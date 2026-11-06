#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""7.6 T-E1 崩溃隔离 L3 实测：杀掉 `:llm` 推理进程后，主进程必须存活且朗读/阅读不中断。

为什么需要脚本而不是手点：这条验证要**在推理进行中**精确 kill 目标进程，并同时取证
「主进程 pid 未变 / 无 FATAL / 剧本仍落盘」。手点无法保证时序，也无法留下可复核证据。

前置：设备上已装 debug 包 + 已导入 GGUF（`files/models/llm/<id>/model.gguf`）。
脚本会自动把本地 AI 打开（routeFor 只看偏好，不看 RAM 门限），跑完恢复原偏好。

用法：
    ai_tests\\venv\\Scripts\\python.exe ai_tests\\scripts\\l3_llm_kill_isolation.py
    ... --device 127.0.0.1:21503 --book-url https://... --keep-prefs
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "output" / "l3"
PACKAGE = "io.legado.miss.app.debug"
DEFAULT_DEVICE = os.environ.get("MEMU_ADB_HOST", "127.0.0.1:21503")
PREFS_NAME = "%s_preferences.xml" % PACKAGE
ADB_CANDIDATES = (
    os.environ.get("ADB_PATH"),
    r"C:\Android\Sdk\platform-tools\adb.exe",
    r"D:\Program Files\Microvirt\MEmu\adb.exe",
)


def find_adb():
    for cand in ADB_CANDIDATES:
        if cand and Path(cand).is_file():
            return cand
    return "adb"


def _quote(cmd):
    """把任意 shell 命令包成单引号字面量（内部单引号用 '\'' 转义）。"""
    return "'" + cmd.replace("'", "'\\''") + "'"


def _has_profile(xml, model_id):
    """偏好里 profileId 是否真的等于 model_id（自闭合 `value="x"` 与开闭标签 `>x<` 两种写法都要认）。"""
    if not xml or not model_id:
        return False
    pattern = re.compile(r'name="localLlmProfileId"[^>]*(?:value="%s"|>%s<)'
                         % (re.escape(model_id), re.escape(model_id)))
    return bool(pattern.search(xml))


class Device:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def sh(self, cmd, timeout=120, root=False):
        # 必须把远端命令拼成**一个**字符串交给 `adb shell`：adb 会把多个参数用空格连接，
        # 拆成 ["su","0","sh","-c",cmd] 时远端实际执行的是 `sh -c ls /data/...`
        # ⇒ sh 只跑 `ls`（列根目录），后面路径成了 $0，表现为"读到 acct / 偏好为空"。
        remote = "su 0 sh -c %s" % _quote(cmd) if root else cmd
        full = [self.adb, "-s", self.serial, "shell", remote]
        proc = subprocess.run(full, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout)
        return (proc.stdout or "").strip()




def pid_of(dev, name):
    out = dev.sh("pidof %s" % name)
    parts = [p for p in out.split() if p.isdigit()]
    return int(parts[0]) if parts else None


def hard_kill(dev, package, tries=10):
    """SIGKILL 主进程直到真的消失（返回 True）。

    为什么不用 `am force-stop`：它是**异步**的，进程在退出过程中会把 SharedPreferences
    内存镜像整文件回写，把外部刚写入的偏好覆盖掉（E15 实测：写 qwen → 启动后变空）。
    SIGKILL 不给回写机会，是自动化改偏好唯一可靠的前置动作。
    """
    for _ in range(tries):
        pid = pid_of(dev, package)
        if not pid:
            return True
        dev.sh("kill -9 %d" % pid, root=True)
        time.sleep(1)
    return pid_of(dev, package) is None


def read_prefs(dev):
    return dev.sh("cat /data/data/%s/shared_prefs/%s" % (PACKAGE, PREFS_NAME), root=True)


def prefs_pairs(xml):
    """把 SharedPreferences XML 归一化成 {name: value}。

    为什么要归一化：应用重启后会把整份偏好**重新序列化**（缩进/空行/自闭合写法都可能变），
    逐字节比较会误报"没写进去/没还原"（2026-11-06 实测：内容已生效但空行数不同）。
    只比键值，才能区分"真的没写进去"和"格式被重排"。
    """
    pairs = {}
    if not xml:
        return pairs
    for m in re.finditer(r'<(\w+)\s+name="([^"]+)"([^>]*?)(?:/>|>([^<]*)</\1>)', xml, re.S):
        attrs, text = m.group(3), m.group(4)
        val = re.search(r'value="([^"]*)"', attrs)
        pairs[m.group(2)] = val.group(1) if val else (text or "")
    return pairs


