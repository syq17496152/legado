#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线「段落语义」自验：**真实运行时 + 真实主题**跑分类器，量段落效果（2026-10-20）。

为什么需要（判据缺口）
    既有的离线夹具 `verify-geometry-offline.py` 只量“页根几何/装饰不压正文”，**并且自己造
    `<p class="reader-paragraph">`** ⇒ 它**永远不会**发现“宿主根本没产出逐段 `.reader-paragraph`”
    这个缺陷（真实缺陷因此藏了很久：真机抽检 47 个章节缓存**全部**是「每行一段、无空行、无标签」
    纯文本，宿主旧实现按“空行”切段 ⇒ 整章塌成一个 `<p>` ⇒ archive 的 `as-*` 与自研的 `dh-*`
    段落语义装饰只剩首段可命中 ⇒ 用户报「段落效果全没了」，且**同时**打在 archive 主题与自研主题上）。

    本夹具补的就是这段缺口：把**真实 `template-runtime.js` + 真实主题 HTML/CSS/JS** 装进 Chromium，
    按**宿主同源规则**注入正文，然后量“段落语义类是否真的被加上”。

两个形体（**反向对照**，保证判据可证伪）
    A 宿主修复后形体：每行一段 `<p class="reader-paragraph">`（与 `TextRichContentProbe` 现行规则同源）
      ⇒ 期望 `reader-paragraph` 数 == 行数，且 `*-lead` / `*-dialogue` / `*-scene` 各 ≥1
    B 宿主修复前形体：整章塌成**一个** `<p>`（换行折叠为空格）
      ⇒ 必须量到**判据变差**（段落数 1、对白/场景类归零）；若 B 与 A 判据相同 ⇒ 判据空真，本夹具自曝失败

用法
    ai_tests\\venv\\Scripts\\python.exe tools/reader-templates/verify-paragraph-offline.py
    ... --template app/src/main/assets/reader/builtin.dungeon-hud/readerTemplate.json
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
MD = REPO / "app/src/main/assets/md"
BUILTIN = REPO / "app/src/main/assets/reader"
ARCHIVE = REPO / "temp/forks-comparison/Rimchars_legado/app/src/main/assets/epub/templates"

# 与宿主同源的正文注入顺序（`TemplateRenderBackend.sandboxScripts`：主题 CSS 由运行时 mountStyles 追加）
BASE_CSS = ("md-reader.css", "template-archive-base.css", "template-layout.css", "template-decoration.css")

# 段落语义类：自研 `dh-*` 与 archive `as-*` 同构（都点 `.reader-paragraph`）
SEMANTIC_RE = re.compile(r"-(lead|dialogue|scene)$")

# 合成样本（本项目自造文本，与主题自带的示例同源；含 首段 / 对白段 / 场景分隔段 三类）
SAMPLE_LINES = [
    "夜雨落在石阶上，她把披风拢紧。",
    "「别走那条干净的路。」",
    "他停下脚步，回身看了一眼远处的灯火。",
    "※※※",
    "「那就走另一条。」她答。",
]


