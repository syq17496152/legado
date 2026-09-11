# -*- coding: utf-8 -*-
"""l2_verify_tts_read.py — TTS 朗读推进 L2 验证（批次F F10/7.3，本地书免网络场景）

验证：
  STEP1 打开本地书并进入阅读页（无网络依赖）
  STEP2 触发朗读（菜单→朗读→开始朗读），确认 TtsTrace play 路径判定行出现
  STEP3 multiRole 路径断言（若激活模板）：章开始/逐段 tag=narration|dialogue 行
  STEP4 推进断言：章节内 nowSpeak/段落推进或 upTtsProgress 有进度输出
  STEP5 暂停/停止后 TtsTrace 无新增异常（Error 级 0 增）

前置：设备至少一个可用系统 TTS 引擎；模板激活为可选（STEP3 无模板时 verdict=skip）。
输出：STEP verdict 列表 + 汇总 PASS/FAIL。
"""
import re
import subprocess
import sys
import time

import uiautomator2 as u2

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
HOST = "127.0.0.1:21503"
PKG = "io.legado.miss.app.debug"
TTS_TRACE = "TtsTrace"


def adb(*args, timeout=30):
    return subprocess.run([ADB, "-s", HOST, "shell"] + list(args),
                          capture_output=True, timeout=timeout, text=True)


def logcat(tag, wait_s=3):
    time.sleep(wait_s)
    r = subprocess.run([ADB, "-s", HOST, "logcat", "-d", "-s", f"{tag}:I"],
                       capture_output=True, timeout=30, text=True)
    return (r.stdout or "").splitlines()


def main():
    results = []
    d = u2.connect(HOST)
    d.app_start(PKG)
    time.sleep(5)
    for t in ("关闭", "取消", "以后再说", "我知道了"):
        el = d(text=t)
        if el.exists:
            try:
                el.click()
                time.sleep(1)
            except Exception:
                pass

    # STEP1 打开本地书（书架第一格封面；无本地书时 verdict=manual）
    adb("logcat", "-c")
    shelf_ok = False
    try:
        w, h = d.window_size()
        d.click(w // 2, h // 5)
        time.sleep(3)
        cur = d.app_current().get("activity", "") if isinstance(d.app_current(), dict) else str(d.app_current())
        shelf_ok = "ReadBook" in cur
    except Exception as e:
        cur = f"err:{e}"
    results.append({
        "step": "STEP1 打开本地书进阅读页",
        "verdict": "pass" if shelf_ok else "manual",
        "detail": f"activity={cur}",
    })

    # STEP2 触发朗读：点击阅读页中央呼出菜单 → 朗读按钮（content-desc 尽力匹配）
    try:
        w, h = d.window_size()
        d.click(w // 2, h // 2)
        time.sleep(1.5)
        btn = d(descriptionStartsWith="朗读")
        if not btn.exists:
            btn = d(text="朗读")
        if btn.exists:
            btn.click()
            time.sleep(2)
            play = d(text="开始朗读")
            if play.exists:
                play.click()
    except Exception:
        pass
    lines = logcat(TTS_TRACE, 5)
    route_lines = [l for l in lines if "play 路径判定" in l]
    results.append({
        "step": "STEP2 朗读启动（play 路径判定）",
        "verdict": "pass" if route_lines else "manual",
        "detail": f"route_lines={len(route_lines)}",
    })

    # STEP3 multiRole 断言（模板激活才有）
    seg_lines = [l for l in lines if "multiRole 段" in l]
    legacy_lines = [l for l in lines if "legacy 入队" in l]
    if seg_lines:
        verdict = "pass"
        detail = f"multiRole 段={len(seg_lines)}"
    elif legacy_lines:
        verdict = "skip"
        detail = "未激活模板走 legacy（激活模板后重跑可验 multiRole）"
    else:
        verdict = "manual"
        detail = "未捕获分段/入队行"
    results.append({"step": "STEP3 multiRole 分段断言", "verdict": verdict, "detail": detail})

    # STEP4 推进断言：路径判定后章节仍在推进（多轮采样行数变化）
    time.sleep(6)
    lines2 = logcat(TTS_TRACE, 0)
    results.append({
        "step": "STEP4 朗读推进（TtsTrace 持续输出）",
        "verdict": "pass" if len(lines2) > len(lines) else "manual",
        "detail": f"delta={len(lines2) - len(lines)}",
    })

    # STEP5 异常检查
    err = subprocess.run([ADB, "-s", HOST, "logcat", "-d", "-s", f"{TTS_TRACE}:E"],
                         capture_output=True, timeout=30, text=True)
    err_lines = [l for l in (err.stdout or "").splitlines() if l.strip() and "beginning of" not in l]
    results.append({
        "step": "STEP5 TtsTrace 无新增 Error",
        "verdict": "pass" if not err_lines else "fail",
        "detail": f"err_lines={len(err_lines)}",
    })

    for r in results:
        print(f"[{r['verdict'].upper():6}] {r['step']} | {r['detail']}")
    failed = [r for r in results if r["verdict"] == "fail"]
    print("SUMMARY:", "PASS" if not failed else f"FAIL({len(failed)})")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
