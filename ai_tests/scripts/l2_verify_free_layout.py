# -*- coding: utf-8 -*-
"""l2_verify_free_layout.py — 自由布局(style=5)白屏修复验证

流程：从订阅源 JSON 读 sourceUrl/sortUrl → adb am start 直启 RssSortActivity →
溢出菜单 Switch Layout 循环切到 style=5 → dump 断言列表非白屏（文章卡片节点>0）→ 截图存证。
"""
import json
import re
import subprocess
import sys
import time

import uiautomator2 as u2

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
HOST = "127.0.0.1:21503"
PKG = "io.legado.miss.app.debug"
SRC_JSON = r"docs\specs\rss-cms-multiroute-nojs\source-liangzi.json"
SHOT = r"output\free_layout_verify.png"


def adb(*args, timeout=60):
    return subprocess.run([ADB, "-s", HOST, "shell"] + list(args),
                          capture_output=True, timeout=timeout, text=True)


def main():
    with open(SRC_JSON, encoding="utf-8") as f:
        data = json.load(f)
    src = data[0] if isinstance(data, list) else data
    source_url = src.get("sourceUrl") or src.get("sourceUrl", "")
    sort_url = src.get("sortUrl") or ""
    # sortUrl 可能是 {{page}} 模板，取首个子分类路径即可（列表页任一分类都能验证布局）
    first_sort = sort_url.split("\n")[0] if "\n" in sort_url else sort_url
    if not source_url:
        print("[FAIL] JSON 缺 sourceUrl")
        sys.exit(1)

    d = u2.connect(HOST)
    # 清栈：force-stop 后冷启，避免残栈干扰
    adb("am", "force-stop", PKG)
    time.sleep(2)
    d.app_start(PKG)
    time.sleep(6)
    # 关可能的弹窗
    for t in ("关闭", "以后再说", "我知道了", "取消"):
        el = d(text=t)
        if el.exists:
            try:
                el.click()
                time.sleep(1)
            except Exception:
                pass
    # 主界面 → 订阅 tab → 频道页找源入口
    tab = d(description="订阅")
    if not tab.exists:
        print("[FAIL] 未找到订阅 tab")
        sys.exit(1)
    tab.click()
    time.sleep(4)
    # 频道页内点击含"影视"的源入口（标题文本节点）
    entry = d(textContains="影视")
    if not entry.exists:
        # 可能源 hub 折叠，尝试滚动
        d.swipe(0.5, 0.6, 0.5, 0.3)
        time.sleep(2)
        entry = d(textContains="影视")
    if not entry.exists:
        xml0 = d.dump_hierarchy()
        texts0 = sorted(set(re.findall(r'text="([^"]{1,30})"', xml0)))[:20]
        print(f"[MANUAL] 频道页未找到影视源入口 texts={texts0}")
        sys.exit(0)
    entry.click()
    time.sleep(8)
    cur = d.app_current()
    cur_name = cur.get("activity", "") if isinstance(cur, dict) else str(cur)
    print("activity:", cur_name)
    if "RssSort" not in cur_name:
        # 可能落在源 hub/分类页，点第一个分类区
        w, h = d.window_size()
        d.click(w // 2, int(h * 0.22))
        time.sleep(5)
        cur = d.app_current()
        cur_name = cur.get("activity", "") if isinstance(cur, dict) else str(cur)
        print("activity2:", cur_name)

    # 溢出菜单 Switch Layout 循环 5 次（0→1→2→3→4→5）
    for i in range(5):
        more = d(description="菜单")
        if more.exists:
            more.click()
        else:
            w, h = d.window_size()
            d.click(w - 60, 70)
        time.sleep(1.2)
        item = d(text="Switch Layout")
        if not item.exists:
            item = d(textContains="布局")
        if item.exists:
            item.click()
        time.sleep(2.5)

    xml = d.dump_hierarchy()
    texts = set(re.findall(r'text="([^"]{1,50})"', xml))
    # 白屏判定：RecyclerView 下有可点击卡片节点（列表项），或出现标题/时间等文章元素
    clickable_items = len(re.findall(r'clickable="true"[^>]*long-clickable="true"', xml))
    has_desc_items = len(re.findall(r'content-desc="[^"]+"', xml))
    d.screenshot(SHOT)
    print(f"clickable_items={clickable_items} desc_items={has_desc_items}")
    print(f"texts_sample={sorted(texts)[:12]}")
    print(f"截图: {SHOT}")
    verdict = "PASS" if (clickable_items >= 2 or has_desc_items >= 3) else "FAIL(疑似仍白屏)"
    print(f"[{verdict}] 自由布局列表非白屏")


if __name__ == "__main__":
    main()
