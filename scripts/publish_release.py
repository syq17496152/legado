#!/usr/bin/env python3
"""
APK 一键发布编排器：版本确认 → 三包构建 → 校验强化 → gh release → git tag

用法:
    ai_tests\\venv\\Scripts\\python.exe scripts\\publish_release.py [--version <ver>] [--dry-run]
        [--platform gitee|github|both] [--config <path>]
        [--confirm-stage build|tag]     # 非交互确认续跑（可重复；AI 代答场景，L2 不适用）
        [--l2-evidence <L2报告路径>]     # L2 真机门禁证据（文件存在且为当日生成）

    或直接双击/命令行 publish.bat（项目根薄壳入口，透传全部参数）

五阶段:
    Stage1 版本确认   --version 显式传入，否则按公式 bump（3.yyMMddHH 型 6 位，
                      与 build.gradle releaseTime() 及 version_pattern 同构）
    Stage2 三包构建   依次 subprocess 调 build-legado.bat（test/release/coexist，
                      显式版本第 3 参保证同版本），每包后 bat 内嵌 daemon 清场
    Stage3 校验强化   三包齐全 / libcronet.so / apksigner 验签 / 包名版本一致性 /
                      updateLog 当日条目——致命项 fail-fast exit
    Stage4 gh release gh CLI 上传 release + coexist（test 包仅本地归档，包名禁令）；
                      gitee 走原 requests 层
    Stage5 git tag    tag=版本号，push 前人工确认，形成版本回滚锚点

设计文档: docs/specs/build-release-automation/design.md（AD-01~AD-07）
配置文件: scripts/publish_config.json（从 publish_config.example.json 复制并填入 token）
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
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

# Stage2 三包构建计划：(包类型, build-legado.bat 参数模板)
# 参数序: <debug|release> <customAppId 或 "-" 占位> <显式版本号>
BUILD_PLAN: List[Tuple[str, List[str]]] = [
    ("test", ["debug", "-", "{version}"]),
    ("release", ["release", "-", "{version}"]),
    ("coexist", ["debug", "io.legado.app", "{version}"]),
]

# 包名禁令断言表（R7）：Release 仅接受 release + coexist 产物
EXPECTED_PACKAGES = {
    "test": "io.legado.miss.app.debug",
    "release": "io.legado.miss.app.release",
    "coexist": "io.legado.app.debug",
}


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
        description="APK 一键发布编排器（版本确认→三包构建→校验强化→gh release→git tag）")
    parser.add_argument("--version", help="指定版本号（如 3.26.083020），缺省时按公式 bump")
    parser.add_argument("--dry-run", action="store_true", help="全流程模拟预览，无任何副作用")
    parser.add_argument("--platform", choices=["gitee", "github", "both"], default="both",
                        help="发布平台（默认 both）")
    parser.add_argument("--config", help=f"配置文件路径（默认 {DEFAULT_CONFIG}）")
    parser.add_argument("--confirm-stage", action="append", choices=["build", "tag"], default=[],
                        metavar="STAGE",
                        help="非交互确认续跑（可重复：--confirm-stage build --confirm-stage tag；"
                             "AI 代答场景；L2 门禁不适用此参数）")
    parser.add_argument("--l2-evidence", metavar="PATH",
                        help="L2 真机验证报告路径（AI 代答 L2 门禁时必传；"
                             "要求文件存在且修改时间为当日）")
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
    """扫描三包目录，返回 (version, {type: apk_path})"""
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

    # 检查是否三包齐全（此处 WARN 仅提示；编排器 Stage3 会 fail-fast）
    missing = set(apk_dirs.keys()) - set(result.keys())
    if missing:
        log("SCAN", f"版本 {version} 缺少包: {missing}", "WARN")
        log("SCAN", f"已有包: {list(result.keys())}")

    for pkg_type, apk_path in result.items():
        size_mb = apk_path.stat().st_size / (1024 * 1024)
        log("SCAN", f"  {pkg_type}: {apk_path.name} ({size_mb:.1f}MB)")

    return version, result


def read_update_log(log_path: Path, version: str) -> str:
    """读取更新日志，提取对应日期的条目。

    fail-fast（R2）：任何缺失场景直接 exit，废除"自动发布 {version}"静默回退——
    updateLog 与发布产物的一致性是 version-delivery-sync 门禁的硬要求。
    """
    if not log_path.exists():
        log("LOG", f"更新日志不存在: {log_path}", "ERROR")
        log("LOG", "发布中止：请先创建 updateLog.md 并补写当日条目", "ERROR")
        sys.exit(1)

    date_str = version_to_date(version)
    if not date_str:
        log("LOG", f"无法从版本号 {version} 解析日期（期望 3.YY.MMDDHH）", "ERROR")
        sys.exit(1)

    content = log_path.read_text(encoding="utf-8")
    # 查找 **YYYY/MM/DD** 标题
    date_pattern = re.compile(r"\*\*(\d{4}/\d{2}/\d{2})\*\*")
    matches = list(date_pattern.finditer(content))

    target_idx = None
    for i, m in enumerate(matches):
        if m.group(1) == date_str:
            target_idx = i
            break

    if target_idx is None:
        log("LOG", f"updateLog.md 缺少 {date_str} 当日条目（R2 fail-fast）", "ERROR")
        log("LOG", "发布中止：请先按 version-delivery-sync 规范补写当日条目再发版", "ERROR")
        sys.exit(1)

    start = matches[target_idx].start()
    end = matches[target_idx + 1].start() if target_idx + 1 < len(matches) else len(content)
    body = content[start:end].strip()
    log("LOG", f"提取到 {date_str} 的日志条目（{len(body)} 字符）")
    return body


def get_upload_name(pkg_type: str, apk_path: Path, version: str) -> str:
    """根据包类型生成上传到 Release 的文件名，避免 test/release 包同名冲突。

    test 包（debug 构建）→ legado_miss_app_debug_{version}.apk
    release 包（正式构建）→ legado_miss_app_{version}.apk
    coexist 包（共存构建）→ legado_legacy_app_{version}.apk
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
    if not confirm(f"版本号 {version}（updateLog 需已有当日条目），确认开始三包构建",
                   "build", args.confirm_stage, args.dry_run):
        log("STAGE1", "未确认构建，中止发布", "WARN")
        sys.exit(1)
    return version


