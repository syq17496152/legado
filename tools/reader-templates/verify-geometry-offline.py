#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线「页根几何」自检：无设备环境下量页眉/页脚占位与正文区占比（R1/R2 判据前移）。

为什么需要
     R1/R2 的判据（`页根底 ≤ 视口底` / `正文区 ≥ 80%` / `页眉带高 ≤ 上限`）此前只能在真机/模拟器上量，
     迭代一轮要重新打包 + 装机（数分钟）⇒ 本脚本用 Chromium **复刻沙箱文档的注入顺序与页根结构**
     （`md-reader.css` → `template-archive-base.css` → `template-layout.css` → `template-decoration.css`
     → 主题 CSS），把同一批判据前移到**构建后即可跑的离线段**（秒级）。
     真机 L2（`ai_tests/scripts/l2_verify_template_page_frame.py`）仍是权威判据；本脚本只作**前置防线**。

口径（与真机自证串 `ctop`/`cbot` 同源，均为**未冻结的自然布局**）
     ctop  = 正文槽 rect.top  − 页根 rect.top     （页眉 + 纸页上边距/内边距）
     cbot  = 页根 rect.bottom − 正文槽 rect.bottom （纸页下内边距 + 页脚）
     正文区 = 视口高 − ctop − cbot

用法
     python tools/reader-templates/verify-geometry-offline.py            # 默认 480×854（本机 MEmu 口径）
     python tools/reader-templates/verify-geometry-offline.py --vp 900 --safe-top 0
     python tools/reader-templates/verify-geometry-offline.py --multi     # 多尺寸扫描（窄屏/高屏）
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
MD = REPO / "app/src/main/assets/md"
THEME = REPO / "app/src/main/assets/reader/builtin.dungeon-hud/readerTemplate.json"

# 正文区硬指标（design §5 / spec R1）
CONTENT_RATIO_MIN = 0.80
HEADER_LIMIT_RATIO = 0.20  # 页眉 + 页脚 ≤ 0.2 × 视口高

SAMPLE = (
    "夜色自拱顶的裂隙渗下，落成一束苍白的光。她把披风拢紧，指尖沿石壁划过——那道纹路仍在，一如多年前。"
)


def sandbox_html(template: dict, vw: int, vp: int, safe_top: int, paragraphs: int) -> str:
    css_parts = []
    for name in ("md-reader.css", "template-archive-base.css", "template-layout.css", "template-decoration.css"):
        path = MD / name
        if path.is_file():
            css_parts.append("/* %s */\n%s" % (name, path.read_text(encoding="utf-8")))
    css_parts.append("/* theme */\n%s" % template["css"])
    body = "".join('<p class="reader-paragraph">%s</p>' % SAMPLE for _ in range(paragraphs))
    html = template["firstPageHtml"].replace(
        'data-reader-flow="body" data-reader-flow-pagination="columns"></section>',
        'data-reader-flow="body" data-reader-flow-pagination="columns">%s</section>' % body,
    )
    return (
        "<!DOCTYPE html><html lang=\"zh-CN\" data-reader-theme=\"day\" data-rp-decoration=\"medium\" "
        "data-reader-motion=\"running\" style=\"--reader-safe-top:%dpx;--reader-safe-right:0px;"
        "--reader-safe-bottom:0px;--reader-safe-left:0px\">\n<head><meta charset=\"utf-8\">\n"
        "<style>%s</style></head>\n<body>\n<div id=\"reader-template-root\" data-reader-page=\"first\" "
        "data-reader-active=\"true\">%s</div>\n</body></html>"
    ) % (safe_top, "\n".join(css_parts), html)


MEASURE_JS = """
() => {
  const doc = document.documentElement;
  const root = document.getElementById('reader-template-root');
  const slot = document.querySelector('[data-reader-flow="body"]');
  const vp = doc.clientHeight, vw = doc.clientWidth;
  if (!root || !slot) return { error: 'missing root/slot', vp, vw };
  const rr = root.getBoundingClientRect();
  const sr = slot.getBoundingClientRect();
  const ctop = sr.top - rr.top;
  const cbot = rr.bottom - sr.bottom;
  return {
    vp, vw,
    rootTop: rr.top, rootBottom: rr.bottom, rootHeight: rr.height,
    slotTop: sr.top, slotBottom: sr.bottom, slotHeight: sr.height,
    ctop, cbot, content: vp - ctop - cbot,
    docScrollHeight: doc.scrollHeight
  };
}
"""


def measure(page, url: str) -> dict:
    page.goto(url, wait_until="load")
    page.wait_for_timeout(120)
    return page.evaluate(MEASURE_JS)


def run_case(vw: int, vp: int, safe_top: int, template: dict, paragraphs: int, out: Path) -> bool:
    from playwright.sync_api import sync_playwright

    out.write_text(sandbox_html(template, vw, vp, safe_top, paragraphs), encoding="utf-8")
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_page(viewport={"width": vw, "height": vp}, device_scale_factor=1)
        data = measure(page, out.as_uri())
        browser.close()

    if data.get("error"):
        print("[FAIL] %s" % data["error"])
        return False

    ratio = data["content"] / data["vp"]
    overflow = data["rootBottom"] - data["vp"]
    hdr_limit = HEADER_LIMIT_RATIO * data["vp"] - data["cbot"]
    ok = overflow <= 1 and ratio >= CONTENT_RATIO_MIN and data["ctop"] <= hdr_limit + 1
    print(
        "[%s] vw=%d vp=%d safe=%d | 页根底−视口=%+.1f ctop=%.1f cbot=%.1f 正文区=%.1f(%.1f%%) "
        "页眉上限=%.1f"
        % ("PASS" if ok else "FAIL", data["vw"], data["vp"], safe_top,
           overflow, data["ctop"], data["cbot"], data["content"], ratio * 100, hdr_limit)
    )
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vp", type=int, default=854)
    ap.add_argument("--vw", type=int, default=480)
    ap.add_argument("--safe-top", type=int, default=24, help="页根 safe-top（本机 MEmu 实测 24 CSS px）")
    ap.add_argument("--paragraphs", type=int, default=400, help="正文段数（须足以溢出多页）")
    ap.add_argument("--multi", action="store_true", help="多尺寸扫描（窄屏/高屏）")
    args = ap.parse_args()

    if not THEME.is_file():
        print("[缺产物] %s（先跑 build-dungeon-hud.py）" % THEME, file=sys.stderr)
        return 2
    template = json.loads(THEME.read_text(encoding="utf-8"))
    out = REPO / ".temp" / "offline-geometry.html"
    out.parent.mkdir(parents=True, exist_ok=True)

    cases = [(args.vw, args.vp, args.safe_top)]
    if args.multi:
        cases = [(360, 640, 0), (360, 640, 24), (480, 854, 0), (480, 854, 24), (480, 900, 0), (412, 915, 24)]

    results = [run_case(vw, vp, safe, template, args.paragraphs, out) for vw, vp, safe in cases]
    print("\n合计 %d/%d 通过" % (sum(results), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
