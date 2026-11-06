#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""7.2 T-A2 端侧模型链路 L3：打开本地 AI → 开书 → 听书中心自动生成 → 校验剧本落盘与来源。

为什么不能复用 `l3_llm_kill_isolation.py` 一把跑完：7.6 的脚本**一看到 `:llm` 起来就 kill -9**
（它的目标是崩溃隔离），而 7.2 要等**完整生成**（冷加载 30s+ 推理）。两者时序目标相反，
故拆成两条脚本，共用同一套「安全改偏好 + 起服务」原语（本文件直接 import 7.6 的实现）。

前置：debug 包已装 + 已导入 GGUF（`files/models/llm/<id>/model.gguf`）+ 设备上有至少一本书。

用法：
    ai_tests\\venv\\Scripts\\python.exe ai_tests/scripts/l3_local_ai_generate.py --book-url <开放测书>
    ... --timeout 300 --keep-prefs

安全设计：
- 打开本地 AI 走**应用自己的写入口** `DebugLlmOverride`（仅 debug 包生效的 Intent 注入）。
  外部直接改 `shared_prefs/*.xml` 会被平台回写（issues-found E15，实测写入后启动必被清空），
  因此不再使用该路线；
- 跑完按原样还原偏好（沿用原文件属主 + chmod 660 + restorecon）。