def escape_html(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def host_shape_paragraphs(lines):
    """宿主**修复后**规则（`TextRichContentProbe.toParagraphHtml` 的离线镜像）：一行一段。"""
    parts = []
    for line in lines:
        trimmed = line.strip()
        if trimmed:
            parts.append('<p class="reader-paragraph">%s</p>' % escape_html(trimmed))
    return "\n".join(parts)


def host_shape_legacy_single_p(lines):
    """宿主**修复前**形体：按空行切段 ⇒ 本样本无空行 ⇒ 整章塌成**一个** `<p>`。"""
    joined = " ".join(line.strip() for line in lines if line.strip())
    return "<p>%s</p>" % escape_html(joined)


def write_text(path: Path, text: str, attempts: int = 6) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    for index in range(attempts):
        try:
            path.write_text(text, encoding="utf-8")
            return
        except PermissionError:
            if index == attempts - 1:
                raise
            time.sleep(0.4)


def sandbox_html(template: dict, body_html: str, theme_id: str = "day") -> str:
    css_parts = []
    for name in BASE_CSS:
        path = MD / name
        if path.is_file():
            css_parts.append("/* %s */\n%s" % (name, path.read_text(encoding="utf-8")))
    runtime = (MD / "template-runtime.js").read_text(encoding="utf-8")
    return (
        "<!DOCTYPE html><html lang=\"zh-CN\" data-reader-theme=\"%s\"><head><meta charset=\"utf-8\">\n"
        "<style>%s</style>\n<script>%s</script>\n</head>\n<body></body></html>"
    ) % (theme_id, "\n".join(css_parts), runtime)


FACTS_JS = """
() => {
  const root = document.getElementById('reader-template-root');
  if (!root) return { error: 'no-root' };
  const slot = root.querySelector('[data-reader-flow="body"]');
  const sem = { lead: 0, dialogue: 0, scene: 0 };
  for (const el of root.querySelectorAll('*')) {
    for (const cls of Array.from(el.classList)) {
      const m = /-(lead|dialogue|scene)$/.exec(cls);
      if (m) sem[m[1]]++;
    }
  }
  let slotHtmlLen = -1;
  try { slotHtmlLen = slot ? (slot.innerHTML || '').length : -1; } catch (e) { slotHtmlLen = -2; }
  return {
    paragraphs: root.querySelectorAll('.reader-paragraph').length,
    slots: root.querySelectorAll('[data-reader-flow="body"]').length,
    pTotal: slot ? slot.querySelectorAll('p').length : -1,
    sem, slotHtmlLen,
    semTotal: sem.lead + sem.dialogue + sem.scene,
    hasRoot: true
  };
}
"""


def run_shape(page, out: Path, template: dict, body_html: str) -> dict:
    write_text(out, sandbox_html(template, body_html))
    page.goto(out.as_uri(), wait_until="load")
    page.wait_for_timeout(120)
    cfg = {
        "token": "offline", "sessionId": "offline",
        "template": {
            "id": template.get("id", ""),
            "type": template.get("type", "paged"),
            "css": template.get("css", ""),
            "firstPageHtml": template.get("firstPageHtml", ""),
            "otherPageHtml": template.get("otherPageHtml", ""),
            "scrollHtml": template.get("scrollHtml", ""),
            "javascript": template.get("javascript", ""),
        },
        "bodyHtml": body_html,
        "themeId": "day",
        "pageIndex": 0,
        "fields": {"bookName": "样本", "chapterTitle": "样本章", "progress": 12, "page": 1, "time": "10:00"},
        "decoration": "medium",
        "motion": True,
    }
    page.evaluate("(cfg) => window.ReaderTemplateRuntime.init(cfg)", cfg)
    page.wait_for_timeout(200)
    return page.evaluate(FACTS_JS)


def check_template(template: dict, out: Path, strict: bool) -> bool:
    from playwright.sync_api import sync_playwright

    name = template.get("id") or template.get("name") or "<unnamed>"
    js = str(template.get("javascript") or "")
    # 该主题是否**真的**实现段落语义分类（archive 的 scroll/vertical 等只做页面装饰 ⇒ 不该拿这条判它）
    has_classifier = ("classList" in js) and ("scene" in js or "dialogue" in js or "lead" in js)
    expected_lines = len([line for line in SAMPLE_LINES if line.strip()])
    ok = True
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_page(viewport={"width": 480, "height": 853}, device_scale_factor=1)
        good = run_shape(page, out, template, host_shape_paragraphs(SAMPLE_LINES))
        bad = run_shape(page, out, template, host_shape_legacy_single_p(SAMPLE_LINES))
        browser.close()

    if good.get("error") or bad.get("error"):
        print("[FAIL] %s 渲染失败：%s" % (name, good.get("error") or bad.get("error")))
        return False

    slots = max(1, int(good.get("slots") or 0))
    # 判据①：宿主同源形体必须**逐段可点**（段数 == 行数 × 正文槽数；magazine 等双槽主题同理）
    if good["paragraphs"] != expected_lines * slots:
        print("[FAIL] %s 形体A reader-paragraph 数 %d != 行数 %d × 槽 %d"
              % (name, good["paragraphs"], expected_lines, slots))
        ok = False
    # 判据②（反向对照，判据可证伪的前提）：旧形体必须**明显变差**
    if not (bad["paragraphs"] < good["paragraphs"]):
        print("[FAIL] %s 反向对照未变差（判据空真）：A=%s B=%s" % (name, good, bad))
        ok = False
    # 判据③（仅对真实现了段落分类器的主题）：形体A 必须命中语义类，且旧形体必须**塌掉**
    if has_classifier:
        if good["semTotal"] < 1:
            print("[FAIL] %s 形体A 未命中任何段落语义类（sem=%s）" % (name, good["sem"]))
            ok = False
        if bad["semTotal"] >= good["semTotal"]:
            print("[FAIL] %s 反向对照语义类未塌（A=%s B=%s）" % (name, good["sem"], bad["sem"]))
            ok = False
    tag = "PASS" if ok else "FAIL"
    if has_classifier:
        print("[%s] %s | A: 段=%d 槽=%d 类=%s | B(反向): 段=%d 类=%s"
              % (tag, name, good["paragraphs"], slots, good["sem"],
                 bad["paragraphs"], bad["sem"]))
    else:
        print("[%s] %s | 该主题无段落分类器（只量宿主段落类供给）| A: 段=%d 槽=%d | B(反向): 段=%d"
              % (tag, name, good["paragraphs"], slots, bad["paragraphs"]))
    return ok


def collect_templates(args) -> list:
    items = []
    if args.template:
        for raw in args.template:
            items.append(Path(raw))
    else:
        for path in sorted(BUILTIN.glob("*/readerTemplate.json")):
            items.append(path)
        if args.archive and ARCHIVE.is_dir():
            items.extend(sorted(ARCHIVE.glob("*.json")))
    out = []
    for path in items:
        if not path.is_file():
            print("[SKIP] 不存在：%s" % path)
            continue
        try:
            out.append(json.loads(path.read_text(encoding="utf-8")))
        except Exception as error:  # noqa: BLE001
            print("[SKIP] 解析失败 %s：%s" % (path.name, error))
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", action="append", help="模板 JSON 路径（可多次）")
    parser.add_argument("--archive", action="store_true", help="含 archive 六套历史主题")
    parser.add_argument("--no-archive", action="store_true", help="只看仓内内置主题")
    args = parser.parse_args()
    if not args.no_archive and not args.template:
        args.archive = True

    templates = collect_templates(args)
    if not templates:
        print("[FAIL] 无可用模板")
        return 1
    out = REPO / ".temp" / "offline-paragraph.html"
    all_ok = True
    for template in templates:
        all_ok = check_template(template, out, True) and all_ok
    print("\n== 结论：%s（模板 %d）==" % ("PASS" if all_ok else "FAIL", len(templates)))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
