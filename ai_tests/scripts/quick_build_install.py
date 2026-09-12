#!/usr/bin/env python3
r"""quick_build_install.py — 快速编译+安装+L1验证

固定测试流程步骤1：编译APK → 启动MEmu → 安装APK → L1验证

用法：
    ai_tests\venv\Scripts\python.exe ai_tests/scripts/quick_build_install.py

退出码：
    0 = 全部通过
    1 = 部分失败
    2 = 致命错误
"""
import subprocess
import sys
import time
import os
from pathlib import Path

# 添加项目根目录到 path 以 import config
PROJECT_ROOT = Path(__file__).parent.parent.parent
sys.path.insert(0, str(Path(__file__).parent.parent))
from config import (
    ADB_PATH, MEMUC_PATH, MEMU_INSTANCE_ID, MEMU_ADB_HOST,
    PACKAGE, MAIN_ACTIVITY, APK_GLOB_DIR
)


def run_cmd(cmd, cwd=None, timeout=300, check=False):
    """执行命令并返回结果"""
    print(f"  >>> {cmd}")
    result = subprocess.run(
        cmd, shell=True, cwd=cwd, capture_output=True, text=True, timeout=timeout,
        encoding='gbk', errors='replace'
    )
    if result.returncode != 0:
        print(f"  !!! 退出码 {result.returncode}")
        if result.stderr:
            print(f"  !!! stderr: {result.stderr[:500]}")
    return result


def cleanup_daemons():
    """打包后清理 Gradle/Kotlin daemon，避免频繁打包打爆内存"""
    print("\n=== 清理构建 daemon ===")
    bat = PROJECT_ROOT / "stop-daemons.bat"
    if bat.exists():
        # 复用根目录 stop-daemons.bat（gradlew --stop + 强杀本项目残留 Kotlin daemon）
        result = run_cmd(f'call "{bat}"', cwd=str(PROJECT_ROOT), timeout=180)
    else:
        # 兜底：只停 Gradle daemon
        result = run_cmd(".\\gradlew.bat --stop", cwd=str(PROJECT_ROOT), timeout=180)
    return result.returncode == 0


def step1_build():
    """步骤1: 编译APK"""
    print("\n=== 步骤1: 编译APK ===")
    # timeout 600→1800（app-update-variant-fix 沉淀）：daemon 清场后冷编译实测 15-18 分钟，
    # 600s 会在编译中途 TimeoutExpired 杀进程（compile 阶段无输出可判，误报编译失败）
    result = run_cmd(".\\gradlew.bat assembleAppDebug", cwd=str(PROJECT_ROOT), timeout=1800)
    # 2026-09-03 local-build-speedup（R8）：编译成功默认保留 daemon 复用增量快照
    # （idletimeout=600000 空闲 10min 自退兜底）；仅编译失败时清场，防止异常状态残留
    if result.returncode != 0:
        cleanup_daemons()
        print("❌ 编译失败")
        return None

    # 检查 BUILD SUCCESSFUL
    if "BUILD SUCCESSFUL" not in result.stdout:
        print("❌ 未找到 BUILD SUCCESSFUL")
        return None

    # 查找最新APK
    apk_dir = APK_GLOB_DIR
    apks = sorted(apk_dir.glob("*.apk"), key=lambda f: f.stat().st_mtime, reverse=True)
    if not apks:
        print(f"❌ 未找到APK文件 in {apk_dir}")
        return None

    apk_path = apks[0]
    print(f"✅ 编译成功: {apk_path.name} ({apk_path.stat().st_size // 1024 // 1024}MB)")
    return str(apk_path)


def step2_start_memu():
    """步骤2: 启动MEmu模拟器"""
    print("\n=== 步骤2: 启动MEmu模拟器 ===")
    # 检查ADB是否已连接
    result = run_cmd(f'"{ADB_PATH}" devices')
    if MEMU_ADB_HOST in result.stdout and "device" in result.stdout:
        print(f"✅ MEmu已连接: {MEMU_ADB_HOST}")
        return True

    # 启动MEmu
    print(f"  启动MEmu实例 {MEMU_INSTANCE_ID}...")
    run_cmd(f'"{MEMUC_PATH}" start -i {MEMU_INSTANCE_ID}', timeout=60)
    time.sleep(15)  # 等待MEmu启动

    # ADB connect
    run_cmd(f'"{ADB_PATH}" connect {MEMU_ADB_HOST}')
    time.sleep(3)

    # 验证连接
    result = run_cmd(f'"{ADB_PATH}" devices')
    if MEMU_ADB_HOST in result.stdout and "device" in result.stdout:
        print(f"✅ MEmu已连接: {MEMU_ADB_HOST}")
        return True
    else:
        print(f"❌ MEmu连接失败")
        return False


