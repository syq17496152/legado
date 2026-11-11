#!/usr/bin/env python3
"""版本号策略校验（stable-release-split-without-tts，2026-11-11）

与 app/build.gradle 的 buildVersionCode 同构：
    versionCode = max(20000 + yyMMddHH, 20000 + gitCommits)

目的（历史教训）：
1. 已发布 3.26.102921 = 26112921；实验分支测试包按 20000 + 26110110 = 26130110 装进过手机。
   10000 基数只能得到 26121121，低于 26130110 ⇒ 覆盖安装必报 INSTALL_FAILED_VERSION_DOWNGRADE。
   恢复 20000 基数后 26131121 同时高于两者，两条升级路径都通。
2. 发布产物名必须能被 publish_release.py 的 version_pattern（3.\\d{2}.\\d{6}）解析；
   test 包上传时由 get_upload_name 重命名为 legado_miss_app_debug_{v}.apk，
   否则 AppReleaseInfo.resolveAppVariant 会把「含 ABI 后缀、不含 debug」的产物误判为 OFFICIAL。

用法：
    python scripts/version_code_policy.py --release-time 26.111121 --commits 900 \
        --baseline 26112921 --baseline 26130110
退出码：0=通过，1=失败
"""
import argparse
import re
import sys

BASE = 20000
VERSION_PATTERN = r"3\.\d{2}\.\d{6}"


def build_version_code(release_time: str, commits: int) -> int:
    """与 build.gradle 同构：时间项与提交数项取较大值，基数 20000。"""
    return max(BASE + int(release_time.replace(".", "")), BASE + commits)


def extract_version(name: str, pattern: str = VERSION_PATTERN):
    """与 publish_release.py extract_version 同构（不锚定结尾，允许 ABI 后缀）。"""
    m = re.search(pattern, name)
    return m.group(0) if m else None


def resolve_variant(name: str) -> str:
    """与 AppReleaseInfo.resolveAppVariant 同构（不含 preRelease 分支）。"""
    if "releaseA" in name:
        return "BETA_RELEASEA"
    if "releaseS" in name:
        return "BETA_RELEASES"
    if "debug" in name:
        return "BETA_RELEASE"
    return "OFFICIAL"


def self_test() -> None:
    # 20000 基数：本次发布版本
    assert build_version_code("26.111121", 900) == 26131121
    # 必须同时高于「已发布」与「已装实验包」
    assert 26131121 > 26112921, "必须高于已发布 3.26.102921"
    assert 26131121 > 26130110, "必须高于已装实验包 26130110"
    # 10000 基数会低于已装实验包（回归防护，禁止改回）
    assert max(10000 + 26111121, 10000 + 900) < 26130110
    # 产物名解析：允许 ABI 后缀
    assert extract_version("legado_miss_app_3.26.111121_arm64.apk") == "3.26.111121"
    assert extract_version("legado_miss_app_3.26.111121.apk") == "3.26.111121"
    # 变体判定：走 publish 脚本重命名后正确；手动上传带 ABI 后缀会被判 OFFICIAL（风险提示）
    assert resolve_variant("legado_miss_app_debug_3.26.111121.apk") == "BETA_RELEASE"
    assert resolve_variant("legado_miss_app_3.26.111121.apk") == "OFFICIAL"
    assert resolve_variant("legado_miss_app_3.26.111121_arm64.apk") == "OFFICIAL"
    print("[PASS] self-test（9 条断言）")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="只跑内置断言")
    ap.add_argument("--release-time", help="yy.MMddHH，如 26.111121")
    ap.add_argument("--commits", type=int, default=0)
    ap.add_argument("--baseline", type=int, action="append", default=[],
                    help="基线 versionCode（已发布版本 / 已装实验包），可重复")
    ap.add_argument("--apk-name", action="append", default=[], help="待校验产物文件名，可重复")
    args = ap.parse_args()

    self_test()
    if args.self_test:
        return 0

    failed = []
    if args.release_time:
        vc = build_version_code(args.release_time, args.commits)
        print(f"[INFO] versionCode = {vc}（releaseTime={args.release_time}, commits={args.commits}）")
        for b in args.baseline:
            if vc <= b:
                failed.append(f"versionCode {vc} <= 基线 {b}（会触发 INSTALL_FAILED_VERSION_DOWNGRADE）")
            else:
                print(f"[PASS] {vc} > 基线 {b}")

    for name in args.apk_name:
        ver = extract_version(name)
        variant = resolve_variant(name)
        if not ver:
            failed.append(f"产物名无法解析版本号：{name}")
        elif variant == "OFFICIAL" and name.startswith("legado_miss_app_") and not name.startswith("legado_miss_app_debug_"):
            # 允许无 ABI 后缀的正式包；带 ABI 后缀的 test 产物必须走 publish 重命名（仅告警，不阻断）
            if re.search(r"_(arm64|x86_64|arm32)\.apk$", name):
                print(f"[WARN] test 产物带 ABI 后缀，手动上传会被判 OFFICIAL（必须走 publish_release.py 重命名）：{name}")
            else:
                print(f"[PASS] 正式包命名：{name} -> {ver}")
        else:
            print(f"[PASS] 产物名：{name} -> {ver} / {variant}")

    if failed:
        for f in failed:
            print(f"[FAIL] {f}")
        return 1
    print("[OK] 版本号策略校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
