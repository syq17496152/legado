#!/usr/bin/env python3
"""l2_verify_voice_library_archive.py —— 真机 L2：音色库归档/去重/值域归一（2026-11-10）。

用户现场：282 个音色里 274 条是无语义序号名（艾舍尔 N / 珂珂 N），性别年龄中英混存，
池名把内部英文值（narration/youth）直接暴露，列表平铺无从下手。

验证点：
  STEP1 音色页出现归档入口：「整理音色库」「全部年龄」「未标年龄」
  STEP2 列表出现分组标题（本档可用 / 其他档位 · 男声/女声 · 年龄）
  STEP3 点「整理音色库」→ 出现「整理完成：共 N 个 …」回执（值域归一 + 去重收口可用）
  STEP4 池名不再暴露英文内部值（chips 不含 narration/youth/aged/teen 裸值）
  STEP5 无 FATAL

通道：adb360.open_adb（裸协议优先，不可用回落标准 adb）。
用法：ai_tests\\venv\\Scripts\\python.exe ai_tests/scripts/l2_verify_voice_library_archive.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb360 import open_adb  # noqa: E402

PKG = "io.legado.miss.app.debug"
BUILTIN = "%s/io.legado.app.ui.book.read.config.BuiltinTtsActivity" % PKG
SHOT_DIR = os.path.join("output", "l3")


def dump_nodes(adb, serial):
    xml = adb.dump_ui(serial=serial)
    out = []
    for match in re.finditer(r"<node[^>]*?/?>", xml):
        tag = match.group(0)
        texts = re.findall(r'(?:text|content-desc)="([^"]*)"', tag)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bounds:
            continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        for t in texts:
            if t.strip():
                out.append((t, ((x1 + x2) // 2, (y1 + y2) // 2)))
    return out


def find(adb, serial, label, tries=5, exact=False):
    for _ in range(tries):
        for text, xy in dump_nodes(adb, serial):
            if (text == label) if exact else (label in text):
                return xy, text
        time.sleep(1.0)
    return None, ""


def top_activity(adb, serial):
    return adb.shell(
        "dumpsys activity activities | grep -E 'topResumedActivity'",
        serial=serial, timeout=30.0
    ).strip()


def main():
    adb = open_adb()
    serial = adb.resolve_serial("")
    os.makedirs(SHOT_DIR, exist_ok=True)
    results = []

    adb.shell("am start -W -n %s --es page_tab voices" % BUILTIN, serial=serial, timeout=90.0)
    time.sleep(3.0)
    print("[OK] 音色页：%s" % top_activity(adb, serial)[:110])

    curate, _ = find(adb, serial, "整理音色库")
    age_all, _ = find(adb, serial, "全部年龄")
    unlabeled, _ = find(adb, serial, "未标年龄")
    print("[%s] 整理音色库按钮" % ("OK" if curate else "FAIL"))
    print("[%s] 年龄归档筛选（全部年龄/未标年龄）" % ("OK" if age_all and unlabeled else "FAIL"))
    results += [bool(curate), bool(age_all and unlabeled)]

    nodes = dump_nodes(adb, serial)
    texts = [t for t, _ in nodes]
    sections = [t for t in texts if re.search(r"(本档可用|其他档位) · ", t)]
    print("[%s] 归档分组标题：%s" % ("OK" if sections else "FAIL", sections[:4]))
    results.append(bool(sections))

    raw_pools = [t for t in texts if t in ("narration", "youth", "aged", "teen", "male", "female")]
    print("[%s] 池名无英文裸值（命中 %d）" % ("OK" if not raw_pools else "FAIL", len(raw_pools)))
    results.append(not raw_pools)

    adb.shell("logcat -c", serial=serial, timeout=30.0)
    if curate:
        adb.tap(curate[0], curate[1], serial=serial)
        time.sleep(6.0)
        msg, msg_text = find(adb, serial, "整理完成", tries=6)
        print("[%s] 整理回执：%s" % ("OK" if msg else "FAIL", msg_text or "（未出现）"))
        results.append(bool(msg))
    adb.screenshot(os.path.join(SHOT_DIR, "k100_voice_archive.png"), serial=serial)

    fatal = adb.shell(
        "logcat -d -b crash | grep -E 'FATAL EXCEPTION' | tail -5", serial=serial, timeout=60.0
    )
    fatal_lines = [l for l in fatal.splitlines() if l.strip()]
    print("[%s] FATAL EXCEPTION：%d 行" % ("OK" if not fatal_lines else "FAIL", len(fatal_lines)))
    results.append(not fatal_lines)

    print("\n结果：%d/%d 通过" % (sum(1 for r in results if r), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