证据：`output/l3/local_ai_generate_<ts>.json`（只记 technical 字段：source/degraded/段数/进程/耗时，
**不落正文**，符合 output-safety）。
"""

import argparse
import datetime
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from l3_llm_kill_isolation import (  # noqa: E402
    PACKAGE,
    PREFS_NAME,
    Device,
    _has_profile,
    detect_model_id,
    find_adb,
    pid_of,
    prefs_pairs,
    read_prefs,
    stop_app,
    write_prefs,
)

OUT_DIR = ROOT / "output" / "l3"
SETTINGS_ACTIVITY = "io.legado.app.ui.book.read.config.LlmSettingsActivity"
READ_ACTIVITY = "io.legado.app.ui.book.read.ReadBookActivity"
CAST_ACTIVITY = "io.legado.app.ui.book.read.config.CastSceneActivity"
# 与 `help/llm/DebugLlmOverride.kt` 的常量保持一致（仅 debug 包生效）
EXTRA_ENABLE = "debugEnableLocalAi"
EXTRA_MODEL_ID = "debugModelId"
SCRIPTS_GLOB = "/data/data/%s/files/scripts/*/*.json" % PACKAGE


def newest_script(dev):
    """设备上最新的剧本文件路径与其 mtime（秒）。无则 (None, 0)。

    为什么不能只看"有没有文件"：同一章重跑会覆盖同一个 path，而设备上**上一轮**的剧本
    本来就在 ⇒ 一开始就被误判成"已生成"，脚本会立刻退出（2026-11-06 实测踩到）。
    因此必须比 mtime 是否 ≥ 本轮开始时间。
    """
    out = dev.sh("ls -t %s 2>/dev/null | head -1" % SCRIPTS_GLOB, root=True)
    path = out.strip().splitlines()[0].strip() if out.strip() else None
    if not path:
        return None, 0
    ts = dev.sh("stat -c %%Y %s" % path, root=True).strip()
    return path, int(ts) if ts.isdigit() else 0


def script_facts(dev, path):
    """只提取 technical 字段，**不读取/不输出正文**。"""
    raw = dev.sh("cat %s" % path, root=True)
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except Exception as e:  # JSON 损坏也要给出可读结论
        return {"parseError": type(e).__name__, "bytes": len(raw)}
    segments = data.get("segments") or data.get("lines") or []
    characters = data.get("characters") or []
    return {
        "source": data.get("source", ""),
        "degraded": data.get("degraded"),
        "degradedReason": data.get("degradedReason", ""),
        "segments": len(segments) if isinstance(segments, list) else 0,
        "characters": len(characters) if isinstance(characters, list) else 0,
        "bytes": len(raw),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="127.0.0.1:21503")
    ap.add_argument("--book-url", required=True)
    ap.add_argument("--model-id", default="")
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--keep-prefs", action="store_true", help="跑完不恢复原偏好")
    args = ap.parse_args()

    adb = find_adb()
    dev = Device(adb, args.device)
    evidence = {
        "schema": 1,
        "case": "T-A2 端侧模型链路（本地 AI 生成剧本）",
        "device": args.device,
        "started_at": datetime.datetime.now().isoformat(timespec="seconds"),
        "steps": [],
    }

    def step(name, ok, detail=""):
        evidence["steps"].append({"name": name, "ok": bool(ok), "detail": str(detail)[:300]})
        print("[%s] %s %s" % ("OK" if ok else "FAIL", name, detail))
        return ok

    def dump(code):
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        path = OUT_DIR / ("local_ai_generate_%s.json"
                          % datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))
        path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print("证据：%s" % path)
        print("结论：%s" % evidence.get("conclusion", ""))
        return code

    # adb server 随发起 shell 退出而消失 ⇒ 必须在本进程内先 connect（否则 getprop 返回空串）
    proc = subprocess.run([adb, "devices"], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=60)
    if args.device not in (proc.stdout or ""):
        subprocess.run([adb, "connect", args.device], capture_output=True, timeout=60)

    out = dev.sh("getprop sys.boot_completed")
    if not step("设备就绪", out.strip() == "1", "boot_completed=%s" % out.strip()):
        evidence["conclusion"] = "设备未就绪"
        return dump(2)

    model_id = args.model_id or detect_model_id(dev)
    evidence["model_id"] = model_id
    if not step("已导入 GGUF 模型", bool(model_id), model_id or "files/models/llm 为空"):
        evidence["conclusion"] = "前置缺失：设备上没有模型"
        return dump(2)

    original_prefs = read_prefs(dev)
    evidence["prefs_backed_up"] = bool(original_prefs)
    if not original_prefs or "<map" not in original_prefs:
        step("读取原偏好", False, "shared_prefs 为空或不可读，拒绝写入")
        evidence["conclusion"] = "无法安全修改偏好，已中止（不做破坏性写入）"
        return dump(2)

    owner = dev.sh("stat -c %%u:%%g /data/data/%s/shared_prefs/%s" % (PACKAGE, PREFS_NAME), root=True)
    evidence["prefs_owner"] = owner

    # 走**应用自己的写入口**（仅 DEBUG 生效的 Intent 注入）：外部改 XML 会被平台回写（E15），
    # 而这里由 AppConfig 在进程内写入，值一定落盘。见 DebugLlmOverride。
    dev.sh("logcat -c")
    dev.sh("am start -n %s/%s --ez %s true --es %s %s"
           % (PACKAGE, SETTINGS_ACTIVITY, EXTRA_ENABLE, EXTRA_MODEL_ID, model_id), root=True)
    time.sleep(6)
    if not step("本地 AI 已开启（调试注入）", _has_profile(read_prefs(dev), model_id),
                "model=%s" % model_id):
        evidence["conclusion"] = "调试注入未生效（确认装的是 debug 包）"
        return dump(2)

    # 注入时应用会顺带做一次 IPC 自检（不依赖"某本书有正文"），是推理链路是否成立的直接证据
    ipc_log = dev.sh("logcat -d | grep 'DebugLlmOverride: 连接推理进程' || true").strip()
    evidence["ipc_log"] = ipc_log[:200]
    step("主进程 ↔ :llm 连通", "成功" in ipc_log, ipc_log or "无自检日志")

    # 必须重启应用：听书中心只在**创建**时自动排队生成；若它已在栈里，`am start` 只是把它拉到前台，
    # 不会触发新一轮生成（2026-11-06 实测：连跑第二次直接"什么都没发生"）。
    stop_app(dev, PACKAGE)
    time.sleep(2)
    dev.sh("am start -n %s/%s --es bookUrl %s" % (PACKAGE, READ_ACTIVITY, args.book_url), root=True)
    # 阅读页要先把章节正文加载完，听书中心才有「当前章」可生成（网络书在模拟器上首开可能 >15s）
    resumed = ""
    for _ in range(20):
        time.sleep(2)
        resumed = dev.sh("dumpsys activity activities | grep mResumedActivity | head -1")
        if READ_ACTIVITY.rsplit(".", 1)[-1] in resumed:
            break
    step("阅读页已打开", READ_ACTIVITY.rsplit(".", 1)[-1] in resumed, resumed.strip()[:80])
    time.sleep(20)

    main_pid = pid_of(dev, PACKAGE)
    evidence["main_pid"] = main_pid
    step("书籍已打开（主进程存活）", bool(main_pid), "pid=%s" % main_pid)
    if not main_pid:
        evidence["conclusion"] = "主进程未起来，无法进入听书中心"
        return dump(2)

    # 本轮起点必须在拉起听书中心**之前**取（否则极快的生成可能早于起点被漏判）
    start_epoch = int(dev.sh("date +%s").strip() or 0)
    evidence["start_epoch"] = start_epoch
    dev.sh("logcat -c")
    dev.sh("am start -n %s/%s" % (PACKAGE, CAST_ACTIVITY), root=True)
    step("进入听书中心", True, "")

    # 冷加载 30s+ 推理：轮询「本轮产生/覆盖的剧本文件」而不是固定等待
    started = time.time()
    llm_pid = None
    script_path = None
    while time.time() - started < args.timeout:
        if not llm_pid:
            llm_pid = pid_of(dev, "%s:llm" % PACKAGE)
        path, ts = newest_script(dev)
        if path and ts >= start_epoch:
            script_path = path
            break
        time.sleep(5)

    evidence["llm_pid"] = llm_pid
    evidence["elapsed_s"] = int(time.time() - started)
    step(":llm 推理进程已启动", bool(llm_pid), "pid=%s" % llm_pid)
    step("剧本已落盘", bool(script_path), script_path or "超时未生成")

    facts = script_facts(dev, script_path) if script_path else {}
    evidence["script_facts"] = facts
    step("剧本可解析", bool(facts) and "parseError" not in facts,
         json.dumps(facts, ensure_ascii=False))
    if facts:
        step("来源为端侧推理", facts.get("source") == "local-ai",
             "source=%s degraded=%s" % (facts.get("source"), facts.get("degraded")))

    # 诊断日志计数（含 LlmClient：连接是否建立、是否超时都在这条链上）
    trace = dev.sh("logcat -d | grep -c 'LlmTrace\\|LlmService\\|LlmClient' || true")
    evidence["llm_log_lines"] = trace.strip()
    step("推理链路有日志", (trace.strip() or "0").isdigit() and int(trace.strip() or 0) > 0,
         "lines=%s" % trace.strip())

    fatal = dev.sh("logcat -d | grep -c 'FATAL EXCEPTION' || true")
    evidence["fatal_count"] = int(fatal) if fatal.strip().isdigit() else 0
    step("无 FATAL 崩溃", evidence["fatal_count"] == 0, "count=%s" % evidence["fatal_count"])

    if not args.keep_prefs:
        stop_app(dev, PACKAGE)
        write_prefs(dev, original_prefs, owner)
        restored = read_prefs(dev)
        same = prefs_pairs(restored) == prefs_pairs(original_prefs)
        step("恢复原偏好", same, "已还原 %d 字节" % len(original_prefs))

    passed = bool(script_path) and (facts.get("source") == "local-ai") and evidence["fatal_count"] == 0
    evidence["result"] = "PASS" if passed else "FAIL"
    evidence["conclusion"] = (
        "端侧模型链路成立：本地 AI 生成剧本并落盘，来源 local-ai，无 FATAL" if passed
        else "未达标：见 steps（缺 ≥8GB 门槛/模型/书或推理超时）"
    )
    return dump(0 if passed else 1)


if __name__ == "__main__":
    sys.exit(main())
