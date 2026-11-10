#!/usr/bin/env python3
"""adb360 —— 5037 被 360 手机助手劫持时的裸协议 adb 通道（Windows 真机）。

背景（docs/project-rules/physical-device-testing.md §五）：
360MobileSrv.exe 内嵌魔改 adb server（版本 26200）独占 5037，标准 adb client（协议 41）
版本协商必败；且该进程有内核自保护（taskkill/suspend 均 Access Denied）。
做法：绕过版本握手，直接按 adb server 文本协议（4 位十六进制长度前缀）连 TCP。

用法：
  python adb360.py devices
  python adb360.py shell "dumpsys window | grep mCurrentFocus"
  python adb360.py push 本地文件 /data/local/tmp/x.apk
  python adb360.py install 本地.apk            # push + pm install -r -d
  python adb360.py root-shell                   # 打印设备序列号

说明：仅依赖标准库；所有命令默认前台同步执行（后台化子进程会被通道关闭杀掉）。
"""

import argparse
import os
import socket
import struct
import sys
import time

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 5037


class AdbError(RuntimeError):
    pass


class RawAdb:
    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT, timeout: float = 15.0):
        self.host = host
        self.port = port
        self.timeout = timeout

    # ---------- 基础 ----------
    def _connect(self) -> socket.socket:
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(self.timeout)
        return sock

    @staticmethod
    def _recv_exact(sock: socket.socket, size: int) -> bytes:
        buf = bytearray()
        while len(buf) < size:
            chunk = sock.recv(size - len(buf))
            if not chunk:
                raise AdbError("连接被对端关闭（期望 %d 字节，实际 %d）" % (size, len(buf)))
            buf.extend(chunk)
        return bytes(buf)

    @staticmethod
    def _send_service(sock: socket.socket, service: str) -> None:
        payload = service.encode("utf-8")
        sock.sendall(("%04x" % len(payload)).encode("ascii") + payload)

    def _read_status(self, sock: socket.socket) -> None:
        status = self._recv_exact(sock, 4)
        if status == b"OKAY":
            return
        if status == b"FAIL":
            length = int(self._recv_exact(sock, 4), 16)
            msg = self._recv_exact(sock, length).decode("utf-8", "replace")
            raise AdbError("FAIL: %s" % msg)
        raise AdbError("未知状态: %r" % status)

    # ---------- host 级 ----------
    def devices(self) -> list:
        """返回 [(serial, state)]。"""
        sock = self._connect()
        try:
            self._send_service(sock, "host:devices")
            self._read_status(sock)
            length = int(self._recv_exact(sock, 4), 16)
            body = self._recv_exact(sock, length).decode("utf-8", "replace")
        finally:
            sock.close()
        result = []
        for line in body.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                result.append((parts[0], parts[1]))
        return result

    def resolve_serial(self, serial: str = "") -> str:
        if serial:
            return serial
        items = self.devices()
        if not items:
            raise AdbError("host:devices 为空：360 server 未识别到真机（检查 USB 调试/授权）")
        for s, state in items:
            if state == "device":
                return s
        raise AdbError("设备均不可用：%s" % items)

    def _transport(self, serial: str = "") -> socket.socket:
        sock = self._connect()
        self._send_service(sock, "host:transport:%s" % self.resolve_serial(serial))
        self._read_status(sock)
        return sock

    # ---------- 设备级 ----------
    def shell(self, cmd: str, serial: str = "", timeout: float = 60.0) -> str:
        sock = self._transport(serial)
        try:
            sock.settimeout(timeout)
            self._send_service(sock, "shell:%s" % cmd)
            self._read_status(sock)
            chunks = []
            while True:
                try:
                    data = sock.recv(65536)
                except socket.timeout:
                    break
                if not data:
                    break
                chunks.append(data)
        finally:
            sock.close()
        return b"".join(chunks).decode("utf-8", "replace")

    def push(self, local_path: str, remote_path: str, mode: str = "0644",
             serial: str = "", timeout: float = 240.0) -> None:
        size = os.path.getsize(local_path)
        spec = ("%s,%s" % (remote_path, mode)).encode("utf-8")
        sock = self._transport(serial)
        try:
            sock.settimeout(timeout)
            self._send_service(sock, "sync:")
            self._read_status(sock)

            sock.sendall(b"SEND" + struct.pack("<I", len(spec)) + spec)
            sent = 0
            with open(local_path, "rb") as fh:
                while True:
                    chunk = fh.read(65536)
                    if not chunk:
                        break
                    sock.sendall(b"DATA" + struct.pack("<I", len(chunk)) + chunk)
                    sent += len(chunk)
                    if size and sent % (8 * 1024 * 1024) < 65536:
                        sys.stderr.write("\r  推送 %.1f/%.1f MB" % (sent / 1048576.0, size / 1048576.0))
                        sys.stderr.flush()
            sys.stderr.write("\n")
            sock.sendall(b"DONE" + struct.pack("<I", 0))
            self._read_status(sock)
            sock.sendall(b"QUIT" + struct.pack("<I", 0))
        finally:
            sock.close()

    def install(self, apk_path: str, serial: str = "", reinstall: bool = True,
                extra_flags: str = "") -> str:
        """覆盖安装（-r，保留数据）。extra_flags 可传 `-d`（设备 versionCode 异常偏高时放行降级）。"""
        remote = "/data/local/tmp/%s" % os.path.basename(apk_path)
        self.push(apk_path, remote, serial=serial)
        flags = "-r" if reinstall else ""
        return self.shell("pm install %s %s %s" % (flags, extra_flags, remote),
                          serial=serial, timeout=300.0)

    def pull(self, remote_path: str, local_path: str, serial: str = "", timeout: float = 240.0) -> int:
        """sync RECV 拉取文件（DATA/DONE 帧）。返回写入字节数。"""
        sock = self._transport(serial)
        written = 0
        try:
            sock.settimeout(timeout)
            self._send_service(sock, "sync:")
            self._read_status(sock)
            spec = remote_path.encode("utf-8")
            sock.sendall(b"RECV" + struct.pack("<I", len(spec)) + spec)
            with open(local_path, "wb") as fh:
                while True:
                    ident = self._recv_exact(sock, 4)
                    if ident == b"DATA":
                        length = struct.unpack("<I", self._recv_exact(sock, 4))[0]
                        fh.write(self._recv_exact(sock, length))
                        written += length
                        continue
                    if ident == b"DONE":
                        self._recv_exact(sock, 4)
                        break
                    if ident == b"FAIL":
                        length = struct.unpack("<I", self._recv_exact(sock, 4))[0]
                        msg = self._recv_exact(sock, length).decode("utf-8", "replace")
                        raise AdbError("pull FAIL: %s" % msg)
                    raise AdbError("pull 未知帧: %r" % ident)
            sock.sendall(b"QUIT" + struct.pack("<I", 0))
        finally:
            sock.close()
        return written

    def screenshot(self, local_path: str, serial: str = "") -> str:
        """截屏到本地（真机取屏走 screencap + pull，避免 shell 二进制被文本解码破坏）。"""
        remote = "/data/local/tmp/_shot_%d.png" % int(time.time() * 1000)
        self.shell("screencap -p %s" % remote, serial=serial, timeout=30.0)
        self.pull(remote, local_path, serial=serial, timeout=60.0)
        self.shell("rm -f %s" % remote, serial=serial, timeout=15.0)
        return local_path

    # ---------- UI 自动化（dump + tap） ----------
    def dump_ui(self, serial: str = "", timeout: float = 60.0) -> str:
        """uiautomator dump 后拉回 XML 文本（Compose 的可见文本在 text 属性里）。"""
        import tempfile
        remote = "/data/local/tmp/_ui_%d.xml" % int(time.time() * 1000)
        self.shell("uiautomator dump %s" % remote, serial=serial, timeout=timeout)
        tmp = os.path.join(tempfile.gettempdir(), "_adb360_ui.xml")
        try:
            self.pull(remote, tmp, serial=serial, timeout=60.0)
            with open(tmp, "r", encoding="utf-8", errors="replace") as fh:
                return fh.read()
        finally:
            self.shell("rm -f %s" % remote, serial=serial, timeout=15.0)

    def find_node(self, xml: str, label: str):
        """在 dump XML 中按 text/content-desc 包含关系找节点，返回 (x, y, node_attrs)。"""
        import re
        for match in re.finditer(r"<node[^>]*?/?>", xml):
            tag = match.group(0)
            texts = re.findall(r'(?:text|content-desc)="([^"]*)"', tag)
            if not any(label in t for t in texts):
                continue
            bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
            if not bounds:
                continue
            x1, y1, x2, y2 = map(int, bounds.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2, tag
        return None

    def tap(self, x: int, y: int, serial: str = "") -> None:
        self.shell("input tap %d %d" % (x, y), serial=serial, timeout=20.0)

    def tap_text(self, label: str, serial: str = "") -> bool:
        node = self.find_node(self.dump_ui(serial=serial), label)
        if not node:
            return False
        self.tap(node[0], node[1], serial=serial)
        return True


class CliAdb:
    """标准 adb.exe 后端的同构实现（与 [RawAdb] 同 API）。

    何时用：360MobileSrv 退出后 5037 归标准 adb server 所有，裸协议仍可用；
    但**本工具链的命令常常在独立 Job 里执行**，命令结束会连带杀掉自己启动的 server
    （实测：命令行内 start-server 能连上，下一条命令 ConnectionRefused）。
    标准 adb.exe 每条命令内部自起 server，天然规避该问题 ⇒ 作为可用性优先的后端。
    """

    def __init__(self, exe: str = "", serial: str = "", timeout: float = 60.0):
        self.exe = exe or os.environ.get("ADB_EXE") or r"C:\Android\Sdk\platform-tools\adb.exe"
        self.serial = serial
        self.timeout = timeout

    def _run(self, args, timeout: float = None, text: bool = True):
        import subprocess
        cmd = [self.exe]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        return subprocess.run(cmd, capture_output=True, timeout=timeout or self.timeout, text=text)

    def devices(self) -> list:
        out = self._run(["devices"]).stdout or ""
        result = []
        for line in out.splitlines():
            line = line.strip()
            if not line or line.startswith("*") or line.startswith("List of"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                result.append((parts[0], parts[1]))
        return result

    def resolve_serial(self, serial: str = "") -> str:
        if serial:
            self.serial = serial
            return serial
        items = [it for it in self.devices() if it[1] == "device"]
        if not items:
            raise AdbError("adb devices 为空：检查 USB 调试/授权（%s）" % self.devices())
        self.serial = items[0][0]
        return self.serial

    def shell(self, cmd: str, serial: str = "", timeout: float = 60.0) -> str:
        if serial:
            self.serial = serial
        return self._run(["shell", cmd], timeout=timeout).stdout or ""

    def push(self, local_path: str, remote_path: str, mode: str = "0644",
             serial: str = "", timeout: float = 240.0) -> None:
        if serial:
            self.serial = serial
        r = self._run(["push", local_path, remote_path], timeout=timeout)
        if r.returncode != 0:
            raise AdbError("push 失败：%s" % (r.stderr or r.stdout))

    def install(self, apk_path: str, serial: str = "", reinstall: bool = True,
                extra_flags: str = "") -> str:
        if serial:
            self.serial = serial
        args = ["install"]
        if reinstall:
            args.append("-r")
        if extra_flags:
            args += extra_flags.split()
        args.append(apk_path)
        return (self._run(args, timeout=600.0).stdout or "")

    def pull(self, remote_path: str, local_path: str, serial: str = "",
             timeout: float = 240.0) -> int:
        if serial:
            self.serial = serial
        r = self._run(["pull", remote_path, local_path], timeout=timeout)
        if r.returncode != 0:
            raise AdbError("pull 失败：%s" % (r.stderr or r.stdout))
        return os.path.getsize(local_path) if os.path.exists(local_path) else 0

    def screenshot(self, local_path: str, serial: str = "") -> str:
        if serial:
            self.serial = serial
        r = self._run(["exec-out", "screencap", "-p"], timeout=120.0, text=False)
        if r.returncode != 0 or not r.stdout:
            raise AdbError("screencap 失败：%s" % r.stderr)
        with open(local_path, "wb") as fh:
            fh.write(r.stdout)
        return local_path

    def dump_ui(self, serial: str = "", timeout: float = 60.0) -> str:
        if serial:
            self.serial = serial
        remote = "/data/local/tmp/_ui_%d.xml" % int(time.time() * 1000)
        self.shell("uiautomator dump %s" % remote, timeout=timeout)
        import tempfile
        tmp = os.path.join(tempfile.gettempdir(), "_adb360_ui_cli.xml")
        try:
            self.pull(remote, tmp, timeout=60.0)
            with open(tmp, "r", encoding="utf-8", errors="replace") as fh:
                return fh.read()
        finally:
            self.shell("rm -f %s" % remote, timeout=15.0)

    find_node = RawAdb.find_node
    tap = RawAdb.tap
    tap_text = RawAdb.tap_text


def open_adb(prefer_raw: bool = True, port: int = DEFAULT_PORT) -> "RawAdb | CliAdb":
    """返回可用后端：裸协议优先（360 劫持场景），不可用则回落标准 adb.exe。"""
    if prefer_raw:
        try:
            raw = RawAdb(port=port)
            if raw.devices():
                return raw
        except Exception:
            pass
    cli = CliAdb()
    cli.resolve_serial("")
    return cli


def main() -> int:
    parser = argparse.ArgumentParser(description="裸协议 adb 通道（360 劫持场景）")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--serial", default="")
    sub = parser.add_subparsers(dest="action", required=True)

    sub.add_parser("devices")
    p_shell = sub.add_parser("shell")
    p_shell.add_argument("cmd")
    p_push = sub.add_parser("push")
    p_push.add_argument("local")
    p_push.add_argument("remote")
    p_install = sub.add_parser("install")
    p_install.add_argument("apk")
    p_pull = sub.add_parser("pull")
    p_pull.add_argument("remote")
    p_pull.add_argument("local")
    p_shot = sub.add_parser("screenshot")
    p_shot.add_argument("local")
    sub.add_parser("ui")
    p_tap = sub.add_parser("tap-text")
    p_tap.add_argument("label")

    args = parser.parse_args()
    try:
        if args.action == "devices":
            items = []
            try:
                items = RawAdb(port=args.port).devices()
            except Exception:
                items = CliAdb().devices()
            if not items:
                print("（空）未识别到设备")
                return 2
            for serial, state in items:
                print("%s\t%s" % (serial, state))
            return 0
        adb = open_adb(port=args.port)
        if args.serial:
            adb.serial = args.serial
        if args.action == "shell":
            sys.stdout.write(adb.shell(args.cmd, serial=args.serial))
            return 0
        if args.action == "push":
            adb.push(args.local, args.remote, serial=args.serial)
            print("pushed %s -> %s" % (args.local, args.remote))
            return 0
        if args.action == "install":
            started = time.time()
            print(adb.install(args.apk, serial=args.serial))
            print("耗时 %.1fs" % (time.time() - started))
            return 0
        if args.action == "pull":
            n = adb.pull(args.remote, args.local, serial=args.serial)
            print("pulled %s -> %s（%d 字节）" % (args.remote, args.local, n))
            return 0
        if args.action == "screenshot":
            adb.screenshot(args.local, serial=args.serial)
            print("截图已保存：%s" % args.local)
            return 0
        if args.action == "ui":
            sys.stdout.write(adb.dump_ui(serial=args.serial))
            return 0
        if args.action == "tap-text":
            ok = adb.tap_text(args.label, serial=args.serial)
            print("tapped" if ok else "not-found: %s" % args.label)
            return 0 if ok else 4
    except AdbError as exc:
        sys.stderr.write("通道错误：%s\n" % exc)
        return 3
    return 1


if __name__ == "__main__":
    sys.exit(main())
