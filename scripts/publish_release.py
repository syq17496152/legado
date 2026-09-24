#!/usr/bin/env python3
"""
APK 一键发布编排器：版本确认 → 双包构建 → 校验强化 → gh release → git tag

用法:
    ai_tests\\venv\\Scripts\\python.exe scripts\\publish_release.py [--version <ver>] [--dry-run]
        [--platform gitee|github|both] [--config <path>]
        [--confirm-stage build|tag]     # 非交互确认续跑（可重复；AI 代答场景，L2 不适用）
        [--l2-evidence <L2报告路径>]     # L2 真机门禁证据（文件存在且为当日生成）

    或直接双击/命令行 publish.bat（项目根薄壳入口，透传全部参数）

五阶段:
    Stage1 版本确认   --version 显式传入，否则按公式 bump（3.yyMMddHH 型 6 位，
                      与 build.gradle releaseTime() 及 version_pattern 同构）
    Stage2 双包构建   依次 subprocess 调 build-legado.bat（test/release，
                      显式版本第 2 参保证同版本），每包后 bat 内嵌 daemon 清场
    Stage3 校验强化   双包齐全 / Cronet 动态下载双门禁 / apksigner 验签 / 包名版本一致性 /
                      updateLog 当日条目——致命项 fail-fast exit
    gh release gh CLI 上传双包（test 包带 _debug 后缀命名防同名冲突）；
                      gitee 走原 requests 层（2026-09-23 用户裁决：默认仅 github，gitee 暂忽略）
    Stage5 git tag    tag=版本号，push 前人工确认，形成版本回滚锚点

设计文档: docs/specs/build-release-automation/design.md（AD-01~AD-07）
配置文件: scripts/publish_config.json（从 publish_config.example.json 复制并填入 token）
"""

import argparse
import datetime
import difflib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Optional, Dict, List, Tuple

import requests
import urllib3

SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
DEFAULT_CONFIG = SCRIPT_DIR / "publish_config.json"

# Gitee API 层仍走 requests。全局 Session：Windows 环境 Gitee 上传偶发 SSL
# 证书链验证失败，临时禁用验证 + 过滤警告（GitHub 层已改走 gh CLI，不受此影响）
# TODO: 后续排查网络环境（代理/防火墙）根因，恢复严格 SSL 验证
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
SESSION = requests.Session()
SESSION.verify = False

# Stage2 双包构建计划：(包类型, build-legado.bat 参数模板)
# 参数序: <debug|release> <显式版本号>
# 2026-09-23 用户裁决：取消共存包，发版仅 test + release 两包
BUILD_PLAN: List[Tuple[str, List[str]]] = [
    ("test", ["debug", "{version}"]),
    ("release", ["release", "{version}"]),
]

# 双包包名断言表（R7）：上传前逐一核对包名与包类型匹配（防混发）
EXPECTED_PACKAGES = {
    "test": "io.legado.miss.app.debug",
    "release": "io.legado.miss.app.release",
}

# ---------------------------------------------------------------- 更新日志规范常量
# （update-log-release-optimize；规范正本 docs/project-rules/version-delivery-sync.md）
MAX_ITEM_CHARS = 40          # 单条上限（去空白 Unicode 码点）
MAX_DAY_CHARS = 600          # 单天上限（去空白，含标题与分节标题）
                             # 2026-09-19 由 250 上调至 600：实测"一天含多个独立大功能"日
                             # （如 09/13 合并 6 批 = 导入校验/源质量体检/投屏三大功能）250 字必然丢功能
MAX_BODY_CHARS = 65000       # Release body 上限（GitHub --notes 65535，留余量）
DUP_SIMILARITY = 0.85        # C2 条目去重相似度阈值
RELEASE_NOTES_DIR = "output/release-notes"

# C3 事务剔除关键词（命中即整条剔除：内部工程事务对用户不可感知）
# 注意：只列不易误伤的强特征词，避免误剔用户可感知条目（如"下载"不列"载"）
TRIVIAL_KEYWORDS = (
    "构建", "打包", "编译", "依赖升级", "Gradle", "CI", "脚本",
    "日志治理", "埋点", "规范沉淀", "文档沉淀", "代码搬迁", "重命名", "元数据",
    "基准测试", "性能基线", "瘦身", "体积优化",
)

# C4 前后对比表述（只保留"现状"半句）
CONTRAST_TAIL_MARKS = ("现在", "现已", "改为", "调整为")
CONTRAST_HEAD_MARKS = ("此前", "原来", "原先", "之前")


def strip_ws(text: str) -> str:
    """去掉所有空白（字数判定口径：Unicode 码点数，中文按 1 计）"""
    return re.sub(r"\s+", "", text)


def log(stage: str, msg: str, level: str = "INFO"):
    """统一日志输出"""
    print(f"[{level}] [{stage}] {msg}")


def hide_token(token: str) -> str:
    """token 脱敏"""
    if not token or len(token) < 8:
        return "***"
    return token[:4] + "***" + token[-4:]


def parse_args():
    parser = argparse.ArgumentParser(
        description="APK 一键发布编排器（版本确认→双包构建→校验强化→gh release→git tag）")
    parser.add_argument("--version", help="指定版本号（如 3.26.083020），缺省时按公式 bump")
    parser.add_argument("--dry-run", action="store_true", help="全流程模拟预览，无任何副作用")
    parser.add_argument("--platform", choices=["gitee", "github", "both"], default="github",
                        help="发布平台（默认 github；2026-09-23 用户裁决：App 内置 GitHub 加速通道，Gitee 暂忽略）")
    parser.add_argument("--config", help=f"配置文件路径（默认 {DEFAULT_CONFIG}）")
    parser.add_argument("--confirm-stage", action="append", choices=["build", "tag"], default=[],
                        metavar="STAGE",
                        help="非交互确认续跑（可重复：--confirm-stage build --confirm-stage tag；"
                             "AI 代答场景；L2 门禁不适用此参数）")
    parser.add_argument("--l2-evidence", metavar="PATH",
                        help="L2 真机验证报告路径（AI 代答 L2 门禁时必传；"
                             "要求文件存在且修改时间为当日）")
    parser.add_argument("--skip-build", action="store_true",
                        help="跳过 Stage2 构建，复用 output/apk/ 各目录下最新产物直接走校验+发布+tag"
                             "（复用场景：双包已由 build-legado.bat 手工产出；发布版本取各包文件名版本 max）")
    parser.add_argument("--from-version",
                        help="发版正文区间起点版本（如 3.26.090820）；缺省时自动从 git tag 推断"
                             "（取小于当前版本的最大 tag）。发版正文=起点到当前版本的全部更新日志")
    return parser.parse_args()