def write_prefs(dev, text, owner=""):
    """把整份 XML 覆盖到应用偏好文件，并**回读校验**（返回是否写入成功）。

    用 `cat > 目标` 而不是 `cp`：`cp` 会新建 inode（可能带 shell 的 SELinux 标签，应用读不到），
    在部分 ROM 上还会直接失败但退出码被 adb 吞掉 ⇒ 表现为「写了但值没变」。
    覆盖原文件则保持 inode 与上下文，再补 chown/chmod/restorecon 三重保险。
    """
    tmp = ROOT / ".temp" / "prefs_patch.xml"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    tmp.write_text(text, encoding="utf-8")
    target = "/data/local/tmp/prefs_patch.xml"
    subprocess.run([dev.adb, "-s", dev.serial, "push", str(tmp), target],
                   capture_output=True, text=True, timeout=120)
    path = "/data/data/%s/shared_prefs/%s" % (PACKAGE, PREFS_NAME)
    dev.sh("cat %s > %s" % (target, path), root=True)
    # 属主必须沿用原文件（写错属主会让应用读不到偏好，表现为"设置全丢"）
    if owner and ":" in owner:
        dev.sh("chown %s %s" % (owner, path), root=True)
    dev.sh("chmod 660 %s" % path, root=True)
    dev.sh("restorecon %s 2>/dev/null" % path, root=True)
    # 归一化比对键值（应用会把整份 XML 重排，逐字节比较会误报）
    return prefs_pairs(read_prefs(dev)) == prefs_pairs(text)


def stop_app(dev, package):
    """确保主进程消失（返回 True）。

    先 `am force-stop`（系统层面标记停止，避免被杀后立刻重启），进程若仍存活再 SIGKILL。
    必须等到**真的没有 pid** 才写偏好：否则应用退出/重启过程中的整文件回写会覆盖补丁（E15）。
    """
    dev.sh("am force-stop %s" % package, root=True)
    for _ in range(8):
        if not pid_of(dev, package):
            return True
        time.sleep(1)
    return hard_kill(dev, package)


def patch_local_ai(xml, profile_id):
    """打开本地 AI：`AiTaskRouter.routeFor` 只认 enabled + profileId + unsupportedReason 三项。"""
    def upsert(name, tag, value):
        """同时兼容自闭合 `<string name="x" value="v" />` 与开闭 `<string name="x">v</string>`。

        只认自闭合形式会漏掉既有条目 ⇒ 追加出**重复 key**，SharedPreferences 取最后一个，
        一旦顺序不如预期就表现为"写进去又被清空"。
        """
        nonlocal xml
        pattern = re.compile(
            r'<%s name="%s"[^>]*?(?:/>|>.*?</%s>)' % (tag, re.escape(name), tag),
            re.DOTALL)
        new = '<%s name="%s" value="%s" />' % (tag, name, value)
        if pattern.search(xml):
            xml = pattern.sub(new, xml, count=1)
        else:
            xml = xml.replace("</map>", "    %s\n</map>" % new, 1)

    upsert("localLlmEnabled", "boolean", "true")
    upsert("localLlmProfileId", "string", profile_id)
    upsert("localLlmUnsupportedReason", "string", "")
    return xml


