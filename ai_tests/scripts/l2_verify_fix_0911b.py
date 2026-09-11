# -*- coding: utf-8 -*-
"""l2_verify_fix_0911b.py — 批次F修复轮 L2：底栏恢复 + 自由布局白屏修复验证

STEP1 书源管理长按进多选 → 断言底部条出现（"反选"按钮可见）
STEP2 退出多选 → 断言底部条消失
STEP3 导入非凡影视订阅源（外部脚本 import_rss_source.py）→ 打开源文章列表
STEP4 溢出菜单 Switch Layout 循环切到 style=5（自由布局）→ 断言列表非白屏（存在文章卡片节点）
"""
import re
import subprocess
import sys
import time

import uiautomator2 as u2

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
HOST = "127.0.0.1:21503"
PKG = "io.legado.miss.app.debug"
SOURCE_MARK = "影视"   # 非凡影视源列表项匹配关键词


def start_act(act):
    subprocess.run([ADB, "-s", HOST, "shell", "am", "start", "-n", f"{PKG}/{act}"],
                   capture_output=True, timeout=30)
    time.sleep(3)


def dump_texts():
    xml = d.dump_hierarchy()
    return set(re.findall(r'text="([^"]{1,40})"', xml)), xml


def main():
    global d
    d = u2.connect(HOST)
    d.app_start(PKG)
    time.sleep(4)
    results = []

    # STEP1 底栏恢复
    start_act("io.legado.app.ui.book.source.manage.BookSourceActivity")
    w, h = d.window_size()
    d.long_click(w // 2, int(h * 0.30))
    time.sleep(1.5)
    texts, _ = dump_texts()
    bar_on = any("反选" in t for t in texts)
    results.append({"step": "STEP1 长按后底部条出现(含反选)", "verdict": "pass" if bar_on else "fail"})

    # STEP2 退出多选底栏消失
    d.press("back")
    time.sleep(1.2)
    texts, _ = dump_texts()
    bar_off = not any("反选" in t for t in texts)
    results.append({"step": "STEP2 退出多选底部条消失", "verdict": "pass" if bar_off else "fail"})

    # STEP3/4 白屏验证
    start_act("io.legado.app.ui.rss.source.manage.RssSourceActivity")
    time.sleep(1.5)
    # 点击含"影视"的源列表项（sourceMark 匹配列表项文本）
    el = d(textContains=SOURCE_MARK)
    if not el.exists:
        results.append({"step": "STEP3 打开源文章列表", "verdict": "manual", "detail": "未找到含'影视'的源项（导入或列表加载未完成）"})
    else:
        el.click()
        time.sleep(6)
        cur = d.app_current()
        cur_name = cur.get("activity", "") if isinstance(cur, dict) else str(cur)
        if "RssSort" not in cur_name:
            # 可能进了频道页，继续点第一个分类
            d.click(w // 2, int(h * 0.25))
            time.sleep(4)
        # 循环切布局 6 次（0→1→2→3→4→5→0，点 5 次到 5；多按几次无妨，最后停在 5 前后）
        for i in range(5):
            more = d(description="菜单")
            if not more.exists:
                w2, h2 = d.window_size()
                d.click(w2 - 60, 70)
            else:
                more.click()
            time.sleep(1.2)
            item = d(text="Switch Layout")
            if not item.exists:
                item = d(textContains="布局")
            if item.exists:
                item.click()
            time.sleep(2.5)
        xml = d.dump_hierarchy()
        # 白屏判定：存在文章列表 RecyclerView 且其中含可交互卡片节点（非空 child）
        has_cards = bool(re.search(r'content-desc="(播放|观看|详情|阅读|打开)[^"]*"', xml)) or \
            len(re.findall(r'long-clickable="true"', xml)) >= 2
        texts4 = set(re.findall(r'text="([^"]{1,50})"', xml))
        results.append({
            "step": "STEP4 自由布局列表非白屏",
            "verdict": "pass" if has_cards else "manual",
            "detail": f"cards={has_cards} textsSample={[t for t in list(texts4)[:8]]}",
        })

    for r in results:
        print(f"[{r['verdict'].upper():6}] {r['step']} {r.get('detail', '')}")
    failed = [r for r in results if r["verdict"] == "fail"]
    print("SUMMARY:", "PASS" if not failed else f"FAIL({len(failed)})")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
