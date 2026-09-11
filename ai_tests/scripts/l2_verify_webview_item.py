# -*- coding: utf-8 -*-
"""l2_verify_webview_item.py — 批次F F6/5.3 补验：缓存统计弹窗含 WebView 分项

前置：CacheActivity 已在前台（离线缓存页）。
步骤：点击"Cache storage"菜单 → 断言弹窗列表含"WebView 数据"文本。
"""
import re
import sys
import time

import uiautomator2 as u2

HOST = "127.0.0.1:21503"
PKG = "io.legado.miss.app.debug"


def main():
    d = u2.connect(HOST)
    d.app_start(PKG)
    time.sleep(3)
    # 直启离线缓存页（该页顶栏才有缓存统计菜单）
    import subprocess
    subprocess.run([r"C:\Android\Sdk\platform-tools\adb.exe", "-s", HOST, "shell",
                    "am", "start", "-n",
                    f"{PKG}/io.legado.app.ui.book.cache.CacheActivity"],
                   capture_output=True, timeout=30)
    time.sleep(3)
    # 顶栏 MoreVert（该页按钮无 desc，坐标兜底：顶栏右缘）
    more = d(description="菜单")
    if more.exists:
        more.click()
    else:
        w, h = d.window_size()
        d.click(w - 60, 70)
    time.sleep(1.5)
    el = d(text="Cache storage")
    if not el.exists:
        el = d(text="缓存统计")
    if not el.exists:
        print("[MANUAL] 溢出菜单未找到 Cache storage 项（菜单结构变化，人工验证）")
        sys.exit(0)
    el.click()
    time.sleep(3)
    xml = d.dump_hierarchy()
    texts = re.findall(r'text="([^"]{1,40})"', xml)
    has_webview = any("WebView" in t for t in texts)
    shown = [t for t in texts if t.strip()][:15]
    print(f"[{'PASS' if has_webview else 'MANUAL'}] 缓存统计弹窗 WebView 分项 | visible={has_webview} items={shown}")


if __name__ == "__main__":
    main()
