#!/usr/bin/env python3
r"""adb360_cli —— 让既有测试脚本（build_and_smoke / quick_build_install 等）在
「360 手机助手劫持 5037」场景下继续可用：把常用 adb CLI 子命令翻译成裸协议调用。

用法（不直接给人用，供 ADB_PATH 指向本脚本）：
  set ADB_PATH=<repo>\ai_tests\scripts\adb360_shim.cmd
  set MEMU_ADB_HOST=70cc13c8          # 走裸协议时这里填真机序列号（不是 tcp:port）
  python ai_tests/scripts/build_and_smoke.py --skip-build

支持子命令：devices / connect / shell / install / uninstall / exec-out screencap /
logcat / pull / push / 其余原样交给 shell。
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb360 import RawAdb, AdbError  # noqa: E402


def parse(argv):
    serial = ""
    rest = []
    i = 0
    while i < len(argv):
        token = argv[i]
        if token in ("-s", "-t"):
            serial = argv[i + 1] if i + 1 < len(argv) else ""
            i += 2
            continue
        if token in ("-P", "--port"):
            i += 2
            continue
        if token == "-d" and not rest:
            i += 1
            continue
        rest.append(token)
        i += 1
    return serial, rest


def main() -> int:
    serial, rest = parse(sys.argv[1:])
    if not rest:
        sys.stderr.write("用法：adb360_cli.py [-s serial] <devices|shell|install|...>\n")
        return 1
    adb = RawAdb()
    action, args = rest[0], rest[1:]
    try:
        if action == "devices":
            for s, state in adb.devices():
                print("%s\t%s" % (s, state))
            return 0
        if action == "connect":
            # 裸协议通道不需要 connect；回显成功，避免上层脚本因 connect 失败提前退出
            print("connected to %s" % (args[0] if args else adb.resolve_serial(serial)))
            return 0
        if action == "disconnect":
            print("disconnected")
            return 0
        if action == "shell":
            sys.stdout.write(adb.shell(" ".join(args), serial=serial, timeout=180.0))
            return 0
        if action == "install":
            flags = [a for a in args if a.startswith("-")]
            apks = [a for a in args if not a.startswith("-")]
            if not apks:
                sys.stderr.write("install 缺少 APK 路径\n")
                return 1
            # 设备上已装包的 versionCode 异常偏高（历史构建机时钟格式缺陷）时，
            # 覆盖安装会报 VERSION_DOWNGRADE；ADB_INSTALL_ALLOW_DOWNGRADE=1 追加 -d（仍保留用户数据）
            extra = "-d" if os.environ.get("ADB_INSTALL_ALLOW_DOWNGRADE") == "1" else ""
            out = adb.install(apks[-1], serial=serial, reinstall=("-r" in flags), extra_flags=extra)
            print(out.strip())
            return 0 if "Success" in out else 1
        if action == "uninstall":
            pkg = [a for a in args if not a.startswith("-")][-1]
            print(adb.shell("pm uninstall %s" % pkg, serial=serial, timeout=180.0).strip())
            return 0
        if action == "exec-out":
            if args[:1] == ["screencap"]:
                tmp = os.path.join(tempfile.gettempdir(), "_adb360_exec_out.png")
                adb.screenshot(tmp, serial=serial)
                with open(tmp, "rb") as fh:
                    sys.stdout.buffer.write(fh.read())
                return 0
            sys.stderr.write("exec-out 仅支持 screencap -p\n")
            return 1
        if action == "pull":
            real = [a for a in args if not a.startswith("-")]
            n = adb.pull(real[0], real[1], serial=serial)
            print("%s: %d bytes" % (real[1], n))
            return 0
        if action == "push":
            real = [a for a in args if not a.startswith("-")]
            adb.push(real[0], real[1], serial=serial)
            print("%s: pushed" % real[1])
            return 0
        # 其余（logcat / am / dumpsys / run-as ...）整体交给 shell
        sys.stdout.write(adb.shell(" ".join([action] + args), serial=serial, timeout=180.0))
        return 0
    except AdbError as exc:
        sys.stderr.write("通道错误：%s\n" % exc)
        return 3


if __name__ == "__main__":
    sys.exit(main())