def read_config(config_path: Path, dry_run: bool = False, platform: str = "both") -> dict:
    """读取配置文件"""
    if not config_path.exists():
        # dry-run 模式下尝试用 example 文件
        example_path = SCRIPT_DIR / "publish_config.example.json"
        if dry_run and example_path.exists():
            log("CONFIG", f"配置文件不存在，dry-run 模式使用 example: {example_path}")
            config_path = example_path
        else:
            log("CONFIG", f"配置文件不存在: {config_path}")
            log("CONFIG", f"请从 publish_config.example.json 复制并填入 token")
            sys.exit(2)
    with open(config_path, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    # 校验必需字段
    platforms_to_check = ["gitee", "github"] if platform == "both" else [platform]
    for p in platforms_to_check:
        if p not in cfg:
            log("CONFIG", f"配置缺少 {p} 段", "ERROR")
            sys.exit(2)
        if not cfg[p].get("token") or "<" in cfg[p]["token"]:
            if dry_run:
                log("CONFIG", f"{p} token 未配置（dry-run 模式，跳过）", "WARN")
            else:
                log("CONFIG", f"{p} token 未配置，无法实际发布", "ERROR")
                sys.exit(2)
    return cfg


def extract_version(filename: str, pattern: str) -> Optional[str]:
    """从文件名提取版本号"""
    m = re.search(pattern, filename)
    if m:
        return m.group(1) if m.groups() else m.group(0)
    return None


def version_to_date(version: str) -> str:
    """版本号转日期: 3.26.072912 → 2026/07/29"""
    parts = version.split(".")
    if len(parts) != 3:
        return ""
    try:
        yy = parts[1]
        mmddhh = parts[2]
        if len(mmddhh) < 4:
            return ""
        return f"20{yy}/{mmddhh[:2]}/{mmddhh[2:4]}"
    except (IndexError, ValueError):
        return ""


def compare_versions(v1: str, v2: str) -> int:
    """比较版本号，返回 1(v1>v2) / -1(v1<v2) / 0(相等)"""
    parts1 = [int(p) for p in v1.split(".")]
    parts2 = [int(p) for p in v2.split(".")]
    for a, b in zip(parts1, parts2):
        if a > b:
            return 1
        if a < b:
            return -1
    return 0


def bump_version() -> str:
    """按公式 bump 版本号：3.yyMMddHH 型 6 位（与 build.gradle releaseTime() 同构）"""
    now = datetime.datetime.now()
    return f"3.{now.strftime('%y')}.{now.strftime('%m%d%H')}"


def scan_apk_files(config: dict, specified_version: Optional[str] = None) -> Tuple[str, Dict[str, Path]]:
    """扫描双包目录，返回 (version, {type: apk_path})"""
    apk_dirs = config["apk_dirs"]
    apk_patterns = config["apk_patterns"]
    version_pattern = config["version_pattern"]

    # 收集所有 APK 文件及其版本号
    all_apks: List[Tuple[str, str, Path]] = []  # (type, version, path)
    for pkg_type, dir_rel in apk_dirs.items():
        dir_path = PROJECT_ROOT / dir_rel
        pattern = apk_patterns.get(pkg_type, "*.apk")
        if not dir_path.exists():
            log("SCAN", f"目录不存在: {dir_path}", "WARN")
            continue
        for apk_file in dir_path.glob(pattern):
            ver = extract_version(apk_file.name, version_pattern)
            if ver:
                all_apks.append((pkg_type, ver, apk_file))

    if not all_apks:
        log("SCAN", "未找到任何 APK 文件", "ERROR")
        sys.exit(1)

    # 确定版本号
    if specified_version:
        version = specified_version
        log("SCAN", f"使用指定版本号: {version}")
    else:
        # 取最大版本号
        version = max(all_apks, key=lambda x: [int(p) for p in x[1].split(".")])[1]
        log("SCAN", f"自动取最大版本号: {version}")

    # 按版本号筛选 APK
    result: Dict[str, Path] = {}
    for pkg_type, ver, apk_path in all_apks:
        if ver == version:
            if pkg_type in result:
                # 同类型同版本多个文件，取较新的（修改时间）
                old = result[pkg_type]
                if apk_path.stat().st_mtime > old.stat().st_mtime:
                    result[pkg_type] = apk_path
            else:
                result[pkg_type] = apk_path

    # 检查是否双包齐全（此处 WARN 仅提示；编排器 Stage3 会 fail-fast）
    missing = set(apk_dirs.keys()) - set(result.keys())
    if missing:
        log("SCAN", f"版本 {version} 缺少包: {missing}", "WARN")
        log("SCAN", f"已有包: {list(result.keys())}")

    for pkg_type, apk_path in result.items():
        size_mb = apk_path.stat().st_size / (1024 * 1024)
        log("SCAN", f"  {pkg_type}: {apk_path.name} ({size_mb:.1f}MB)")

    return version, result


def collect_latest_artifacts(config: dict) -> Tuple[str, Dict[str, Path]]:
    """--skip-build 模式：各包目录取 mtime 最新产物（不限文件名版本），发布版本取各包版本 max。

    复用场景：双包已由 build-legado.bat 分批产出（文件名时间戳可能跨小时不一致），
    versionName 差异在 Stage3 以 WARN 提示（skip_build 宽松模式），不阻断发布。
    """
    apk_dirs = config["apk_dirs"]
    apk_patterns = config["apk_patterns"]
    version_pattern = config["version_pattern"]

    result: Dict[str, Path] = {}
    versions: List[str] = []
    for pkg_type, dir_rel in apk_dirs.items():
        dir_path = PROJECT_ROOT / dir_rel
        pattern = apk_patterns.get(pkg_type, "*.apk")
        if not dir_path.exists():
            log("SCAN", f"目录不存在: {dir_path}", "ERROR")
            sys.exit(1)
        candidates = [p for p in dir_path.glob(pattern) if extract_version(p.name, version_pattern)]
        if not candidates:
            log("SCAN", f"{pkg_type} 目录无匹配产物: {dir_path}\\{pattern}", "ERROR")
            sys.exit(1)
        latest = max(candidates, key=lambda p: p.stat().st_mtime)
        ver = extract_version(latest.name, version_pattern)
        if ver:
            versions.append(ver)
        result[pkg_type] = latest
        size_mb = latest.stat().st_size / (1024 * 1024)
        log("SCAN", f"  {pkg_type}: {latest.name} ({size_mb:.1f}MB, mtime 最新)")

    version = max(versions, key=lambda v: [int(p) for p in v.split(".")])
    log("SCAN", f"--skip-build 复用双包，发布版本取 max: {version}")
    distinct = sorted(set(versions))
    if len(distinct) > 1:
        log("SCAN", f"双包文件名版本存在差异 {distinct}（跨小时构建所致，versionName 差异仅 WARN）", "WARN")
    return version, result


def list_git_tags() -> List[str]:
    """读取本地 git tag 列表（失败返回空列表，由调用方 fail-fast 提示传 --from-version）"""
    try:
        proc = subprocess.run(["git", "tag"], cwd=str(PROJECT_ROOT),
                              capture_output=True, text=True, timeout=30,
                              encoding="utf-8", errors="replace")
        if proc.returncode != 0:
            return []
        return [t.strip() for t in proc.stdout.splitlines() if t.strip()]
    except Exception:
        return []


def infer_prev_version(current: str) -> Optional[str]:
    """区间起点推断：本地 git tag 中"小于当前版本且最大"者。

    用 compare_versions（按 . 分段转 int）比较——裸字符串比较在段长不一致时会误判。
    tag 命名非 `3.26.MMDDHH` 规范（无法解析为数字段）时跳过该 tag。
    """
    candidates: List[str] = []
    for tag in list_git_tags():
        norm = tag.lstrip("vV")  # 容错 v 前缀
        if not re.fullmatch(r"\d+(\.\d+)+", norm):
            continue
        try:
            if compare_versions(norm, current) < 0:
                candidates.append(norm)
        except (ValueError, IndexError):
            continue
    if not candidates:
        return None
    return max(candidates, key=lambda v: [int(p) for p in v.split(".")])


def parse_update_log_blocks(content: str) -> List[Tuple[str, str]]:
    """把 updateLog 拆为 [(日期, 块原文)]，块原文含标题行本身，按文件出现顺序"""
    matches = list(re.finditer(r"\*\*(\d{4}/\d{2}/\d{2})", content))
    blocks: List[Tuple[str, str]] = []
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(content)
        blocks.append((m.group(1), content[start:end].strip()))
    return blocks


def check_log_structure(blocks: List[Tuple[str, str]]):
    """V1/V2 结构门禁：同日多条 / 日期非严格倒序 → fail-fast"""
    dates = [d for d, _ in blocks]
    seen = set()
    for d in dates:
        if d in seen:
            log("CLEAN", f"同日存在多个日期条目: {d} —— 必须合并为一条"
                         f"（一天多条会让发版正文丢失后续批次）", "ERROR")
            sys.exit(1)
        seen.add(d)
    for i in range(1, len(dates)):
        if dates[i] >= dates[i - 1]:
            log("CLEAN", f"日期顺序错乱: {dates[i]} 出现在 {dates[i - 1]} 之后"
                         f"（要求严格倒序，新在前）", "ERROR")
            sys.exit(1)


def day_char_count(block: str) -> int:
    """单天字数：标题行 + 分节标题行 + 条目行，去空白合计"""
    return len(strip_ws(block))


def compress_item(item: str) -> str:
    """C5 超长压缩：按标点就近截断至 ≤MAX_ITEM_CHARS；找不到标点则硬截断补 …"""
    if len(item) <= MAX_ITEM_CHARS:
        return item
    for i in range(len(item) - 1, 0, -1):
        if item[i] in "。；，、":
            if i <= MAX_ITEM_CHARS:
                return item[:i]
    return item[:MAX_ITEM_CHARS - 1] + "…"


def drop_contrast_head(text: str) -> str:
    """C4 前后对比截断：含"此前/原来/原先/之前"且含"现在/现已/改为/调整为"时只留后半句"""
    if not any(h in text for h in CONTRAST_HEAD_MARKS):
        return text
    best = -1
    for mark in CONTRAST_TAIL_MARKS:
        idx = text.find(mark)
        if idx > 0 and (best < 0 or idx < best):
            best = idx
    return text[best:] if best > 0 else text


def is_trivial_item(item: str) -> bool:
    """C3 事务剔除：命中内部工程事务关键词"""
    return any(kw in item for kw in TRIVIAL_KEYWORDS)


def dedup_items(items: List[str]) -> Tuple[List[str], int]:
    """C2 去重：相似度 ≥ DUP_SIMILARITY 视为重复，保留较长的一条"""
    kept: List[str] = []
    dropped = 0
    for item in items:
        plain = strip_ws(item)
        dup_idx = -1
        for i, k in enumerate(kept):
            if difflib.SequenceMatcher(None, plain, strip_ws(k)).ratio() >= DUP_SIMILARITY:
                dup_idx = i
                break
        if dup_idx >= 0:
            if len(plain) > len(strip_ws(kept[dup_idx])):
                kept[dup_idx] = item
            dropped += 1
        else:
            kept.append(item)
    return kept, dropped


def clean_day_block(date_str: str, blocks: List[str]) -> Tuple[str, Dict[str, int]]:
    """清洗单天：C3 事务剔除 → C2 去重 → C4 前后对比截断 → C5 超长压缩

    返回 (清洗后块文本, 统计)。标题沿用该日首个块的原文（保留其括号说明）。
    """
    stats = {"dropped": 0, "deduped": 0, "truncated": 0}
    title = blocks[0].splitlines()[0].strip()
    # 收集该日全部分节（保持出现顺序）
    section_order: List[str] = []
    section_items: Dict[str, List[str]] = {}
    for block in blocks:
        for line in block.splitlines()[1:]:
            s = line.strip()
            if s.startswith("### "):
                name = s
                if name not in section_items:
                    section_items[name] = []
                    section_order.append(name)
            elif s.startswith("- "):
                if not section_order:  # 无分节的裸条目
                    section_items.setdefault("### 其它", [])
                    section_order.append("### 其它")
                section_items[section_order[-1]].append(s[2:].strip())

    lines = [title]
    for name in section_order:
        cleaned: List[str] = []
        for item in section_items[name]:
            if is_trivial_item(item):
                stats["dropped"] += 1
                continue
            cleaned.append(item)
        cleaned, deduped = dedup_items(cleaned)
        stats["deduped"] += deduped
        final: List[str] = []
        for item in cleaned:
            new = drop_contrast_head(item)
            new2 = compress_item(new)
            if new2 != item:
                stats["truncated"] += 1
            final.append(new2)
        if final:
            lines.append(name)
            lines.extend(f"- {i}" for i in final)
    return "\n".join(lines), stats


def clean_release_notes(content: str, version: str,
                        from_version: Optional[str]) -> Tuple[str, Dict[str, int]]:
    """发版前置清洗加工（全自动，规则 C1~C6）——必须先于构建执行。

    产出 Release body：版本区间标注 + 清洗后的区间内全部天条目。
    任一残留（超长/结构违规/body 超限）→ fail-fast，不产出文件。
    """
    end_date = version_to_date(version)
    if not end_date:
        log("CLEAN", f"无法从版本号 {version} 解析日期（期望 3.YY.MMDDHH）", "ERROR")
        sys.exit(1)

    start_version = from_version or infer_prev_version(version)
    if not start_version:
        log("CLEAN", "无法推断区间起点：本地无可用 git tag，请显式传 --from-version <版本>", "ERROR")
        sys.exit(1)
    start_date = version_to_date(start_version)
    if not start_date:
        log("CLEAN", f"--from-version 非法（无法解析为日期）: {start_version}", "ERROR")
        sys.exit(1)
    if start_date > end_date:
        log("CLEAN", f"区间起点 {start_version}({start_date}) 晚于当前版本 {version}({end_date})", "ERROR")
        sys.exit(1)

    blocks = parse_update_log_blocks(content)
    if not blocks:
        log("CLEAN", "updateLog 中未找到任何 **YYYY/MM/DD** 条目", "ERROR")
        sys.exit(1)
    check_log_structure(blocks)  # V1 同日多条 / V2 严格倒序

    in_range = [(d, b) for d, b in blocks if start_date <= d <= end_date]
    if not in_range:
        log("CLEAN", f"区间 {start_date}~{end_date} 内无任何日志条目"
                     f"（请按 version-delivery-sync 规范补写当日条目）", "ERROR")
        sys.exit(1)

    # C1 同日合并（结构门禁已保证区间内同日唯一，此处按日期分组以兼容历史）
    grouped: Dict[str, List[str]] = {}
    order: List[str] = []
    for d, b in in_range:
        if d not in grouped:
            grouped[d] = []
            order.append(d)
        grouped[d].append(b)

    total = {"dropped": 0, "deduped": 0, "truncated": 0}
    rendered: List[str] = []
    for d in order:  # order 已是倒序（结构门禁保证）
        text, stats = clean_day_block(d, grouped[d])
        for k in total:
            total[k] += stats[k]
        chars = day_char_count(text)
        if chars > MAX_DAY_CHARS:
            log("CLEAN", f"清洗后仍超长: {d} 实际{chars}字 限制{MAX_DAY_CHARS}字", "ERROR")
            sys.exit(1)
        for line in text.splitlines():
            if line.startswith("- ") and len(strip_ws(line[2:])) > MAX_ITEM_CHARS:
                log("CLEAN", f"清洗后仍有超长条目({d}): {strip_ws(line[2:])[:30]}...", "ERROR")
                sys.exit(1)
        rendered.append(text)

    body = f"{start_version} → {version}\n\n" + "\n\n".join(rendered)
    if len(body) > MAX_BODY_CHARS:
        log("CLEAN", f"发版正文超长: {len(body)} 字符 限制{MAX_BODY_CHARS}"
                     f"（请用更靠后的 --from-version 缩小覆盖区间）", "ERROR")
        sys.exit(1)

    before_items = sum(1 for _, b in in_range for line in b.splitlines() if line.strip().startswith("- "))
    after_items = sum(1 for t in rendered for line in t.splitlines() if line.startswith("- "))
    total["before_items"] = before_items
    total["after_items"] = after_items
    total["days"] = len(rendered)
    total["chars"] = len(body)

    log("CLEAN", f"区间 {start_version}({start_date}) → {version}({end_date})，覆盖 {len(rendered)} 天")
    log("CLEAN", f"清洗统计: 条目 {before_items} → {after_items}"
                 f"（剔除事务 {total['dropped']} / 去重 {total['deduped']} / 压缩 {total['truncated']}），"
                 f"正文 {len(body)} 字符")
    return body, total


def write_release_notes(version: str, body: str, dry_run: bool) -> Optional[Path]:
    """落盘发版信息产物 output/release-notes/{version}.md（供发版前审阅与事后追溯）"""
    if dry_run:
        log("CLEAN", f"[dry-run] 将写入 {RELEASE_NOTES_DIR}/{version}.md（{len(body)} 字符）")
        return None
    out_dir = PROJECT_ROOT / RELEASE_NOTES_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{version}.md"
    out_path.write_text(body + "\n", encoding="utf-8")
    log("CLEAN", f"发版信息已产出: {out_path}")
    return out_path


def read_update_log(log_path: Path, version: str,
                    from_version: Optional[str] = None) -> str:
    """读取更新日志并产出发版正文（区间全集 + 前置清洗）。

    update-log-release-optimize：由"取版本号当天首条"改为"上一已发布版本→当前版本区间全集"，
    并强制经 clean_release_notes 清洗（全自动 C1~C6）。

    fail-fast（R2）：任何缺失/违规场景直接 exit，不静默回退。
    """
    if not log_path.exists():
        log("LOG", f"更新日志不存在: {log_path}", "ERROR")
        log("LOG", "发布中止：请先创建 updateLog.md 并补写当日条目", "ERROR")
        sys.exit(1)

    content = log_path.read_text(encoding="utf-8")
    body, _stats = clean_release_notes(content, version, from_version)
    return body


def get_upload_name(pkg_type: str, apk_path: Path, version: str) -> str:
    """根据包类型生成上传到 Release 的文件名，避免 test/release 包同名冲突。

    test 包（debug 构建）→ legado_miss_app_debug_{version}.apk
    release 包（正式构建）→ legado_miss_app_{version}.apk
    """
    if pkg_type == "test":
        return f"legado_miss_app_debug_{version}.apk"
    return apk_path.name


def retry_on_failure(func, max_attempts: int, backoff_base: int, stage: str, *args, **kwargs):
    """重试机制（Gitee requests 层）：网络错误/5xx 重试，4xx 鉴权错误立即终止"""
    last_exc = None
    for attempt in range(1, max_attempts + 1):
        try:
            return func(*args, **kwargs)
        except requests.exceptions.HTTPError as e:
            status = e.response.status_code if e.response is not None else 0
            if 400 <= status < 500:
                log(stage, f"HTTP {status} 鉴权/请求错误，不重试: {e}", "ERROR")
                return None
            log(stage, f"HTTP {status}（尝试 {attempt}/{max_attempts}）: {e}", "WARN")
            last_exc = e
        except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
            log(stage, f"网络错误（尝试 {attempt}/{max_attempts}）: {e}", "WARN")
            last_exc = e
        except Exception as e:
            log(stage, f"未知错误（尝试 {attempt}/{max_attempts}）: {e}", "ERROR")
            last_exc = e
        if attempt < max_attempts:
            wait = backoff_base ** attempt
            log(stage, f"等待 {wait}s 后重试...")
            time.sleep(wait)
    log(stage, f"重试 {max_attempts} 次后仍失败", "ERROR")
    return None


def confirm(prompt: str, stage: str, confirmed_stages: List[str], dry_run: bool) -> bool:
    """普通确认点（构建前/tag）。

    三通道：dry_run 模拟通过 / --confirm-stage 参数化代答（AD-07） / stdin 交互（默认 N）。
    """
    if dry_run:
        log(stage, f"[dry-run] 将交互确认「{prompt}」—— 模拟通过")
        return True
    if stage in confirmed_stages:
        log(stage, f"[--confirm-stage] 「{prompt}」—— 已由参数确认（AI 代答留痕）")
        return True
    try:
        ans = input(f"{prompt} [y/N]: ").strip().lower()
    except EOFError:
        ans = ""
    return ans == "y"


# === Stage 1: 版本确认 ===

def stage1_confirm_version(args, config: dict) -> str:
    """Stage1 版本确认/bump（R1）"""
    if args.version:
        version = args.version
        log("STAGE1", f"使用指定版本号: {version}")
    else:
        version = bump_version()
        log("STAGE1", f"按公式 bump 版本号: {version}（3.yyMMddHH 型 6 位，与 releaseTime() 同构）")
    if version_to_date(version) == "":
        log("STAGE1", f"版本号格式异常: {version}（期望 3.YY.MMDDHH 6 位尾段）", "ERROR")
        sys.exit(2)
    if not confirm(f"版本号 {version}（updateLog 需已有当日条目），确认开始双包构建",
                   "build", args.confirm_stage, args.dry_run):
        log("STAGE1", "未确认构建，中止发布", "WARN")
        sys.exit(1)
    return version


# === Stage 2: 双包构建 ===

def stage2_build_two(version: str, dry_run: bool) -> Dict[str, Path]:
    """Stage2 双包构建（R1/R8）：subprocess 调 build-legado.bat，解析 [ARTIFACT] 行。

    - 显式版本第 2 参保证双包同版本
    - daemon 复用是双包提速核心（2026-09-15 build-three-packages-optimize）：bat 成功路径
      已无清场逻辑，双包全程共享 daemon（Kotlin 增量快照/构建缓存跨包生效）；
      禁止恢复成功路径清场！失败路径清场 + 瞬态锁自动重试由 bat 内置处理
    - stdin=DEVNULL：bat 内 pause 读到 EOF 立即返回，不阻塞编排器
    """
    artifacts: Dict[str, Path] = {}
    bat = PROJECT_ROOT / "build-legado.bat"
    if not bat.exists():
        log("BUILD", f"构建脚本不存在: {bat}", "ERROR")
        sys.exit(1)
    artifact_re = re.compile(r"^\[ARTIFACT\]\s+(.+\.apk)\s*$")
    for pkg, arg_tpl in BUILD_PLAN:
        bat_args = [a.format(version=version) for a in arg_tpl]
        cmd = ["cmd", "/c", str(bat)] + bat_args
        if dry_run:
            log("BUILD", f"[dry-run] 将执行: build-legado.bat {' '.join(bat_args)}")
            continue
        log("BUILD", f"构建 {pkg} 包: build-legado.bat {' '.join(bat_args)}")
        proc = subprocess.Popen(
            cmd, cwd=str(PROJECT_ROOT), stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace")
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.rstrip("\r\n")
            print(line)
            m = artifact_re.match(line.strip())
            if m:
                artifacts[pkg] = Path(m.group(1))
        proc.wait()
        if proc.returncode != 0:
            log("BUILD", f"{pkg} 包构建失败（exit={proc.returncode}），中止发布", "ERROR")
            sys.exit(1)
        if pkg not in artifacts:
            log("BUILD", f"{pkg} 包未捕获 [ARTIFACT] 行（构建输出异常，Stage3 将兜底重扫）", "WARN")
    return artifacts


# === Stage 3: 校验强化 ===

def find_sdk_tool(tool_name: str) -> Optional[Path]:
    """在 ANDROID_HOME/build-tools 下查找工具（apksigner.bat / aapt2.exe），取版本最高目录"""
    sdk: Optional[Path] = None
    lp = PROJECT_ROOT / "local.properties"
    if lp.exists():
        for line in lp.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.strip().startswith("sdk.dir"):
                sdk = Path(line.split("=", 1)[1].strip().replace("\\\\", "\\"))
                break
    if not sdk or not sdk.exists():
        sdk = Path(os.environ.get("ANDROID_HOME", r"C:\Android\Sdk"))
    bt = sdk / "build-tools"
    if not bt.exists():
        return None
    for ver_dir in sorted(bt.iterdir(), reverse=True):
        candidate = ver_dir / tool_name
        if candidate.exists():
            return candidate
    return None


def check_cronet_packaging(apk: Path) -> Tuple[bool, str]:
    """Cronet **动态下载双向门禁**（R4，zipfile 直查，不依赖 bat 内校验）。

    现行标准（2026-09-15 cronet-dynamic-download 迁移，见
    `docs/project-rules/package-naming.md`「Cronet 动态下载门禁」）：
      ① APK **必须不含** `lib/*/libcronet*.so` —— 存在即 bundled 泄漏（包体异常）
      ② APK **必须含** `assets/cronet.json` —— 缺失则运行时按 ABI 下载 so 的校验必失败

    ⚠️ 旧实现（cronet-bundled 时代）要求"APK 内含 libcronet*.so"，与现行路线完全相反：
    按现行动态下载打的包会被它 100% 拦截（2026-09-17 双包发布实测 exit 1 铁证）。
    """
    try:
        with zipfile.ZipFile(apk) as z:
            names = z.namelist()
    except zipfile.BadZipFile:
        return False, "APK 无法解析（BadZipFile）"
    leaked = [n for n in names if n.startswith("lib/") and "libcronet" in n and n.endswith(".so")]
    if leaked:
        return False, f"lib/*/libcronet*.so 泄漏（bundled 残留）: {leaked[:3]}"
    if "assets/cronet.json" not in names:
        return False, "assets/cronet.json 缺失（运行时 so 下载校验必失败）"
    return True, "无 libcronet*.so + 含 assets/cronet.json"


def run_tool(tool: Path, tool_args: List[str]) -> Tuple[bool, str]:
    """执行 SDK 工具，返回 (成功, 合并输出)"""
    try:
        proc = subprocess.run([str(tool)] + tool_args, capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=300)
        return proc.returncode == 0, (proc.stdout or "") + (proc.stderr or "")
    except subprocess.TimeoutExpired:
        return False, "tool timeout (300s)"


def stage3_verify(config: dict, version: str, dry_run: bool,
                  skip_build: bool = False, pre_scanned: Optional[Dict[str, Path]] = None,
                  from_version: Optional[str] = None) -> Tuple[Dict[str, Path], str]:
    """Stage3 校验强化（R2-R5）：致命项 fail-fast exit，建议项 WARN 清单。

    skip_build=True：使用 pre_scanned 产物（collect_latest_artifacts 扫描结果），
    跳过按版本过滤的双包齐全重扫；versionName 与发布版本不一致降级 WARN（跨小时构建差异）。
    """
    # 致命项 1：双包齐全（重扫兜底，不信任 Stage2 [ARTIFACT] 解析）
    if skip_build and pre_scanned is not None:
        apks = dict(pre_scanned)
        missing = [k for k in EXPECTED_PACKAGES if k not in apks]
    else:
        _, apks = scan_apk_files(config, version)
        missing = [k for k in EXPECTED_PACKAGES if k not in apks]

    if dry_run:
        # dry-run 允许无产物（bump 新版本尚未构建），仅模拟校验；
        # updateLog 校验只读无副作用，dry-run 也真实执行（L1 门禁预演）
        if missing:
            log("VERIFY", f"[dry-run] 产物缺失 {missing} —— 实际发布时将 fail-fast 拦截（模拟通过）")
        else:
            log("VERIFY", "[dry-run] 将执行: Cronet 动态下载双门禁检查 / apksigner 验签 / "
                          "aapt2 包名版本一致性 / updateLog 区间门禁")
        log_path = PROJECT_ROOT / config["update_log_path"]
        body = read_update_log(log_path, version, from_version)
        return apks, body

    if missing:
        log("VERIFY", f"产物缺失（R3 fail-fast）: {missing} —— 不再仅 WARN", "ERROR")
        sys.exit(1)

    # 致命项 2：Cronet 动态下载双向门禁（R4，exit 1 不降级）
    for pkg, apk in apks.items():
        ok, detail = check_cronet_packaging(apk)
        if not ok:
            log("VERIFY", f"[{pkg}] Cronet 打包门禁失败: {apk.name} —— {detail}（exit 1）", "ERROR")
            sys.exit(1)
    log("VERIFY", "Cronet 动态下载双门禁：双包全部通过（无 libcronet*.so + 含 assets/cronet.json）")

    # 致命项 3/4：apksigner 验签 + aapt2 包名/版本一致性（R5）
    apksigner = find_sdk_tool("apksigner.bat")
    aapt2 = find_sdk_tool("aapt2.exe")
    if not apksigner or not aapt2:
        log("VERIFY", f"未找到 apksigner/aapt2（sdk={find_sdk_tool('apksigner.bat') and 'OK' or 'MISS'}，"
                      f"检查 local.properties sdk.dir 或 ANDROID_HOME 的 build-tools）", "ERROR")
        sys.exit(1)
    for pkg, apk in apks.items():
        ok, out = run_tool(apksigner, ["verify", "--print-certs", str(apk)])
        if not ok:
            log("VERIFY", f"[{pkg}] apksigner 验签失败: {apk.name}\n{out[-500:]}", "ERROR")
            sys.exit(1)
        ok, out = run_tool(aapt2, ["dump", "badging", str(apk)])
        if not ok:
            log("VERIFY", f"[{pkg}] aapt2 读取失败: {out[-300:]}", "ERROR")
            sys.exit(1)
        m = re.search(r"package: name='([^']+)'\s+versionCode='\d+'\s+versionName='([^']+)'", out)
        if not m:
            log("VERIFY", f"[{pkg}] badging 解析失败（package 行缺失）", "ERROR")
            sys.exit(1)
        pkg_name, ver_name = m.group(1), m.group(2)
        if pkg_name != EXPECTED_PACKAGES[pkg]:
            log("VERIFY", f"[{pkg}] 包名不一致: 期望 {EXPECTED_PACKAGES[pkg]}，实际 {pkg_name}", "ERROR")
            sys.exit(1)
        if ver_name != version:
            # debug 构建的 versionName 带 versionNameSuffix 后缀（如 3.26.083022debug），
            # 允许"精确相等或以版本号为前缀"；其余不一致为致命错误
            if ver_name.startswith(version):
                log("VERIFY", f"[{pkg}] versionName 含构建后缀: {ver_name}（基版本 {version} 匹配）")
            elif skip_build and ver_name[:9] == version[:9]:
                # --skip-build 复用模式：跨小时构建的文件名版本差异（如 3.26.090517 vs 3.26.090518），
                # versionName 由构建时刻公式生成导致差异——同日期（前 9 字符 3.YY.MMDD）即降级 WARN 不阻断
                log("VERIFY", f"[{pkg}] --skip-build 复用包版本差异: {ver_name} vs 发布版本 {version}（同日构建，WARN 放行）", "WARN")
            else:
                log("VERIFY", f"[{pkg}] 版本不一致: 期望 {version}，实际 {ver_name}", "ERROR")
                sys.exit(1)
        log("VERIFY", f"[{pkg}] 验签通过 包名={pkg_name} 版本={ver_name}")

    # 建议项（WARN 不阻断）：版本日期与今天偏差
    v_date = version_to_date(version)
    today = datetime.date.today().strftime("%Y/%m/%d")
    if v_date != today:
        log("VERIFY", f"版本号日期 {v_date} 与今天 {today} 不一致（重发旧版本？仅提示）", "WARN")

    # 致命项 5：updateLog 发版正文（区间全集 + 前置清洗，R2 fail-fast）
    log_path = PROJECT_ROOT / config["update_log_path"]
    body = read_update_log(log_path, version, from_version)

    # 致命项 6：主题一致性门禁（门禁 16 取色 token / 门禁 17 宿主刷新覆盖）
    stage3_theme_gates()
    # 致命项 7（H2.2）：**交付门禁**（run_gates --stage deliver）—— 交付/归档前的最终闸门
    stage3b_deliver_gates()

    return apks, body


# === 主题一致性门禁（发布链路接线点 3/3）===

THEME_GATE_SCRIPTS = (
    ("门禁 16 · 取色 token", "ai_tests/scripts/audit_theme_token_violation.py", True),
    ("门禁 17 · 宿主刷新覆盖", "ai_tests/scripts/audit_host_refresh_coverage.py", False),
)


def _last_release_tag() -> str:
    """最近 release tag：作为「本次发布引入的变更文件」的比对基准；无 tag 时回退 HEAD~1。"""
    try:
        out = subprocess.check_output(
            ["git", "describe", "--tags", "--abbrev=0"],
            cwd=str(PROJECT_ROOT), stderr=subprocess.STDOUT,
        )
        tag = out.decode("utf-8", "replace").strip()
        return tag or "HEAD~1"
    except (subprocess.CalledProcessError, FileNotFoundError, OSError):
        return "HEAD~1"


def _run_gate_runner(stage: str, base: str, fatal_label: str) -> None:
    """统一调用门禁 runner（H2.2/H2.4）：fail-fast + GATE_NESTED 传递。

    · `--exclude G-06 G-09`：G-06 的 cmd 是本机不存在的 `git hook pre-push`（必红），
      G-09 是发布链路自身（防自调）；
    · `--base-ref <最近 release tag>`：取色门禁语义 = 比对本次发布引入的变更；
    · GATE_NESTED：若本进程已在门禁链内（由 runner 派生），子 runner 会按哨兵自行跳过。
    """
    runner = PROJECT_ROOT / "ai_tests" / "scripts" / "run_gates.py"
    if not runner.is_file():
        log("VERIFY", f"门禁 runner 缺失（{runner}）—— 本地工作区不完整", "ERROR")
        sys.exit(1)
    cmd = [sys.executable, str(runner), "--stage", stage,
           "--exclude", "G-06", "G-09", "--base-ref", base]
    if os.environ.get("GATE_NESTED") == "1":
        log("VERIFY", "检测到门禁链内调用（GATE_NESTED=1）⇒ 子 runner 将按哨兵跳过（防递归）")
    try:
        out = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True,
                             encoding="utf-8", errors="replace")
    except OSError as exc:  # noqa: BLE001
        log("VERIFY", f"门禁 runner 执行失败: {exc}", "ERROR")
        sys.exit(1)
    if out.returncode != 0:
        log("VERIFY", f"{fatal_label}未通过（exit {out.returncode}，基准 {base}）", "ERROR")
        for line in (out.stdout or "").strip().splitlines()[-12:]:
            log("VERIFY", f"  | {line}")
        sys.exit(1)
    log("VERIFY", f"{fatal_label}全部通过（runner --stage {stage}，基准 {base}）")


def _legacy_direct_theme_gates(base: str) -> None:
    """H2.3 回退分支（flag 切换）：改造前的**硬编码直调**实现（不经 runner）。

    用途：runner 改造若在真实发版中出现回归，用 `LEGACY_DIRECT_THEME_GATES=1` 一键回到改造前
    行为（直调 THEME_GATE_SCRIPTS 两个脚本并 fail-fast），而不是只能回滚代码版本
    ⇒ 保证发布链路永远有一条可用通道（验证标准：切换 flag 后行为与改造前一致）。
    差异说明：改造前直调用的基准参数为 `--diff`（worktree vs HEAD，提交后恒空 ⇒ 有恒 PASS 陷阱），
    此处按现行语义传 `--base <最近 release tag>`，更严格；其余 keep-alive 行为一致。
    """
    for name, script, need_base in THEME_GATE_SCRIPTS:
        cmd = [sys.executable, str(PROJECT_ROOT / script)]
        if need_base:
            cmd += ["--base", base]
        try:
            proc = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True,
                                  encoding="utf-8", errors="replace")
        except OSError as exc:  # noqa: BLE001
            log("VERIFY", f"{name} 直调回退分支执行失败: {exc}", "ERROR")
            sys.exit(1)
        if proc.returncode != 0:
            log("VERIFY", f"{name} 未通过（直调回退分支 exit {proc.returncode}）", "ERROR")
            for line in (proc.stdout or "").strip().splitlines()[-12:]:
                log("VERIFY", f"  | {line}")
            sys.exit(1)
        log("VERIFY", f"{name} 通过（直调回退分支）")