def step3_install_apk(apk_path):
    """步骤3: 安装APK"""
    print(f"\n=== 步骤3: 安装APK ===")
    result = run_cmd(
        f'"{ADB_PATH}" -s {MEMU_ADB_HOST} install -r "{apk_path}"',
        timeout=120
    )
    if "Success" in result.stdout:
        print(f"✅ APK安装成功")
        return True
    else:
        print(f"❌ APK安装失败: {result.stdout[:300]}")
        return False


def step4_l1_verify():
    """步骤4: L1验证 - App启动无崩溃"""
    print("\n=== 步骤4: L1验证（App启动无崩溃）===")
    # 清空logcat
    run_cmd(f'"{ADB_PATH}" -s {MEMU_ADB_HOST} logcat -c')

    # 启动App
    run_cmd(
        f'"{ADB_PATH}" -s {MEMU_ADB_HOST} shell am start -n {PACKAGE}/{MAIN_ACTIVITY}'
    )
    time.sleep(8)  # 等待App启动

    # 检查崩溃
    result = run_cmd(
        f'"{ADB_PATH}" -s {MEMU_ADB_HOST} logcat -d -s AndroidRuntime:E'
    )
    if "FATAL EXCEPTION" in result.stdout:
        print("❌ App启动崩溃!")
        # 输出崩溃信息（过滤敏感字段）
        crash_lines = [l for l in result.stdout.split('\n') if 'FATAL' in l or 'Exception' in l]
        for line in crash_lines[:5]:
            print(f"  {line.strip()}")
        return False

    # 检查当前Activity（Windows 用 findstr 替代 grep）
    result = run_cmd(
        f'"{ADB_PATH}" -s {MEMU_ADB_HOST} shell dumpsys activity activities | findstr mResumedActivity'
    )
    if PACKAGE in result.stdout:
        print(f"✅ L1验证通过: App正常启动")
        return True
    else:
        print(f"⚠️ App可能未正常启动（未检测到 {PACKAGE}）")
        return False


def main():
    import argparse
    ap = argparse.ArgumentParser(description="快速编译+安装+L1验证（--skip-build 跳过编译，直接使用最新APK）")
    ap.add_argument("--skip-build", action="store_true", help="跳过编译，直接使用 app/build/outputs/apk/app/debug 下最新APK")
    args = ap.parse_args()

    print("=" * 60)
    print("Legado 快速编译+安装+L1验证")
    print("=" * 60)

    # 步骤1: 编译（--skip-build 时跳过）
    if args.skip_build:
        apks = sorted(APK_GLOB_DIR.glob("*.apk"), key=lambda f: f.stat().st_mtime, reverse=True)
        if not apks:
            print(f"❌ 未找到APK文件 in {APK_GLOB_DIR}")
            sys.exit(2)
        apk_path = str(apks[0])
        print(f"✅ 跳过编译，使用最新APK: {Path(apk_path).name} ({apk_path and (apks[0].stat().st_size // 1024 // 1024)}MB)")
    else:
        apk_path = step1_build()
        if not apk_path:
            sys.exit(2)

    # 步骤2: 启动MEmu
    if not step2_start_memu():
        sys.exit(2)

    # 步骤3: 安装APK
    if not step3_install_apk(apk_path):
        sys.exit(1)

    # 步骤4: L1验证
    if not step4_l1_verify():
        sys.exit(1)

    print("\n" + "=" * 60)
    print("✅ 全部完成: 编译+安装+L1验证通过")
    print(f"   APK: {Path(apk_path).name}")
    print("=" * 60)
    sys.exit(0)


if __name__ == "__main__":
    main()
