#!/usr/bin/env python3
"""l2_verify_local_ai_entries.py —— 真机 L2：公共离线能力入口收敛（用户裁定 2026-11-10）。

被测点：
  1) 我的 → 工具：存在「本地模型（端侧）」与「声音与离线资源」两行；
  2) 点「本地模型（端侧）」→ 落 LlmSettingsActivity（本地 AI 设置页）；
  3) 点「声音与离线资源」→ 落 VoiceAssetCenterActivity（音色/离线模型/配乐/缓存）；
  4) 我的 → 工具 → AI 设置 → 页内可见「本地模型（端侧）」入口。

通道：走 ai_tests/scripts/adb360.py 裸协议（360 手机助手劫持 5037 场景，见
docs/project-rules/physical-device-testing.md §五）。

用法：ai_tests\\venv\\Scripts\\python.exe ai_tests/scripts/l2_verify_local_ai_entries.py [--serial 70cc13c8]
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb360 import RawAdb  # noqa: E402

PKG = "io.legado.miss.app.debug"
MAIN = "%s/io.legado.app.ui.main.MainActivity" % PKG
LLM_ACT = "LlmSettingsActivity"
VOICE_ACT = "VoiceAssetCenterActivity"
CONFIG_ACT = "ConfigActivity"
SHOT_DIR = os.path.join("output", "l3")


def top_activity(adb, serial):
    out = adb.shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'",
                    serial=serial, timeout=30.0)
    for line in out.splitlines():
        if PKG in line:
            return line.strip()
    return ""


def screen_size(adb, serial):
    out = adb.shell("wm size", serial=serial, timeout=15.0)
    for token in out.replace("Physical size:", " ").split():
        if "x" in token:
            try:
                w, h = [int(v) for v in token.split("x")]
                return w, h
            except ValueError:
                continue
    return 1080, 2400


def swipe_up(adb, serial, w, h):
    # 步长刻意取小（≈0.26 屏高）：大滑动会让目标行在两帧 dump 之间被跳过（实测「声音与离线资源」点空）
    adb.shell("input swipe %d %d %d %d 300" % (w // 2, int(h * 0.68), w // 2, int(h * 0.42)),
              serial=serial, timeout=30.0)
    time.sleep(1.0)


def wait_text_scroll(adb, serial, label, w, h, max_swipes=14):
    """先在当前屏找，找不到就小步上滑（设置页内容长，「工具」分组在下方）。

    找到后**再取一次坐标**：滑动惯性未停时 dump 的坐标可能已过期（实测「声音与离线资源」偶发点空）。
    命中位置贴底（y > 0.88h）时再多滑一点，避免点到导航栏。
    """
    for _ in range(max_swipes):
        xml = adb.dump_ui(serial=serial)
        node = adb.find_node(xml, label)
        if node:
            if node[1] > int(h * 0.88):
                swipe_up(adb, serial, w, h)
                continue
            time.sleep(0.8)
            node2 = adb.find_node(adb.dump_ui(serial=serial), label)
            return node2 or node
        swipe_up(adb, serial, w, h)
        time.sleep(0.5)
    return None


def wait_text(adb, label, serial, tries=6, delay=1.0):
    for _ in range(tries):
        xml = adb.dump_ui(serial=serial)
        node = adb.find_node(xml, label)
        if node:
            return node, xml
        time.sleep(delay)
    return None, ""


def tap_and_expect(adb, serial, label, expect, shot_name, w=1080, h=2400):
    node = wait_text_scroll(adb, serial, label, w, h)
    if not node:
        print("[FAIL] 未找到入口：%s" % label)
        return False
    adb.tap(node[0], node[1], serial=serial)
    time.sleep(2.5)
    top = top_activity(adb, serial)
    ok = expect in top
    os.makedirs(SHOT_DIR, exist_ok=True)
    adb.screenshot(os.path.join(SHOT_DIR, shot_name), serial=serial)
    print("[%s] %s → %s" % ("OK" if ok else "FAIL", label, top[:110] or "（未取到栈顶）"))
    return ok


def open_my_page(adb, serial):
    """冷启 → 切「我的」→ 回到列表顶部，保证每次查找起点一致（返回栈会重置滚动位置）。"""
    w, h = screen_size(adb, serial)
    adb.shell("am force-stop %s" % PKG, serial=serial, timeout=30.0)
    adb.shell("am start -W -n %s" % MAIN, serial=serial, timeout=60.0)
    time.sleep(3.5)
    node, _ = wait_text(adb, "我的", serial, tries=3)
    if node:
        adb.tap(node[0], node[1], serial=serial)
    else:
        adb.tap(int(w * 0.875), int(h * 0.958), serial=serial)
    time.sleep(2)
    # 回顶：连续下滑（内容区）
    for _ in range(3):
        adb.shell("input swipe %d %d %d %d 250" % (w // 2, int(h * 0.35), w // 2, int(h * 0.75)),
                  serial=serial, timeout=30.0)
        time.sleep(0.6)
    return w, h


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="")
    args = parser.parse_args()
    adb = RawAdb()
    serial = adb.resolve_serial(args.serial)

    results = []
    for label, expect, shot in (
        ("本地模型（端侧）", LLM_ACT, "k100_local_ai.png"),
        ("声音与离线资源", VOICE_ACT, "k100_voice_assets.png"),
        ("AI 设置", CONFIG_ACT, "k100_ai_settings.png"),
    ):
        w, h = open_my_page(adb, serial)
        results.append(tap_and_expect(adb, serial, label, expect, shot, w, h))
        if expect == CONFIG_ACT and results[-1]:
            node = wait_text_scroll(adb, serial, "本地模型（端侧）", w, h)
            print("[%s] AI 设置页内「本地模型（端侧）」入口" % ("OK" if node else "FAIL"))
            results.append(bool(node))

    print("\n结果：%d/%d 通过" % (sum(1 for r in results if r), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