def stage3b_deliver_gates() -> None:
    """Stage3 附加致命项（H2.2）：**交付门禁** —— `run_gates.py --stage deliver`。

    补齐「挂载点 3 = deliver 无真实调用链路」的缺口（spec REQ-1 Scenario「deliver 阶段有挂载点」：
    「存在真实调用 `run_gates.py --stage deliver` 的链路」）。deliver 阶段覆盖
    G-08 全量单测 / G-10 日志 / G-12 迁移 / G-14 死资源 / G-15 元门禁 / G-17 漂移 / G-19 独立抽查
    （G-06/G-09 不属该阶段）。语义 = 「交付产物/归档前」的最终闸门，与 publish 阶段分工：
    publish 管「发布动作相关」，deliver 管「产物本身够不够交付标准」。
    """
    _run_gate_runner("deliver", _last_release_tag(), "交付阶段门禁")


def stage3_theme_gates() -> None:
    """Stage3 附加致命项：主题一致性门禁（门禁 16/17）—— 发布链路 fail-fast。

    依据 docs/project-rules/theme-consistency-iron-rule.md §九。
    基准 = 最近 release tag ⇒ 比对「本次发布实际引入的变更文件」，规避旧门禁
    `--diff`(worktree vs HEAD) 在提交后恒空 ⇒ 恒 PASS 的退化（该项目曾经的实证缺陷）。

    ⚠ 接线点说明（据实修正，2026-09-23）：CI 接线在本项目**不可行** —— `docs/` 与 `ai_tests/`
    按品牌词合规策略不入远端（.gitignore:236-237），`.github/workflows/*` 亦被忽略
    （.gitignore:161-165，test.yml 已注释 push 触发器并注明「本地构建为唯一交付链路」）
    ⇒ 远端无脚本可跑。三处接线点 = ① pre-commit hook ② Trae Hook ③ 本处（发布链路）。
    """
    base = _last_release_tag()
    # H2.3：回退开关 —— 需要回到「改造前硬编码直调」时置 1（防发版链路回归）
    if os.environ.get("LEGACY_DIRECT_THEME_GATES") == "1":
        log("VERIFY", "LEGACY_DIRECT_THEME_GATES=1 ⇒ 走改造前硬编码直调回退分支（不经 runner）")
        _legacy_direct_theme_gates(base)
        return
    # 2026-09-24（门禁包 H2）：由「硬编码直调门禁 16/17 脚本」改为**统一走 runner**，
    # 兑现「新增门禁只改注册表、挂载点自动生效」的承诺（原实现绕过注册表，是设计承诺的偏离）。
    # ⚠ 必须 --exclude G-06 G-09 与 --base-ref（理由见 _run_gate_runner 文档串）。
    # ⚠ 前置（GB-7）：注册表 G-02/G-03 的 stages 必须含 publish，否则本阶段不再覆盖取色门禁。
    _run_gate_runner("publish", base, "发布阶段门禁")


