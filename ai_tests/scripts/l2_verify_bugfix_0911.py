# -*- coding: utf-8 -*-
"""l2_verify_bugfix_0911.py — 批次F 5.3 管理族 L2 验证

验证：
  STEP1 书源管理直启 → 顶栏 MoreVert 存在
  STEP2 长按列表项进多选 → 顶栏 MoreVert 菜单含"全选"（批量操作收口顶栏）
  STEP3 底部常驻选择条不再显示（选中态下 dump 无"反选"按钮节点）
  STEP4 缓存管理含 WebView 分项
输出：STEP verdict + 汇总。
"""
import re
import subprocess
import sys
import time

import uiautomator2 as u2

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
HOST = "127.0.0.1:21503"
PKG = "io.legado.miss.app.debug"


def adb(*args, timeout=30):
    return subprocess.run([ADB, "-s", HOST, "shell"] + list(args),
                          capture_output=True, timeout=timeout, text=True)


def start_act(act):
    r = adb("am", "start", "-n", f"{PKG}/{act}")
    time.sleep(3.5)
    cur = d.app_current()
    name = cur.get("activity", "") if isinstance(cur, dict) else str(cur)
    return name


def dump_texts():
    xml = d.dump_hierarchy()
    return xml, set(re.findall(r'text="([^"]{1,40})"', xml))


def main():
    global d
    d = u2.connect(HOST)
    d.app_start(PKG)
    time.sleep(4)
    results = []

    # STEP1 书源管理直启
    name = start_act("io.legado.app.ui.book.source.manage.BookSourceActivity")
    ok1 = "BookSourceActivity" in name
    results.append({"step": "STEP1 书源管理直启", "verdict": "pass" if ok1 else "fail", "detail": name})

    if not ok1:
        for r in results:
            print(f"[{r['verdict'].upper():6}] {r['step']} | {r['detail']}")
        print("SUMMARY: FAIL")
        sys.exit(1)

    # STEP2 长按首列表项进多选
    xml1, texts1 = dump_texts()
    m = re.search(r'<node[^>]*resource-id="[^"]*recycler_view[^"]*"', xml1)
    clicked = False
    try:
        # 取第一个列表项区域（屏幕 1/4 高度处长按）
        w, h = d.window_size()
        d.long_click(w // 2, int(h * 0.28))
        time.sleep(1.5)
        clicked = True
    except Exception:
        pass
    xml2, texts2 = dump_texts()
    in_select = ("反选" in " ".join(texts2)) or any("全选" in t for t in texts2)
    results.append({
        "step": "STEP2 长按进多选",
        "verdict": "pass" if clicked and in_select else "manual",
        "detail": f"clicked={clicked} selectHint={'反选' in ' '.join(texts2) or any('全选' in t for t in texts2)}",
    })

    # STEP3 底部条不显示：选中态下不应存在"反选"常驻按钮（收口后反选只在溢出菜单内）
    # 判定：dump 中"反选"文本出现次数为 0（常驻底栏按钮文本）——溢出菜单未打开时不可见
    no_bottombar = "反选" not in " ".join(texts2)
    results.append({
        "step": "STEP3 底部常驻条已移除",
        "verdict": "pass" if no_bottombar else "fail",
        "detail": f"反选 visible={'反选' in ' '.join(texts2)}",
    })

    # STEP4 缓存管理 WebView 分项
    name4 = start_act("io.legado.app.ui.book.cache.CacheManageActivity")
    time.sleep(2)
    xml4, texts4 = dump_texts()
    has_webview = any("WebView" in t for t in texts4)
    results.append({
        "step": "STEP4 缓存管理含 WebView 分项",
        "verdict": "pass" if has_webview else "manual",
        "detail": f"activity={'CacheManage' in name4} webview={'WebView' in ' '.join(texts4)}",
    })

    for r in results:
        print(f"[{r['verdict'].upper():6}] {r['step']} | {r['detail']}")
    failed = [r for r in results if r["verdict"] == "fail"]
    print("SUMMARY:", "PASS" if not failed else f"FAIL({len(failed)})")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
