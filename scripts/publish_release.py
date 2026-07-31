#!/usr/bin/env python3
"""
APK 发布脚本：将本地构建的 APK 发布到 Gitee 和 GitHub Release。

用法:
    ai_tests\\venv\\Scripts\\python.exe scripts\\publish_release.py [--version <ver>] [--dry-run] [--platform gitee|github|both]

配置文件:
    scripts/publish_config.json（从 publish_config.example.json 复制并填入 token）
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path
from typing import Optional, Dict, List, Tuple

import requests
import urllib3

SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
DEFAULT_CONFIG = SCRIPT_DIR / "publish_config.json"

# 全局 Session：Windows 环境 uploads.github.com SSL 证书链验证失败
# （api.github.com 正常，仅 uploads.github.com 异常，疑似网络代理拦截）
# 临时方案：禁用 SSL 验证 + 过滤警告，保证发布流程可用
# TODO: 后续排查网络环境（代理/防火墙）根因，恢复严格 SSL 验证
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
SESSION = requests.Session()
SESSION.verify = False


def log(stage: str, msg: str, level: str = "INFO"):
    """统一日志输出"""
    print(f"[{level}] [{stage}] {msg}")


def hide_token(token: str) -> str:
    """token 脱敏"""
    if not token or len(token) < 8:
        return "***"
    return token[:4] + "***" + token[-4:]


def parse_args():
    parser = argparse.ArgumentParser(description="APK 发布到 Gitee/GitHub Release")
    parser.add_argument("--version", help="指定版本号（如 3.26.072912），缺省时自动取最大")
    parser.add_argument("--dry-run", action="store_true", help="只预览不实际调用 API")
    parser.add_argument("--platform", choices=["gitee", "github", "both"], default="both", help="发布平台（默认 both）")
    parser.add_argument("--config", help=f"配置文件路径（默认 {DEFAULT_CONFIG}）")
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

    # 检查是否三包齐全
    missing = set(apk_dirs.keys()) - set(result.keys())
    if missing:
        log("SCAN", f"版本 {version} 缺少包: {missing}", "WARN")
        log("SCAN", f"已有包: {list(result.keys())}")

    for pkg_type, apk_path in result.items():
        size_mb = apk_path.stat().st_size / (1024 * 1024)
        log("SCAN", f"  {pkg_type}: {apk_path.name} ({size_mb:.1f}MB)")

    return version, result


def read_update_log(log_path: Path, version: str) -> str:
    """读取更新日志，提取对应日期的条目"""
    if not log_path.exists():
        log("LOG", f"更新日志不存在: {log_path}", "WARN")
        return f"自动发布 {version}"

    date_str = version_to_date(version)
    if not date_str:
        log("LOG", f"无法从版本号 {version} 解析日期", "WARN")
        return f"自动发布 {version}"

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
        log("LOG", f"未找到 {date_str} 的日志条目，使用默认 body", "WARN")
        return f"自动发布 {version}"

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
    """重试机制：网络错误/5xx 重试，4xx 鉴权错误立即终止"""
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


# === Gitee API ===

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


# === GitHub API ===

def github_get_release_by_tag(config: dict, tag: str) -> Optional[dict]:
    """查询 GitHub Release by tag"""
    g = config["github"]
    url = f"{g['api_base']}/repos/{g['owner']}/{g['repo']}/releases/tags/{tag}"
    headers = {"Authorization": f"token {g['token']}", "Accept": "application/vnd.github+json"}
    resp = SESSION.get(url, headers=headers, timeout=30)
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    return resp.json()


def github_create_release(config: dict, version: str, body: str) -> Optional[int]:
    """创建 GitHub Release，返回 release_id"""
    g = config["github"]
    url = f"{g['api_base']}/repos/{g['owner']}/{g['repo']}/releases"
    headers = {"Authorization": f"token {g['token']}", "Accept": "application/vnd.github+json"}
    payload = {
        "tag_name": version,
        "name": version,
        "body": body,
        "target_commitish": g.get("target_commitish", "master"),
        "draft": False,
        "prerelease": False,
    }
    resp = SESSION.post(url, headers=headers, json=payload, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    return data.get("id")


def github_list_assets(config: dict, release_id: int) -> List[str]:
    """列出 GitHub Release 已有 asset 名称"""
    g = config["github"]
    url = f"{g['api_base']}/repos/{g['owner']}/{g['repo']}/releases/{release_id}"
    headers = {"Authorization": f"token {g['token']}", "Accept": "application/vnd.github+json"}
    resp = SESSION.get(url, headers=headers, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    assets = data.get("assets", [])
    return [a.get("name", "") for a in assets]


def github_upload_asset(config: dict, release_id: int, apk_path: Path, upload_name: str) -> bool:
    """上传 GitHub Asset"""
    g = config["github"]
    url = f"{g['upload_base']}/repos/{g['owner']}/{g['repo']}/releases/{release_id}/assets"
    headers = {
        "Authorization": f"token {g['token']}",
        "Content-Type": "application/octet-stream",
    }
    params = {"name": upload_name}
    with open(apk_path, "rb") as f:
        resp = SESSION.post(url, headers=headers, params=params, data=f, timeout=config["retry"]["timeout"])
    resp.raise_for_status()
    return True


def github_publish(config: dict, version: str, body: str, apks: Dict[str, Path], dry_run: bool) -> Dict[str, bool]:
    """GitHub 发布流程"""
    results: Dict[str, bool] = {}
    g = config["github"]
    log("GITHUB", f"owner={g['owner']}/{g['repo']} token={hide_token(g['token'])}")

    if dry_run:
        log("GITHUB", "[dry-run] 将创建 Release tag=" + version)
        for pkg_type, apk in apks.items():
            log("GITHUB", f"[dry-run] 将上传 {pkg_type}: {apk.name}")
            results[pkg_type] = True
        return results

    # 查询 Release 是否已存在
    existing = retry_on_failure(github_get_release_by_tag, 3, 2, "GITHUB", config, version)
    if existing is not None:
        release_id = existing.get("id")
        log("GITHUB", f"Release 已存在（id={release_id}），复用")
        existing_assets = set(github_list_assets(config, release_id))
    else:
        release_id = retry_on_failure(github_create_release, 3, 2, "GITHUB", config, version, body)
        if release_id is None:
            log("GITHUB", "创建 Release 失败", "ERROR")
            for pkg_type in apks:
                results[pkg_type] = False
            return results
        log("GITHUB", f"创建 Release 成功（id={release_id}）")
        existing_assets = set()

    # 上传 APK
    for pkg_type, apk_path in apks.items():
        upload_name = get_upload_name(pkg_type, apk_path, version)
        if upload_name in existing_assets:
            log("GITHUB", f"  {pkg_type}: {upload_name} 已存在，跳过")
            results[pkg_type] = True
            continue
        log("GITHUB", f"  {pkg_type}: 上传 {upload_name}...")
        ok = retry_on_failure(github_upload_asset, 3, 2, "GITHUB", config, release_id, apk_path, upload_name)
        results[pkg_type] = ok is not None
        if ok:
            log("GITHUB", f"  {pkg_type}: 上传成功")
        else:
            log("GITHUB", f"  {pkg_type}: 上传失败", "ERROR")

    return results


def main():
    args = parse_args()
    config_path = Path(args.config) if args.config else DEFAULT_CONFIG

    log("MAIN", "=" * 60)
    log("MAIN", "APK 发布脚本启动")
    log("MAIN", f"  dry_run={args.dry_run} platform={args.platform}")

    # 1. 读取配置
    config = read_config(config_path, args.dry_run, args.platform)

    # 2. 扫描 APK
    version, apks = scan_apk_files(config, args.version)
    if not apks:
        log("MAIN", "没有可发布的 APK", "ERROR")
        sys.exit(1)

    # 3. 读取更新日志
    log_path = PROJECT_ROOT / config["update_log_path"]
    body = read_update_log(log_path, version)
    log("MAIN", f"Release body 预览（前 200 字符）:\n{body[:200]}...")

    # 4. 发布
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
            all_results["github"] = github_publish(config, version, body, apks, args.dry_run)
        except Exception as e:
            log("GITHUB", f"发布异常: {e}", "ERROR")
            all_results["github"] = {k: False for k in apks}
            exit_code = 1

    # 5. 汇总输出
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

    if not args.dry_run:
        if total_success < total_count:
            exit_code = 1
        elif total_success == 0:
            exit_code = 1

    log("MAIN", f"退出码: {exit_code}")
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