# === L2 真机门禁（不可跳过，AD-05/AD-07）===

def check_l2_evidence(args) -> None:
    """L2 真机门禁三通道：
    - dry-run：模拟通过
    - AI 代答：--l2-evidence 文件存在且修改时间为当日（R13，缺失/过期 exit 拒绝）
    - 人工交互：stdin y/N，默认 N；不提供任何 flag 级跳过
    """
    if args.dry_run:
        log("L2", "[dry-run] 将确认「真机 L2 验证已通过」—— 模拟通过")
        return
    if args.l2_evidence:
        p = Path(args.l2_evidence)
        if not p.is_file():
            log("L2", f"L2 证据文件不存在: {p}", "ERROR")
            sys.exit(1)
        mtime = datetime.date.fromtimestamp(p.stat().st_mtime)
        if mtime != datetime.date.today():
            log("L2", f"L2 证据文件非当日生成: {p}（mtime={mtime}）", "ERROR")
            sys.exit(1)
        log("L2", f"L2 证据校验通过: {p}（mtime={mtime}）")
        return
    try:
        ans = input("真机 L2 验证已通过？[y/N]: ").strip().lower()
    except EOFError:
        ans = ""
    if ans != "y":
        log("L2", "L2 未确认通过，中止发布（已构建产物保留在 output/apk/ 可复用）", "WARN")
        sys.exit(1)
    log("L2", "L2 已确认通过")


