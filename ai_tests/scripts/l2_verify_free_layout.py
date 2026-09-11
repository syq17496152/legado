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
    # 清栈：force-stop 后 am start 直启 RssSortActivity（确定性入口，不依赖主界面导航路径）
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
    # am start 直启：initData 读 --es sourceUrl 从 rssSourceDao 取源（RssSortViewModel.kt:23）
    r = adb("am", "start", "-n",
            f"{PKG}/io.legado.app.ui.rss.article.RssSortActivity",
            "--es", "sourceUrl", source_url)
    print("am start:", (r.stdout or r.stderr).strip().splitlines()[-1] if (r.stdout or r.stderr) else "?")
    time.sleep(10)
    cur = d.app_current()
    cur_name = cur.get("activity", "") if isinstance(cur, dict) else str(cur)
    print("activity:", cur_name)
    if "RssSort" not in cur_name:
        print("[FAIL] 直启未进入 RssSortActivity")
        sys.exit(1)
    # 等文章列表首屏数据（网络拉取分类+文章）
    time.sleep(8)

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
            item = d(text="切换布局")
        if item.exists:
            item.click()
        else:
            print(f"[WARN] 第 {i + 1} 次未找到切换布局菜单项")
            d.press("back")
        time.sleep(2.5)
        time.sleep(4)  # 每次切换后等 fragment 重建+列表加载

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

    # ---- 滚动压测（crash-19-54-47 场景：滑动触发加载更多 → notifyDataSetChanged → 全量布局）----
    # 真机闪退路径：scrollVerticallyBy→fill→getViewForPosition(footer)→createViewHolder(单实例)
    # 断言：连续滑动 10 屏后前台存活 + logcat 无 FATAL + app crash 目录无新文件
    adb("logcat", "-c")
    crash_dir_before = set(crash_logs())
    for i in range(10):
        d.swipe(0.5, 0.75, 0.5, 0.15, duration=0.25)
        time.sleep(1.2)
    time.sleep(4)  # 等加载更多 + 全量布局完成
    cur = d.app_current()
    alive = PKG in (cur.get("package", "") if isinstance(cur, dict) else str(cur))
    fatal = adb("logcat", "-d", "-s", "AndroidRuntime:E", timeout=60).stdout
    fatal_lines = [ln for ln in fatal.splitlines() if "FATAL" in ln or "IllegalStateException" in ln]
    new_crash = crash_logs() - crash_dir_before
    d.screenshot(SHOT.replace(".png", "_scroll.png"))
    print(f"alive={alive} fatal_lines={fatal_lines[:3]} new_crash={sorted(new_crash)}")
    scroll_verdict = "PASS" if (alive and not fatal_lines and not new_crash) else "FAIL(滚动闪退复现)"
    print(f"[{scroll_verdict}] 自由布局滚动压测（10 屏含加载更多）")


def crash_logs():
    """列出应用 externalCache/crash 目录（CrashHandler 兜底落盘处），返回文件名集合"""
    r = adb("su", "-c",
            f"ls /storage/emulated/0/Android/data/{PKG}/cache/crash 2>/dev/null", timeout=30)
    return set(r.stdout.split())


if __name__ == "__main__":
    main()
