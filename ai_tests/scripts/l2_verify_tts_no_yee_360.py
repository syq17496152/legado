#!/usr/bin/env python3
"""l2_verify_tts_no_yee_360.py —— 真机 L2：句尾多余「耶」修复端到端（2026-11-10）。

缺陷：Kokoro 中文前端对词表外装饰符（～ ☆ # * · 等）不是静默丢弃，而是补一个音节，
      实测 ASR 转写为「的/道/到」类音，用户听成每句结尾一个「耶」。
修复：BuiltinSpeechTextSanitizer 在送引擎前剔除不可朗读字符（缓存升版 bt3）。

验证链路（真机真朗读，非单测）：
  STEP1 本地导入页选中含装饰符的测试 TXT 并放入书架
  STEP2 从导入页条目直接进阅读页（已在书架 ⇒ onItemClick 走 startRead）
  STEP3 阅读页菜单 → 朗读 → 开始朗读
  STEP4 logcat 断言：`builtin 文本清洗 removed=N` 出现且 N>0（证明清洗真的在真机生效）
  STEP5 logcat 断言：`builtin 合成完成` rc=0 且无 FATAL EXCEPTION

通道：adb360 裸协议（360 劫持 5037，见 docs/project-rules/physical-device-testing.md §五）。
用法：ai_tests\\venv\\Scripts\\python.exe ai_tests/scripts/l2_verify_tts_no_yee_360.py [--dump]
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb360 import open_adb  # noqa: E402

PKG = "io.legado.miss.app.debug"
MAIN = "%s/io.legado.app.ui.main.MainActivity" % PKG
SHOT_DIR = os.path.join("output", "l3")
TEST_FILE = "tts_yee_test.txt"
TEST_DIR = "/sdcard/02.阅读"
LOCAL_FILE = os.path.join(os.environ.get("TEMP", "."), TEST_FILE)

# 测试文本：正文里刻意放 ～ ☆ 等词表外装饰符，且句尾紧跟引号（复现用户描述的「一句话结尾」）
TEST_TEXT = (
    "第一章 标点测试\n\n"
    "艾伦说道：“这里的风有些冷～”\n\n"
    "凯尔道：“别急，先看看地图☆”\n\n"
    "艾伦：“我们得在天黑前赶到～”\n\n"
    "他笑了。\n\n"
    "“好吧。”凯尔点头。\n"
)


def dump_nodes(adb, serial):
    """返回 [(text, (x, y))]，只保留有 text/content-desc 且有 bounds 的节点。"""
    xml = adb.dump_ui(serial=serial)
    out = []
    for match in re.finditer(r"<node[^>]*?/?>", xml):
        tag = match.group(0)
        texts = re.findall(r'(?:text|content-desc)="([^"]*)"', tag)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not texts or not bounds:
            continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        for t in texts:
            if t.strip():
                out.append((t, ((x1 + x2) // 2, (y1 + y2) // 2)))
    return out


def find(adb, serial, label, tries=6, exact=False):
    for _ in range(tries):
        for text, xy in dump_nodes(adb, serial):
            if (text == label) if exact else (label in text):
                return xy
        time.sleep(1.0)
    return None


def top_activity(adb, serial):
    return adb.shell(
        "dumpsys activity activities | grep -E 'topResumedActivity'",
        serial=serial, timeout=30.0
    ).strip()


def ensure_test_file(adb, serial):
    """测试 TXT 推送到设备导入根目录（已存在且大小一致则跳过）。"""
    size = os.path.getsize(LOCAL_FILE)
    remote = "%s/%s" % (TEST_DIR, TEST_FILE)
    cur = adb.shell("stat -c %%s %s 2>/dev/null" % remote, serial=serial, timeout=20.0).strip()
    if cur == str(size):
        print("[OK] 测试文本已在设备：%s（%s 字节）" % (remote, size))
        return True
    adb.push(LOCAL_FILE, remote, serial=serial)
    print("[OK] 测试文本已推送：%s（%s 字节）" % (remote, size))
    return True


def open_import_page(adb, serial):
    """书架 tab → 菜单 → 添加本地 → ImportBookActivity。"""
    adb.shell("am force-stop %s" % PKG, serial=serial, timeout=30.0)
    adb.shell("am start -W -n %s" % MAIN, serial=serial, timeout=60.0)
    time.sleep(4)
    shelf = find(adb, serial, "书架", tries=6)
    if shelf:
        adb.tap(shelf[0], shelf[1], serial=serial)
        time.sleep(2.5)
    menu = find(adb, serial, "菜单", tries=5) or find(adb, serial, "更多", tries=2)
    if not menu:
        print("[FAIL] 书架页找不到「菜单」入口")
        return False
    adb.tap(menu[0], menu[1], serial=serial)
    time.sleep(2.0)
    add = find(adb, serial, "添加本地", tries=5) or find(adb, serial, "本地导入", tries=2)
    if not add:
        print("[FAIL] 菜单里找不到「添加本地」")
        return False
    adb.tap(add[0], add[1], serial=serial)
    time.sleep(4.0)
    top = top_activity(adb, serial)
    ok = "ImportBookActivity" in top
    print("[%s] 本地导入页：%s" % ("OK" if ok else "FAIL", top[:110]))
    return ok


def select_and_add(adb, serial, max_swipe=12):
    """在导入页滚动查找测试文件 → 点选 → 点「放入书架」。

    返回 "added"（本次新加入）/ "opened"（列表已刷新为在架，点击直接进阅读页）/ "fail"。
    """
    for _ in range(max_swipe):
        xy = find(adb, serial, TEST_FILE, tries=2)
        if xy:
            adb.tap(xy[0], xy[1], serial=serial)
            time.sleep(2.0)
            if "ReadBookActivity" in top_activity(adb, serial):
                print("[OK] 该书已在书架，点击直接进入阅读页")
                return "opened"
            confirm = find(adb, serial, "放入书架", tries=6)
            if not confirm:
                print("[FAIL] 选中后找不到「放入书架」")
                return "fail"
            adb.tap(confirm[0], confirm[1], serial=serial)
            time.sleep(3.0)
            print("[OK] 已放入书架：%s" % TEST_FILE)
            return "added"
        # 小步下滑（大跳会飘过条目）
        w, h = 1200, 2608
        adb.shell("input swipe %d %d %d %d 400" % (w // 2, int(h * 0.78), w // 2, int(h * 0.38)),
                  serial=serial, timeout=20.0)
        time.sleep(1.2)
    print("[FAIL] 导入页滚动到底仍未找到 %s" % TEST_FILE)
    return "fail"


def open_reader_from_shelf(adb, serial):
    """回书架 → 「全部」视图 → 按书名打开刚加入的测试书。

    注意：导入页条目的 isOnBookShelf 只在重新扫描时刷新（本轮实测点回调仍走 toggleSelect），
    所以「放入书架」后不能原地再点条目进阅读页，必须回书架点。
    """
    for _ in range(3):
        if "MainActivity" in top_activity(adb, serial):
            break
        adb.shell("input keyevent 4", serial=serial, timeout=20.0)
        time.sleep(2.0)
    all_chip = find(adb, serial, "全部", tries=3)
    if all_chip:
        adb.tap(all_chip[0], all_chip[1], serial=serial)
        time.sleep(3.0)
    title = TEST_FILE.rsplit(".", 1)[0]
    xy = find(adb, serial, title, tries=6)
    if not xy:
        print("[FAIL] 书架「全部」视图找不到 %s" % title)
        return False
    adb.tap(xy[0], xy[1], serial=serial)
    time.sleep(5.0)
    top = top_activity(adb, serial)
    ok = "ReadBookActivity" in top
    print("[%s] 已进入阅读页：%s" % ("OK" if ok else "FAIL", top[:110]))
    return ok


def start_read_aloud(adb, serial):
    """阅读页中央点击呼出菜单 → 朗读 → 播放（朗读面板）。"""
    w, h = 1200, 2608
    adb.shell("input tap %d %d" % (w // 2, h // 2), serial=serial, timeout=20.0)
    time.sleep(2.0)
    for label in ("朗读", "听书"):
        xy = find(adb, serial, label, exact=True, tries=2)
        if xy:
            adb.tap(xy[0], xy[1], serial=serial)
            time.sleep(3.0)
            start = (find(adb, serial, "播放", exact=True, tries=4)
                     or find(adb, serial, "开始朗读", exact=True, tries=2))
            if start:
                adb.tap(start[0], start[1], serial=serial)
                print("[OK] 已触发朗读（%s → 播放）" % label)
            else:
                print("[FAIL] 朗读面板里找不到「播放」")
                return False
            return True
    print("[FAIL] 阅读页菜单里找不到朗读入口")
    return False


def collect_logs(adb, serial, wait_s=14.0):
    """TtsTrace 关键行 + 全量 FATAL 行（FATAL 不在自定义 tag 下，必须单独抓）。"""
    time.sleep(wait_s)
    trace = adb.shell(
        "logcat -d -s TtsTrace:I | grep -E '文本清洗|合成完成' | tail -60",
        serial=serial, timeout=60.0
    )
    fatal = adb.shell(
        "logcat -d -b crash | grep -E 'FATAL EXCEPTION' | tail -20",
        serial=serial, timeout=60.0
    )
    return [l for l in trace.splitlines() if l.strip()], [l for l in fatal.splitlines() if l.strip()]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dump", action="store_true", help="只打印当前界面节点，不做流程")
    args = parser.parse_args()

    adb = open_adb()
    serial = adb.resolve_serial("")
    os.makedirs(SHOT_DIR, exist_ok=True)

    if args.dump:
        for text, xy in dump_nodes(adb, serial):
            print("%-30s %s" % (text[:30], xy))
        return 0

    if not os.path.exists(LOCAL_FILE):
        with open(LOCAL_FILE, "w", encoding="utf-8") as fh:
            fh.write(TEST_TEXT)
    ensure_test_file(adb, serial)

    results = []

    if not open_import_page(adb, serial):
        results.append(False)
        print("\n结果：FAIL（无法进入本地导入页）")
        return 1
    results.append(True)

    added = select_and_add(adb, serial)
    results.append(added != "fail")
    adb.screenshot(os.path.join(SHOT_DIR, "k100_tts_yee_import.png"), serial=serial)
    if added == "fail":
        print("\n结果：%d/%d 通过" % (sum(results), len(results)))
        return 1

    if added == "opened":
        opened = True
        print("[OK] 阅读页已就绪（跳过书架二次打开）")
    else:
        opened = open_reader_from_shelf(adb, serial)
    results.append(opened)
    if not opened:
        print("\n结果：%d/%d 通过" % (sum(results), len(results)))
        return 1

    adb.shell("logcat -c", serial=serial, timeout=30.0)
    started = start_read_aloud(adb, serial)
    results.append(started)
    adb.screenshot(os.path.join(SHOT_DIR, "k100_tts_yee_reading.png"), serial=serial)

    lines, fatal_lines = collect_logs(adb, serial)
    clean = [l for l in lines if "文本清洗" in l]
    done = [l for l in lines if "合成完成" in l and "rc=0" in l]
    fatal = list(fatal_lines)

    # removed=N 必须 >0（证明真机上装饰符被剔除）
    removed_ok = False
    for line in clean:
        m = re.search(r"removed=(\d+)", line)
        if m and int(m.group(1)) > 0:
            removed_ok = True
            break

    print("[%s] 文本清洗日志命中（removed>0）：%d 行" % ("OK" if removed_ok else "FAIL", len(clean)))
    print("[%s] 合成成功日志（rc=0）：%d 行" % ("OK" if done else "FAIL", len(done)))
    print("[%s] FATAL EXCEPTION：%d 行" % ("OK" if not fatal else "FAIL", len(fatal)))
    results += [removed_ok, bool(done), not fatal]

    print("\n结果：%d/%d 通过" % (sum(1 for r in results if r), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