# === Gitee API 层（requests 保留：Gitee 无 gh CLI 等价物）===

def gitee_get_release_by_tag(config: dict, tag: str) -> Optional[dict]:
    """查询 Gitee Release by tag"""
    g = config["gitee"]
    url = f"{g['api_base']}/repos/{g['owner']}/{g['repo']}/releases/tags/{tag}"
    resp = SESSION.get(url, params={"access_token": g["token"]}, timeout=30)
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    return resp.json()


def gitee_create_release(config: dict, version: str, body: str) -> Optional[int]:
    """创建 Gitee Release，返回 release_id"""
    g = config["gitee"]
    url = f"{g['api_base']}/repos/{g['owner']}/{g['repo']}/releases"
    payload = {
        "access_token": g["token"],
        "tag_name": version,
        "name": version,
        "body": body,
        "target_commitish": g.get("target_commitish", "main"),
    }
    resp = SESSION.post(url, data=payload, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    return data.get("id")


def gitee_list_assets(config: dict, release_id: int) -> List[str]:
    """列出 Gitee Release 已有 asset 名称"""
    g = config["gitee"]
    url = f"{g['api_base']}/repos/{g['owner']}/{g['repo']}/releases/{release_id}"
    resp = SESSION.get(url, params={"access_token": g["token"]}, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    assets = data.get("assets", [])
    return [a.get("name", "") for a in assets]


def gitee_upload_asset(config: dict, release_id: int, apk_path: Path, upload_name: str) -> bool:
    """上传 Gitee Asset"""
    g = config["gitee"]
    url = f"{g['api_base']}/repos/{g['owner']}/{g['repo']}/releases/{release_id}/attach_files"
    with open(apk_path, "rb") as f:
        files = {"file": (upload_name, f, "application/vnd.android.package-archive")}
        data = {"access_token": g["token"]}
        resp = SESSION.post(url, files=files, data=data, timeout=config["retry"]["timeout"])
    resp.raise_for_status()
    return True


def gitee_publish(config: dict, version: str, body: str, apks: Dict[str, Path], dry_run: bool) -> Dict[str, bool]:
    """Gitee 发布流程"""
    results: Dict[str, bool] = {}
    g = config["gitee"]
    log("GITEE", f"owner={g['owner']}/{g['repo']} token={hide_token(g['token'])}")

    if dry_run:
        log("GITEE", "[dry-run] 将创建 Release tag=" + version)
        for pkg_type, apk in apks.items():
            log("GITEE", f"[dry-run] 将上传 {pkg_type}: {apk.name}")
            results[pkg_type] = True
        return results

    # 查询 Release 是否已存在
    existing = retry_on_failure(gitee_get_release_by_tag, 3, 2, "GITEE", config, version)
    if existing is not None:
        release_id = existing.get("id")
        log("GITEE", f"Release 已存在（id={release_id}），复用")
        existing_assets = set(gitee_list_assets(config, release_id))
    else:
        release_id = retry_on_failure(gitee_create_release, 3, 2, "GITEE", config, version, body)
        if release_id is None:
            log("GITEE", "创建 Release 失败", "ERROR")
            for pkg_type in apks:
                results[pkg_type] = False
            return results
        log("GITEE", f"创建 Release 成功（id={release_id}）")
        existing_assets = set()

    # 上传 APK
    for pkg_type, apk_path in apks.items():
        upload_name = get_upload_name(pkg_type, apk_path, version)
        if upload_name in existing_assets:
            log("GITEE", f"  {pkg_type}: {upload_name} 已存在，跳过")
            results[pkg_type] = True
            continue
        log("GITEE", f"  {pkg_type}: 上传 {upload_name}...")
        ok = retry_on_failure(gitee_upload_asset, 3, 2, "GITEE", config, release_id, apk_path, upload_name)
        results[pkg_type] = ok is not None
        if ok:
            log("GITEE", f"  {pkg_type}: 上传成功")
        else:
            log("GITEE", f"  {pkg_type}: 上传失败", "ERROR")

    return results


# === Stage 4: gh release 发布（GitHub 层改走 gh CLI，Gitee 层保留 requests）===

def gh_run(gh_args: List[str], env: dict, stage: str) -> Optional[subprocess.CompletedProcess]:
    """执行 gh CLI 子命令，3 次指数退避重试；鉴权类失败立即终止。返回 None 表示未找到（404 类）。"""
    cmd = ["gh"] + gh_args
    last_out = ""
    for attempt in range(1, 4):
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", env=env, timeout=1800)
        except subprocess.TimeoutExpired:
            log(stage, f"gh 调用超时（尝试 {attempt}/3）", "WARN")
            time.sleep(2 ** attempt)
            continue
        out = (proc.stdout or "") + (proc.stderr or "")
        last_out = out
        if proc.returncode == 0:
            return proc
        if "not found" in out.lower():
            return None
        if re.search(r"401|403|[Bb]ad credentials", out):
            log(stage, f"gh 鉴权失败，不重试: {hide_token(out[:200])}", "ERROR")
            sys.exit(1)
        log(stage, f"gh 调用失败（尝试 {attempt}/3）: {out[-300:]}", "WARN")
        if attempt < 3:
            time.sleep(2 ** attempt)
    log(stage, f"gh 重试耗尽: {last_out[-300:]}", "ERROR")
    sys.exit(1)


def github_publish_gh(config: dict, version: str, body: str, apks: Dict[str, Path],
                      dry_run: bool) -> Dict[str, bool]:
    """GitHub 发布流程（gh CLI 替代 requests，规避 uploads.github.com SSL 与 51MB+ 双坑）。

    2026-08-30 用户裁决：双包全上传，test 包经 get_upload_name 加 _debug 后缀防同名冲突。
    """
    results: Dict[str, bool] = {}
    g = config["github"]
    repo = f"{g['owner']}/{g['repo']}"
    log("GITHUB", f"repo={repo} token={hide_token(g['token'])} (gh CLI)")

    upload_apks = dict(apks)

    if dry_run:
        # dry-run 允许无产物（bump 新版本尚未构建），仅模拟
        log("GITHUB", f"[dry-run] 将创建/复用 Release tag={version}（body=updateLog 当日条目）")
        for pkg_type, apk in upload_apks.items():
            log("GITHUB", f"[dry-run] 将上传 {pkg_type}: {get_upload_name(pkg_type, apk, version)}")
            results[pkg_type] = True
        return results

    if not upload_apks:
        log("GITHUB", "无可上传产物", "ERROR")
        return results

    env = {**os.environ, "GH_TOKEN": g["token"]}

    # 存在性检查（幂等：已存在复用）
    existing = gh_run(["release", "view", version, "--repo", repo], env, "GITHUB")
    existing_assets: set = set()
    if existing is not None:
        log("GITHUB", "Release 已存在，复用")
        proc = gh_run(["release", "view", version, "--repo", repo,
                       "--json", "assets", "-q", ".assets[].name"], env, "GITHUB")
        if proc is not None and proc.stdout:
            existing_assets = set(proc.stdout.split())
    else:
        log("GITHUB", "Release 不存在，创建")
        gh_run(["release", "create", version, "--title", version, "--notes", body,
                "--target", g.get("target_commitish", "master"), "--repo", repo], env, "GITHUB")

    for pkg_type, apk_path in upload_apks.items():
        upload_name = get_upload_name(pkg_type, apk_path, version)
        if upload_name in existing_assets:
            log("GITHUB", f"  {pkg_type}: {upload_name} 已存在，跳过")
            results[pkg_type] = True
            continue
        # gh release upload 使用文件原始名且不支持重命名：test 包需先复制为
        # _debug 目标名再上传，否则与 release 包原始名冲突（同名 asset 拒绝）
        if upload_name == apk_path.name:
            upload_file = apk_path
        else:
            staging = Path(tempfile.gettempdir()) / "legado_gh_upload"
            staging.mkdir(exist_ok=True)
            upload_file = staging / upload_name
            shutil.copyfile(apk_path, upload_file)
        log("GITHUB", f"  {pkg_type}: 上传 {upload_name}...")
        gh_run(["release", "upload", version, str(upload_file), "--repo", repo], env, "GITHUB")
        results[pkg_type] = True
        log("GITHUB", f"  {pkg_type}: 上传成功")

    return results


# === Stage 5: git tag 回滚锚点 ===

def stage5_git_tag(version: str, confirmed_stages: List[str], dry_run: bool) -> None:
    """Stage5 git tag（R9）：tag=版本号，push 前人工确认，形成回滚锚点"""
    tag = version
    if not confirm(f"推送 git tag {tag}（版本回滚锚点）", "tag", confirmed_stages, dry_run):
        log("TAG", "已确认跳过 tag push（本地不创建 tag）")
        return
    if dry_run:
        log("TAG", f"[dry-run] 将执行: git tag {tag} && git push origin {tag}")
        return
    proc = subprocess.run(["git", "tag", tag], capture_output=True, text=True,
                          cwd=str(PROJECT_ROOT), encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        if "already exists" in (proc.stderr or ""):
            log("TAG", f"tag {tag} 已存在，复用（幂等）")
        else:
            log("TAG", f"创建 tag 失败: {proc.stderr}", "ERROR")
            sys.exit(1)
    proc = subprocess.run(["git", "push", "origin", tag], capture_output=True, text=True,
                          cwd=str(PROJECT_ROOT), encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        log("TAG", f"push tag 失败: {proc.stderr}", "ERROR")
        sys.exit(1)
    log("TAG", f"tag {tag} 已推送（回滚方式: git checkout {tag}）")


# === 主流程：五阶段编排 ===

def main():
    args = parse_args()
    config_path = Path(args.config) if args.config else DEFAULT_CONFIG

    log("MAIN", "=" * 60)
    log("MAIN", "APK 一键发布编排器启动")
    log("MAIN", f"  dry_run={args.dry_run} platform={args.platform} "
               f"confirm_stage={args.confirm_stage or '-'} l2_evidence={args.l2_evidence or '-'}")

    # 读取配置
    config = read_config(config_path, args.dry_run, args.platform)

    # Stage1 版本确认
    version = stage1_confirm_version(args, config)

    # Stage2 产物来源确定（--skip-build 复用模式会以产物扫描结果覆盖 version）
    artifacts: Dict[str, Path] = {}
    if args.skip_build:
        version, artifacts = collect_latest_artifacts(config)
        log("MAIN", f"--skip-build 复用模式：发布版本 {version}（跳过构建，产物来自 output/apk/）")

    # ---------------- 发版前置：更新日志清洗加工（update-log-release-optimize REQ-5） ----------------
    # 位置：版本最终确定之后、双包构建之前 —— 清洗不过即停，不浪费双包构建时间（约 20 分钟）。
    # 产出 output/release-notes/{version}.md 供发版前审阅与事后追溯；
    # Stage3 的 read_update_log 用同版本+同区间+同规则重新清洗，结果与产物文件确定性一致。
    log_path = PROJECT_ROOT / config["update_log_path"]
    if not log_path.exists():
        log("CLEAN", f"更新日志不存在: {log_path} —— 发布中止", "ERROR")
        sys.exit(1)
    log_content = log_path.read_text(encoding="utf-8")
    release_body, clean_stats = clean_release_notes(log_content, version, args.from_version)
    write_release_notes(version, release_body, args.dry_run)

    # Stage2 双包构建（非复用模式）
    if not args.skip_build:
        stage2_build_two(version, args.dry_run)

    # Stage3 校验强化（含更新日志区间门禁 fail-fast）
    apks, body = stage3_verify(config, version, args.dry_run,
                               skip_build=args.skip_build, pre_scanned=artifacts,
                               from_version=args.from_version)
    log("MAIN", f"Release body 预览（前 200 字符）:\n{body[:200]}...")

    # L2 真机门禁（不可跳过，无 flag 旁路）
    check_l2_evidence(args)

    # Stage4 发布（双包全上传；test 包经 get_upload_name 加 _debug 后缀防同名冲突）
    all_results: Dict[str, Dict[str, bool]] = {}
    exit_code = 0

    if args.platform in ("gitee", "both"):
        try:
            all_results["gitee"] = gitee_publish(config, version, body, apks, args.dry_run)
        except Exception as e:
            log("GITEE", f"发布异常: {e}", "ERROR")
            all_results["gitee"] = {k: False for k in apks}
            exit_code = 1

    if args.platform in ("github", "both"):
        try:
            all_results["github"] = github_publish_gh(config, version, body, apks, args.dry_run)
        except Exception as e:
            log("GITHUB", f"发布异常: {e}", "ERROR")
            all_results["github"] = {k: False for k in apks}
            exit_code = 1

    # Stage5 git tag（发布成功才打 tag；任一平台失败仍允许打 tag 以便排查，由汇总退出码反映）
    stage5_git_tag(version, args.confirm_stage, args.dry_run)

    # 汇总输出
    log("MAIN", "=" * 60)
    log("MAIN", "发布结果汇总:")
    log("MAIN", f"  版本号: {version}")
    total_success = 0
    total_count = 0
    for platform, results in all_results.items():
        for pkg_type, ok in results.items():
            status = "✅ 成功" if ok else "❌ 失败"
            log("MAIN", f"  {platform}/{pkg_type}: {status}")
            total_count += 1
            if ok:
                total_success += 1
    log("MAIN", f"  总计: {total_success}/{total_count} 成功")

    if not args.dry_run and total_success < total_count:
        exit_code = 1

    log("MAIN", f"退出码: {exit_code}")
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