# === Stage 2: 三包构建 ===

def stage2_build_three(version: str, dry_run: bool) -> Dict[str, Path]:
    """Stage2 三包构建（R1/R8）：subprocess 调 build-legado.bat，解析 [ARTIFACT] 行。

    - 显式版本第 3 参保证三包同版本
    - bat 自带 :STOP_DAEMON 清场与 libcronet.so 校验（每包后自动执行，不可跳过）
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


def check_libcronet(apk: Path) -> bool:
    """libcronet 存在校验（R4，zipfile 直查，不依赖 bat 内校验）。

    cronet-bundled Maven 迁移后 so 带版本号（如 libcronet.151.0.7922.47.so），
    故用 libcronet*.so 前缀匹配而非精确名。
    """
    try:
        with zipfile.ZipFile(apk) as z:
            return any(n.startswith("lib/arm64-v8a/libcronet") and n.endswith(".so")
                       for n in z.namelist())
    except zipfile.BadZipFile:
        return False


def run_tool(tool: Path, tool_args: List[str]) -> Tuple[bool, str]:
    """执行 SDK 工具，返回 (成功, 合并输出)"""
    try:
        proc = subprocess.run([str(tool)] + tool_args, capture_output=True,
                              text=True, errors="replace", timeout=300)
        return proc.returncode == 0, (proc.stdout or "") + (proc.stderr or "")
    except subprocess.TimeoutExpired:
        return False, "tool timeout (300s)"


def stage3_verify(config: dict, version: str, dry_run: bool) -> Tuple[Dict[str, Path], str]:
    """Stage3 校验强化（R2-R5）：致命项 fail-fast exit，建议项 WARN 清单"""
    # 致命项 1：三包齐全（重扫兜底，不信任 Stage2 [ARTIFACT] 解析）
    _, apks = scan_apk_files(config, version)
    missing = [k for k in EXPECTED_PACKAGES if k not in apks]

    if dry_run:
        # dry-run 允许无产物（bump 新版本尚未构建），仅模拟校验；
        # updateLog 校验只读无副作用，dry-run 也真实执行（L1 门禁预演）
        if missing:
            log("VERIFY", f"[dry-run] 产物缺失 {missing} —— 实际发布时将 fail-fast 拦截（模拟通过）")
        else:
            log("VERIFY", "[dry-run] 将执行: libcronet.so 三包检查 / apksigner 验签 / "
                          "aapt2 包名版本一致性 / updateLog 当日条目")
        log_path = PROJECT_ROOT / config["update_log_path"]
        body = read_update_log(log_path, version)
        return apks, body

    if missing:
        log("VERIFY", f"产物缺失（R3 fail-fast）: {missing} —— 不再仅 WARN", "ERROR")
        sys.exit(1)

    # 致命项 2：libcronet.so（R4，exit 1 不降级）
    for pkg, apk in apks.items():
        if not check_libcronet(apk):
            log("VERIFY", f"[{pkg}] libcronet.so 缺失: {apk.name}（m3u8 播放将失效，exit 1）", "ERROR")
            sys.exit(1)
    log("VERIFY", "libcronet.so 三包齐全")

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
            else:
                log("VERIFY", f"[{pkg}] 版本不一致: 期望 {version}，实际 {ver_name}", "ERROR")
                sys.exit(1)
        log("VERIFY", f"[{pkg}] 验签通过 包名={pkg_name} 版本={ver_name}")

    # 建议项（WARN 不阻断）：版本日期与今天偏差
    v_date = version_to_date(version)
    today = datetime.date.today().strftime("%Y/%m/%d")
    if v_date != today:
        log("VERIFY", f"版本号日期 {v_date} 与今天 {today} 不一致（重发旧版本？仅提示）", "WARN")

    # 致命项 5：updateLog 当日条目（R2 fail-fast）
    log_path = PROJECT_ROOT / config["update_log_path"]
    body = read_update_log(log_path, version)
    return apks, body


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
                                  errors="replace", env=env, timeout=1800)
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
    """GitHub 发布流程（gh CLI 替代 requests，规避 uploads.github.com SSL 与 51MB+ 双坑）"""
    results: Dict[str, bool] = {}
    g = config["github"]
    repo = f"{g['owner']}/{g['repo']}"
    log("GITHUB", f"repo={repo} token={hide_token(g['token'])} (gh CLI)")

    # 包名禁令（R7）：test 包仅本地归档，不上 Release
    upload_apks = {k: v for k, v in apks.items() if k != "test"}

    if dry_run:
        # dry-run 允许无产物（bump 新版本尚未构建），仅模拟
        log("GITHUB", f"[dry-run] 将创建/复用 Release tag={version}（body=updateLog 当日条目）")
        for pkg_type, apk in upload_apks.items():
            log("GITHUB", f"[dry-run] 将上传 {pkg_type}: {get_upload_name(pkg_type, apk, version)}")
            results[pkg_type] = True
        return results

    if not upload_apks:
        log("GITHUB", "无可上传产物（test 包不上 Release）", "ERROR")
        return {k: False for k in apks}

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
        log("GITHUB", f"  {pkg_type}: 上传 {upload_name}...")
        gh_run(["release", "upload", version, str(apk_path), "--repo", repo], env, "GITHUB")
        results[pkg_type] = True
        log("GITHUB", f"  {pkg_type}: 上传成功")

    results.setdefault("test", True)  # test 归档即成功
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
                          cwd=str(PROJECT_ROOT), errors="replace")
    if proc.returncode != 0:
        if "already exists" in (proc.stderr or ""):
            log("TAG", f"tag {tag} 已存在，复用（幂等）")
        else:
            log("TAG", f"创建 tag 失败: {proc.stderr}", "ERROR")
            sys.exit(1)
    proc = subprocess.run(["git", "push", "origin", tag], capture_output=True, text=True,
                          cwd=str(PROJECT_ROOT), errors="replace")
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

    # Stage2 三包构建
    stage2_build_three(version, args.dry_run)

    # Stage3 校验强化（含 updateLog 当日条目 fail-fast）
    apks, body = stage3_verify(config, version, args.dry_run)
    log("MAIN", f"Release body 预览（前 200 字符）:\n{body[:200]}...")

    # L2 真机门禁（不可跳过，无 flag 旁路）
    check_l2_evidence(args)

    # Stage4 发布（test 包已被两层排除：github 层 + 此处 gitee 入参过滤）
    all_results: Dict[str, Dict[str, bool]] = {}
    exit_code = 0
    release_apks = {k: v for k, v in apks.items() if k != "test"}

    if args.platform in ("gitee", "both"):
        try:
            all_results["gitee"] = gitee_publish(config, version, body, release_apks, args.dry_run)
        except Exception as e:
            log("GITEE", f"发布异常: {e}", "ERROR")
            all_results["gitee"] = {k: False for k in release_apks}
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
