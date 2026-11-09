#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build_and_smoke.py —— 交付一条命令（出包 → 溯源清单 → 安装启动冒烟 → 截图）

为什么是「一条命令」
    G-53 门禁要求「最新测试包 + 冒烟证据」，但把 5 个步骤留给 AI 手工串 = 必然漏（本项目已多次实证）。
    本脚本把整套动作收敛为唯一入口：`build_and_smoke.py`（默认测试包）。
    出包一律走 build-legado.bat（AGENTS.md 打包铁律），脚本只是调度器。

流程
    1) build-legado.bat（--skip-build 可跳过，直接对现有产物补冒烟）
    2) 取 output/apk/<variant> 最新 APK → 写 <apk>.build.json（delivery_evidence.write_manifest）
    3) adb 连接模拟器 → install -r → logcat -c → am start
    4) 抓 logcat：FATAL EXCEPTION 计数 + 前台 Activity
    5) 截图 → output/delivery/smoke-<variant>-<version>.png
    6) 写 output/delivery/smoke-<variant>.json（G-53 读取）

--fresh / --skip-install 的定位
    **默认覆盖安装（`install -r`）不卸载**：书源 / 书架 / 订阅源 / 剧本都是用户数据，自动化无权清空
    （2026-11-05 用户铁律：默认 uninstall 曾把真机上已配置的数据全部抹掉）。需要干净环境才显式 `--fresh`。
    部分 ROM（ColorOS 实测）对 adb 覆盖安装强制弹出「安全验证」拼图，自动化无法稳定通过；
    此时可人工完成安装后再用 --skip-install：脚本会用 `dumpsys package` **校验设备已装版本
    与本 APK 版本号完全一致**（不一致直接 FAIL），随后照常执行启动冒烟/截图，C4/C5 判据不弱化。

--defer 的定位
    多批次开发中途不值得每提交一次就等 20 分钟编译时，可用
    `build_and_smoke.py --defer "<理由>"` 登记「暂缓」，写入
    `.trae/.hook-state/delivery_deferred.json`（含 commit + 理由，可审计）。
    Stop 卡点见到与当前 HEAD 匹配的暂缓登记才放行；HEAD 一前进即失效 ⇒ 不能一次豁免混到底。

退出码
    0 = 冒烟通过（或暂缓已登记）
    1 = 出包/安装/启动失败，或启动期出现 FATAL
    2 = 环境错误（无 APK / 无设备 / adb 缺失）