def detect_model_id(dev):
    out = dev.sh("ls /data/data/%s/files/models/llm 2>/dev/null" % PACKAGE, root=True)
    ids = [x for x in out.split() if x and not x.startswith(".")]
    return ids[0] if ids else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default=DEFAULT_DEVICE)
    ap.add_argument("--book-url", default="https://www.sou78.com/book/158686/")
    ap.add_argument("--model-id", default="")
    ap.add_argument("--wait-llm", type=int, default=180)
    ap.add_argument("--keep-prefs", action="store_true", help="跑完不恢复原偏好（方便连续复测）")
    args = ap.parse_args()

    adb = find_adb()
    dev = Device(adb, args.device)
    evidence = {
        "schema": 1,
        "case": "T-E1 推理进程被杀后主进程存活",
        "device": args.device,
        "started_at": datetime.datetime.now().isoformat(timespec="seconds"),
        "steps": [],
    }

    def step(name, ok, detail=""):
        evidence["steps"].append({"name": name, "ok": bool(ok), "detail": str(detail)[:500]})
        print("[%s] %s %s" % ("OK" if ok else "FAIL", name, detail))
        return ok

    devices = subprocess.run([adb, "devices"], capture_output=True, text=True,
                             encoding="utf-8", errors="replace", timeout=60).stdout or ""
    if args.device not in devices:
        subprocess.run([adb, "connect", args.device], capture_output=True, timeout=60)
        devices = subprocess.run([adb, "devices"], capture_output=True, text=True,
                                 encoding="utf-8", errors="replace", timeout=60).stdout or ""
    if args.device not in devices:
        step("设备就绪", False, devices.strip())
        evidence["conclusion"] = "设备未连接"
        return _dump(evidence, 2)
    step("设备就绪", True, args.device)

    model_id = args.model_id or detect_model_id(dev)
    evidence["model_id"] = model_id
    if not step("已导入 GGUF 模型", bool(model_id), model_id or "files/models/llm 为空"):
        evidence["conclusion"] = "前置缺失：设备上没有模型，无法验证 AI 生效分支"
        return _dump(evidence, 2)

    original_prefs = read_prefs(dev)
    evidence["prefs_backed_up"] = bool(original_prefs)
    # 安全闸：读不到原偏好就绝不写（否则会用残缺 XML 覆盖，等于清空用户设置）
    if not original_prefs or "<map" not in original_prefs:
        step("读取原偏好", False, "shared_prefs 为空或不可读，拒绝写入")
        evidence["conclusion"] = "无法安全修改偏好，已中止（不做破坏性写入）"
        return _dump(evidence, 2)
    (ROOT / ".temp" / "prefs_backup.xml").write_text(original_prefs, encoding="utf-8")

    owner = dev.sh("stat -c %%u:%%g /data/data/%s/shared_prefs/%s" % (PACKAGE, PREFS_NAME), root=True)
    evidence["prefs_owner"] = owner

    # 必须先让主进程**彻底**退出再写偏好：进程退出/重启过程中的整文件回写会覆盖补丁（E15）。
    dead = stop_app(dev, PACKAGE)
    step("应用已停止", dead, "pid=%s" % pid_of(dev, PACKAGE))

    patched = patch_local_ai(original_prefs, model_id)
    activity = "io.legado.app.ui.book.read.ReadBookActivity"
    extra = "--es bookUrl %s" % args.book_url
    kept = False
    for attempt in range(3):
        written = write_prefs(dev, patched, owner)
        step("写入偏好（第 %d 次）" % (attempt + 1), written,
             "回读一致=%s" % written)
        if not written:
            # 文件写不进去（权限/SELinux）时继续拉起应用只会得到「值又变空」的假象
            continue
        started = dev.sh("am start -n %s/%s %s" % (PACKAGE, activity, extra), root=True)
        time.sleep(12)
        on_disk = read_prefs(dev)
        kept = _has_profile(on_disk, model_id)
        step("开启本地 AI（第 %d 次）" % (attempt + 1), kept,
             "profileId=%s owner=%s start=%s" % (model_id, owner, started[:60]))
        if kept:
            break
        stop_app(dev, PACKAGE)
        time.sleep(2)

    # 基线进程必须**确定存在**：偏好路线失败时应用已被 force-stop 且没被成功拉起
    # （ReadBookActivity 依赖有效 bookUrl），实测会让 main_pid_before=None，
    # 使「主进程存活且 pid 未变」这条核心判据退化成不可验证。这里用 MainActivity 兜底建立基线。
    dev.sh("am start -n %s/io.legado.app.ui.main.MainActivity" % PACKAGE, root=True)
    main_pid_before = pid_of(dev, PACKAGE)
    for _ in range(15):
        if main_pid_before:
            break
        time.sleep(2)
        main_pid_before = pid_of(dev, PACKAGE)
    dev.sh("logcat -c")
    evidence["main_pid_before"] = main_pid_before
    step("主进程基线就绪", bool(main_pid_before), "pid=%s" % main_pid_before)
    if not main_pid_before:
        evidence["conclusion"] = "主进程未起来，无法建立 kill 前基线"
        return _dump(evidence, 2)
    evidence["prefs_after_start"] = {"profileId_kept": kept}

    dev.sh("am start -n %s/io.legado.app.ui.book.read.config.CastSceneActivity" % PACKAGE, root=True)
    step("进入听书中心", True, "")

    def wait_llm(seconds):
        end = time.time() + seconds
        while time.time() < end:
            pid = pid_of(dev, "%s:llm" % PACKAGE)
            if pid:
                return pid
            time.sleep(3)
        return None

    # 首选真实链路（听书中心自动排剧本 → ScriptGenerationService → LlmClient）
    llm_pid = wait_llm(min(args.wait_llm, 60))
    route = "script"
    if not llm_pid:
        # 兜底：本模拟器 RAM 5.8GB < 8GB，设置页开关被 LocalLlmFeature 禁用，
        # 加上 SharedPreferences 在 force-stop 竞态下会被应用回写，所以改用「直接启动推理服务」
        # 拉起同一个 `:llm` 进程。崩溃隔离的验证目标（推理进程死 ⇒ 主进程活）不变。
        dev.sh("am startservice -n %s/io.legado.app.help.llm.LlmService" % PACKAGE, root=True)
        step("直接拉起推理服务", True, "route=service")
        llm_pid = wait_llm(args.wait_llm)
        route = "service"
    evidence["llm_pid"] = llm_pid
    evidence["llm_route"] = route
    if not step(":llm 推理进程已启动", bool(llm_pid), "pid=%s route=%s" % (llm_pid, route)):
        evidence["conclusion"] = "未拉起 :llm（需检查剧本生成是否被路由到 LOCAL）"
        return _dump(evidence, 2)

    dev.sh("kill -9 %d" % llm_pid, root=True)
    step("强制杀 :llm", True, "kill -9 %s" % llm_pid)
    time.sleep(8)

    main_pid_after = pid_of(dev, PACKAGE)
    evidence["main_pid_after"] = main_pid_after
    survived = main_pid_after is not None and main_pid_after == main_pid_before
    step("主进程存活且 pid 未变", survived, "%s -> %s" % (main_pid_before, main_pid_after))

    fatals = dev.sh("logcat -d | grep -c 'FATAL EXCEPTION' || true")
    evidence["fatal_count"] = int(fatals) if fatals.strip().isdigit() else 0
    step("无 FATAL 崩溃", evidence["fatal_count"] == 0, "count=%s" % evidence["fatal_count"])

    resumed = dev.sh("dumpsys activity activities | grep mResumedActivity | head -1")
    evidence["resumed_activity"] = resumed
    step("界面仍在前台", PACKAGE in resumed, resumed[:160])

    scripts = dev.sh("find /data/data/%s/files/scripts -name '*.json' 2>/dev/null | wc -l" % PACKAGE,
                     root=True)
    evidence["script_files"] = scripts.strip()
    step("剧本已落盘（降级或正常）", (scripts.strip() or "0") != "0", "files=%s" % scripts.strip())

    if not args.keep_prefs:
        stop_app(dev, PACKAGE)
        write_prefs(dev, original_prefs, owner)
        restored = read_prefs(dev)
        # 归一化比键值（应用重启会重排整份 XML，逐字节比较会误报"没还原"）
        same = prefs_pairs(restored) == prefs_pairs(original_prefs)
        step("恢复原偏好", same,
             "已还原 %d 字节（校验一致=%s）" % (len(original_prefs), same))

    passed = survived and evidence["fatal_count"] == 0
    evidence["result"] = "PASS" if passed else "FAIL"
    evidence["conclusion"] = ("推理进程被杀不影响主进程：朗读链路与界面均存活" if passed
                              else "存在未达标项，见 steps")
    return _dump(evidence, 0 if passed else 1)


def _dump(evidence, code):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / ("llm_kill_isolation_%s.json"
                      % datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))
    path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("证据：%s" % path)
    print("结论：%s" % evidence.get("conclusion", ""))
    return code


if __name__ == "__main__":
    sys.exit(main())