"""

import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import delivery_evidence as de  # noqa: E402

BUILD_BAT = de.ROOT / "build-legado.bat"
STATE_DIR = de.ROOT / ".trae" / ".hook-state"
DEFER_FILE = STATE_DIR / "delivery_deferred.json"
DEFAULT_DEVICE = os.environ.get("MEMU_ADB_HOST", "127.0.0.1:21503")
MAIN_ACTIVITY = "io.legado.app.ui.welcome.WelcomeActivity"
VERSION_RE = re.compile(r"(\d+\.\d+\.\d{5,6})")
ADB_CANDIDATES = (
    os.environ.get("ADB_PATH"),
    r"C:\Android\Sdk\platform-tools\adb.exe",
    r"D:\Program Files\Microvirt\MEmu\adb.exe",
)


def find_adb():
    for cand in ADB_CANDIDATES:
        if cand and Path(cand).is_file():
            return cand
    found = shutil.which("adb")
    if found:
        return found
    return None


def run(cmd, timeout=600, binary=False):
    proc = subprocess.run(cmd, capture_output=True, text=not binary, timeout=timeout,
                          encoding=None if binary else "utf-8", errors=None if binary else "replace")
    return proc


def device_abi(adb):
    """按设备真实 ABI 出包（S1 单 ABI 分包）：x86_64 模拟器出 x86_64 包，真机出 arm64 包。"""
    proc = run([adb, "-s", DEFAULT_DEVICE, "shell", "getprop", "ro.product.cpu.abi"], timeout=60)
    abi = (proc.stdout or "").strip().lower()
    if "x86" in abi:
        return "x86_64"
    if "v7a" in abi or "armeabi" in abi:
        return "arm32"
    return "arm64"


def step_build(variant, abi="arm64"):
    task = "release" if variant == "release" else "debug"
    log = de.ROOT / ".temp" / ("build_and_smoke_%s.log" % datetime.datetime.now().strftime("%H%M%S"))
    log.parent.mkdir(parents=True, exist_ok=True)
    print("[1/5] 出包：build-legado.bat %s（ABI=%s，日志 %s）"
          % (task, abi, log.relative_to(de.ROOT)))
    env = dict(os.environ)
    env["LEGADO_ABI"] = abi
    with open(log, "w", encoding="utf-8", errors="replace") as fh:
        proc = subprocess.run(
            [str(BUILD_BAT), task], cwd=str(de.ROOT), stdin=subprocess.DEVNULL, env=env,
            stdout=fh, stderr=subprocess.STDOUT, text=True, timeout=3600)
    if proc.returncode != 0:
        print("[FAIL] 出包失败（exit=%d），见日志：%s" % (proc.returncode, log))
        return False
    print("[OK] 出包成功")
    return True


def step_device(adb):
    print("[2/5] 设备：%s" % DEFAULT_DEVICE)
    devices = run([adb, "devices"], timeout=60).stdout or ""
    if DEFAULT_DEVICE not in devices:
        run([adb, "connect", DEFAULT_DEVICE], timeout=60)
        devices = run([adb, "devices"], timeout=60).stdout or ""
    if DEFAULT_DEVICE not in devices:
        print("[FAIL] 设备未连接：%s\n%s" % (DEFAULT_DEVICE, devices.strip()))
        return False
    print("[OK] 设备在线")
    return True


def data_fingerprint(adb, package):
    """用户数据指纹：`databases/legado.db*` 是否存在 + shared_prefs 文件数。

    覆盖安装不会改动 /data/data/<pkg>，因此安装前后指纹必须一致。出现「安装前有库、安装后没了」
    即证明用户数据被清空（2026-11-05 用户铁律的直接门禁，不再依赖人工自觉）。
    release 包不可 run-as ⇒ 返回 None（跳过比对，不阻断，正式包由人工确认）。
    """
    script = ("ls -l databases 2>/dev/null; echo __PREFS__; ls shared_prefs 2>/dev/null")
    try:
        proc = run([adb, "-s", DEFAULT_DEVICE, "shell", "run-as", package, "sh", "-c", script], timeout=120)
    except subprocess.TimeoutExpired:
        return None
    out = proc.stdout or ""
    if proc.returncode != 0 or "__PREFS__" not in out:
        return None
    db_part, _, pref_part = out.partition("__PREFS__")
    dbs = {}
    for ln in db_part.splitlines():
        cols = ln.split()
        if len(cols) >= 5 and cols[-1].startswith("legado.db"):
            dbs[cols[-1]] = cols[4]  # 文件字节数
    prefs = len([x for x in pref_part.splitlines() if x.strip()])
    return {"db": dbs, "prefs": prefs}


def describe_fingerprint(fp):
    if fp is None:
        return "（不可读取：非 debug 包或应用未安装）"
    db = ",".join("%s=%sB" % (k, v) for k, v in sorted(fp["db"].items())) or "无"
    return "db[%s] prefs=%d" % (db, fp["prefs"])


def first_install_time(adb, package):
    """firstInstallTime：覆盖安装不改它；一旦变化就证明发生了「卸载+重装」⇒ 用户数据必然已清空。

    这条判据不依赖 run-as（本仓库的 debug 包 debuggable=false，无法读 /data/data），
    是「不卸载」最可靠的可审计证据。
    """
    try:
        out = run([adb, "-s", DEFAULT_DEVICE, "shell", "dumpsys", "package", package], timeout=120).stdout or ""
    except subprocess.TimeoutExpired:
        return None
    for ln in out.splitlines():
        if "firstInstallTime=" in ln:
            return ln.split("=", 1)[1].strip()
    return None


def step_install(adb, apk, package, fresh=False):
    """默认覆盖安装（保留书源/书架/订阅源等用户数据）；仅 --fresh 才卸载。

    2026-11-05 用户铁律：交付冒烟**禁止默认卸载**——曾因默认 uninstall 把用户真机上
    已配置的书源/书架/订阅源全部清空，导致用户不敢把主力机交给自动化。干净环境一律显式 `--fresh`。
    """
    print("[3/5] 安装：%s（%s）" % (apk.name, "全新安装(显式 --fresh)" if fresh else "覆盖安装（保留用户数据）"))
    before = None
    before_first = None
    if fresh:
        run([adb, "-s", DEFAULT_DEVICE, "uninstall", package], timeout=120)  # 首次安装本无此包，忽略结果
    else:
        before = data_fingerprint(adb, package)
        before_first = first_install_time(adb, package)
        print("      安装前：firstInstallTime=%s 用户数据=%s"
              % (before_first or "（未安装）", describe_fingerprint(before)))
    try:
        # 部分 ROM（ColorOS）会在覆盖安装时弹「安全验证」拼图，人工完成需要时间 ⇒ 超时放宽到 15 分钟
        proc = run([adb, "-s", DEFAULT_DEVICE, "install", "-r", str(apk)], timeout=900)
    except subprocess.TimeoutExpired:
        print("[FAIL] 安装超时：设备可能在等待「安全验证/继续安装」确认（请在手机上完成后再重试）")
        return False
    out = ((proc.stdout or "") + (proc.stderr or "")).strip()
    if proc.returncode != 0 or "Success" not in out:
        print("[FAIL] 安装失败：%s" % out[:400])
        return False
    print("[OK] 安装成功")
    if not fresh:
        after_first = first_install_time(adb, package)
        print("      安装后：firstInstallTime=%s" % (after_first or "（取不到）"))
        if before_first and after_first and before_first != after_first:
            print("[FAIL] firstInstallTime 变化（%s → %s）⇒ 发生了「卸载+重装」，用户数据已被清空"
                  % (before_first, after_first))
            print("       如为干净环境冒烟，请显式传 --fresh，不要静默清数据。")
            return False
        if before is not None:
            after = data_fingerprint(adb, package)
            print("      安装后用户数据：%s" % describe_fingerprint(after))
            lost_db = bool(before["db"]) and (after is None or not after["db"])
            lost_prefs = after is not None and after["prefs"] < before["prefs"]
            if lost_db or lost_prefs:
                print("[FAIL] 用户数据丢失！安装前后指纹不一致（书源/书架/订阅源可能已被清空）")
                print("       如为干净环境冒烟，请显式传 --fresh，不要静默清数据。")
                return False
    return True


def step_verify_installed(adb, apk, package):
    """--skip-install：不重装，但必须验证设备上已装版本与本 APK 完全一致（否则判 FAIL）。"""
    m = VERSION_RE.search(apk.name)
    want = m.group(1) if m else ""
    out = run([adb, "-s", DEFAULT_DEVICE, "shell", "dumpsys", "package", package], timeout=120).stdout or ""
    got = ""
    for ln in out.splitlines():
        if "versionName=" in ln:
            got = ln.strip().split("=", 1)[1].strip()
            break
    ok = bool(want) and want in got
    print("[%s] 已装版本校验：期望含 %s，实际 %s"
          % ("OK" if ok else "FAIL", want or "（无法从文件名解析）", got or "（未安装）"))
    return ok


def step_smoke(adb, package):
    print("[4/5] 启动并观察 10s（FATAL 扫描）")
    run([adb, "-s", DEFAULT_DEVICE, "shell", "logcat", "-c"], timeout=60)
    run([adb, "-s", DEFAULT_DEVICE, "shell", "am", "force-stop", package], timeout=60)
    start = run([adb, "-s", DEFAULT_DEVICE, "shell", "am", "start", "-W", "-n",
                 "%s/%s" % (package, MAIN_ACTIVITY)], timeout=120)
    start_out = (start.stdout or "") + (start.stderr or "")
    if start.returncode != 0 or "Error" in start_out or "does not exist" in start_out:
        print("[FAIL] 启动命令失败：%s" % start_out.strip()[:300])
        return None
    import time
    time.sleep(10)
    log = run([adb, "-s", DEFAULT_DEVICE, "logcat", "-d"], timeout=120).stdout or ""
    fatal_lines = [ln for ln in log.splitlines() if "FATAL EXCEPTION" in ln]
    # 崩在子线程也可能只留 "AndroidRuntime: FATAL"；按关键词族计数，宁严勿松
    fatal_count = len(fatal_lines) + sum(
        1 for ln in log.splitlines() if "AndroidRuntime" in ln and " E " in ln and "FATAL" in ln)
    resumed = ""
    act = run([adb, "-s", DEFAULT_DEVICE, "shell", "dumpsys", "activity", "activities"], timeout=120).stdout or ""
    # Android 17（K100 实测 2026-11-09）只输出 topResumedActivity / ResumedActivity，
    # 旧字段 mResumedActivity 已消失 ⇒ 三种写法都要认，否则「启动成功」会被误判成未取到前台
    for ln in act.splitlines():
        if "mResumedActivity" in ln or "topResumedActivity" in ln or "ResumedActivity:" in ln:
            resumed = ln.strip()
            break
    launched = bool(resumed and package in resumed)
    print("[%s] 冒烟：FATAL=%d，前台 Activity=%s" % ("OK" if launched and fatal_count == 0 else "FAIL",
                                                   fatal_count, resumed[:120] or "（未取到）"))
    return {"fatal_count": fatal_count, "resumed_activity": resumed, "launched": launched}


def step_shot(adb, variant, version):
    print("[5/5] 截图")
    shot = de.smoke_shot_path(variant, version)
    shot.parent.mkdir(parents=True, exist_ok=True)
    proc = run([adb, "-s", DEFAULT_DEVICE, "exec-out", "screencap", "-p"], timeout=120, binary=True)
    data = proc.stdout or b""
    if len(data) <= 1000:
        print("[FAIL] 截图失败（%d 字节）" % len(data))
        return None
    shot.write_bytes(data)
    print("[OK] 截图：%s（%d 字节）" % (shot.name, len(data)))
    return shot


def write_defer(reason):
    if len(reason.strip()) < 8:
        print("[FAIL] --defer 必须给出至少 8 个字的理由（可审计豁免）")
        return 1
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "commit": de.head_sha(),
        "reason": reason.strip(),
        "at": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    DEFER_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print("[OK] 已登记暂缓出包：commit=%s 理由=%s" % (payload["commit"][:8], payload["reason"]))
    print("     说明：HEAD 前进即失效；交付/发版前必须补齐 build_and_smoke.py。")
    return 0


def main():
    ap = argparse.ArgumentParser(description="交付一条命令：出包 + 冒烟 + 证据")
    ap.add_argument("--variant", default="test", choices=["test", "release"])
    ap.add_argument("--skip-build", action="store_true", help="跳过出包，直接对现有最新 APK 冒烟")
    ap.add_argument("--fresh", action="store_true",
                    help="显式全新安装（先卸载）；会清空书源/书架/订阅源等用户数据，仅干净环境冒烟时用")
    ap.add_argument("--keep-data", action="store_true",
                    help="[兼容旧参数] 覆盖安装保留数据；现已是默认行为，本参数不再改变任何结果")
    ap.add_argument("--skip-install", action="store_true",
                    help="跳过安装，仅校验设备上已装版本与本 APK 一致后直接冒烟（ColorOS 验证页无法自动化的兜底）")
    ap.add_argument("--defer", default=None, help="登记「暂缓出包」豁免（需理由）")
    args = ap.parse_args()

    if args.defer is not None:
        return write_defer(args.defer)

    variant = args.variant
    package = de.PACKAGE_BY_VARIANT[variant]
    # S1 单 ABI 分包：debug 产物按 ABI 落 output/apk/test（arm64）/ test-x86_64 / test-arm32
    apk_subdir = de.ABI_SUBDIR_BY_TARGET.get(variant, "test") if variant != "release" else "release"
    apk_dir = de.ROOT / "output" / "apk" / apk_subdir

    adb = find_adb()
    if not adb:
        print("[FAIL] 找不到 adb（候选：C:\\Android\\Sdk\\platform-tools\\adb.exe / MEmu adb）")
        return 2

    if not args.skip_build:
        if not BUILD_BAT.is_file():
            print("[FAIL] 找不到 build-legado.bat")
            return 2
        if not step_device(adb):
            return 2
        abi = device_abi(adb)
        if variant == "release":
            abi = "both"
        apk_subdir = "release" if variant == "release" else de.ABI_SUBDIR_BY_TARGET.get(abi, "test")
        apk_dir = de.ROOT / "output" / "apk" / apk_subdir
        if not step_build(variant, abi):
            return 1
    else:
        # --skip-build：仍按设备真实 ABI 选产物目录，否则会拿 arm64 包去比 x86 设备（版本校验必挂）
        abi = device_abi(adb)
        subdir = "release" if variant == "release" else de.ABI_SUBDIR_BY_TARGET.get(abi, "test")
        apk_dir = de.ROOT / "output" / "apk" / subdir

    apk = de.newest_apk(apk_dir)
    if not apk:
        print("[FAIL] 未找到 APK：%s" % apk_dir)
        return 2
    manifest = de.write_manifest(apk, variant)
    print("[OK] 溯源清单：%s（commit=%s signature=%s）"
          % (de.apk_sidecar(apk).name, manifest["commit"][:8], manifest["signature"]))

    if not step_device(adb):
        return 2
    if args.skip_install:
        if not step_verify_installed(adb, apk, package):
            return 1
    elif not step_install(adb, apk, package, fresh=args.fresh):
        return 1

    smoke = step_smoke(adb, package)
    if not smoke:
        return 1
    m = VERSION_RE.search(apk.name)
    version = m.group(1) if m else "unknown"
    shot = step_shot(adb, variant, version)

    record = {
        "schema": 1,
        "variant": variant,
        "commit": manifest["commit"],
        "signature": manifest["signature"],
        "apk_name": apk.name,
        "apk_sha256": manifest["apk_sha256"],
        "package": package,
        "device": DEFAULT_DEVICE,
        "installed": True,
        "launched": smoke["launched"],
        "resumed_activity": smoke["resumed_activity"],
        "fatal_count": smoke["fatal_count"],
        "screenshot": shot.name if shot else "",
        "checked_at": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    de.EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    de.smoke_path(variant).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    (de.EVIDENCE_DIR / ("smoke-%s-%s.json" % (variant, version))).write_text(
        json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")

    ok = record["launched"] and record["fatal_count"] == 0 and bool(shot)
    print("=" * 62)
    print("%s 冒烟证据：%s" % ("[OK] 交付" if ok else "[FAIL] 交付未通过",
                              de.smoke_path(variant).name))
    print("   APK=%s  commit=%s  FATAL=%d  截图=%s"
          % (apk.name, manifest["commit"][:8], record["fatal_count"], record["screenshot"] or "无"))
    print("   下一步：ai_tests\\venv\\Scripts\\python.exe ai_tests/scripts/audit_delivery_smoke.py")
    print("=" * 62)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
