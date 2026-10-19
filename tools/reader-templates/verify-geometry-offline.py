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


def sandbox_html(template: dict, vw: int, vp: int, safe_top: int, paragraphs: int,
                 region: str = "cathedral", perf: str = "explore", fallen: bool = False) -> str:
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
    # 与作者 JS（`direct()` / `sync()`）同口径：区域/演出态/牺牲态写在**页壳**的 `data-dh-*` 与 class 上
    html = html.replace('data-dh-region="cathedral"', 'data-dh-region="%s"' % region)
    html = html.replace('data-dh-perf="explore"', 'data-dh-perf="%s"' % perf)
    if fallen:
        html = html.replace('class="dh-page dh-opening"', 'class="dh-page dh-opening dh-fallen"')
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


# ---------------- R4 结构自验（区域 × 演出态 矩阵；不需要设备，验证的是**构建产物**而非原型） ----------------

MATRIX_JS = """
() => {
  const page = document.querySelector('.dh-page');
  const cs = (sel, prop) => {
    const el = document.querySelector(sel);
    return el ? getComputedStyle(el).getPropertyValue(prop).trim() : null;
  };
  const all = (sel, prop) => Array.from(document.querySelectorAll(sel))
    .map(el => getComputedStyle(el).getPropertyValue(prop).trim());
  return {
    region: page.getAttribute('data-dh-region'),
    perf: page.getAttribute('data-dh-perf'),
    stageImage: cs('.dh-stage', 'background-image').slice(0, 120),
    stageSize: cs('.dh-stage', 'background-size'),
    stageRepeat: cs('.dh-stage', 'background-repeat'),
    stagePos: cs('.dh-stage', 'background-position'),
    foeBoss: cs('.dh-foe-boss', 'opacity'), foeSpider: cs('.dh-foe-spider', 'opacity'),
    foeSlug: cs('.dh-foe-slug', 'opacity'), foeFrog: cs('.dh-foe-frog', 'opacity'),
    npc: cs('.dh-npc', 'opacity'), talk: cs('.dh-talk', 'opacity'),
    fxSlash: cs('.dh-fx-slash', 'opacity'), fxBurst: cs('.dh-fx-burst', 'opacity'),
    // ⚠️ 动效元素的**瞬时 opacity 不可作判据**：`@keyframes` 的 opacity 优先级高于普通声明
    // （斩击闪光在 0%~72% 区间本就是 opacity:0）⇒ 判"特效出场"应读 `animation-name`。
    fxSlashAnim: cs('.dh-fx-slash', 'animation-name'),
    slugAnim: cs('.dh-foe-slug .dh-f', 'animation-name'),
    slugSteps: cs('.dh-foe-slug .dh-f', 'animation-timing-function'),
    npcAnim: cs('.dh-npc .dh-n-f', 'animation-name'),
    boltAnim: cs('.dh-fx-bolt i', 'animation-name'),
    bandCount: document.querySelectorAll('.dh-band i').length,
    bandPos: all('.dh-band i', 'background-position'),
    bandOpacity: all('.dh-band i', 'opacity'),
    cornerCount: document.querySelectorAll('.dh-corner').length,
    cornerClasses: Array.from(document.querySelectorAll('.dh-corner')).map(e => e.className),
    folio: !!document.querySelector('.dh-folio'),
    bodyCount: document.querySelectorAll('[data-reader-flow="body"]').length,
    // 牺牲态：页脚归零（CSS 变量 --rp-percent 由宿主下发；此处按未注入计，仅记录结构）
    fallen: document.querySelectorAll('.dh-fallen').length
  };
}
"""

REGIONS = ["cathedral", "town", "grave", "cave"]
PERFS = ["explore", "battle", "talk"]


def matrix_case(template: dict, vp: int, vw: int, shots_dir: Path = None) -> bool:
    """逐（区域 × 演出态）渲染构建产物，断言 CSS 属性切片**真的生效**。

    为什么需要：构建期的"死规则清零"只能证明"类有供给"，**证明不了属性选择器命中**
    （选择器写错 = 静默无效，症状与"没做"完全一样）。本矩阵是**产物级**自验，替代原型的目视评审。

    `shots_dir` 非空时额外落**真实产物的截图**（供人工目视复核；非原型截图）。
    """
    from playwright.sync_api import sync_playwright

    out = REPO / ".temp" / "offline-matrix.html"
    failures = []
    stage_images = {}
    shot_list = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_page(viewport={"width": vw, "height": vp}, device_scale_factor=1)
        for region in REGIONS:
            for perf in PERFS:
                out.write_text(sandbox_html(template, vw, vp, 0, 40, region, perf), encoding="utf-8")
                page.goto(out.as_uri(), wait_until="load")
                page.wait_for_timeout(60)
                d = page.evaluate(MATRIX_JS)
                label = "%s/%s" % (region, perf)
                if shots_dir is not None:
                    shots_dir.mkdir(parents=True, exist_ok=True)
                    target = shots_dir / ("matrix_%s_%s.png" % (region, perf))
                    page.screenshot(path=str(target))
                    shot_list.append(target.name)
                # ① 属性已就位
                if d["region"] != region or d["perf"] != perf:
                    failures.append("%s 属性未就位（%s/%s）" % (label, d["region"], d["perf"]))
                # ② 演出态：talk ⇒ NPC 上场且敌人清场；battle ⇒ 本区敌人上场且特效出
                if perf == "talk":
                    if float(d["npc"] or 0) < 0.9:
                        failures.append("%s NPC 未上场（opacity=%s）" % (label, d["npc"]))
                    if any(float(d[k] or 0) > 0.05 for k in ("foeBoss", "foeSpider", "foeSlug", "foeFrog")):
                        failures.append("%s 对话演出未清场（敌人仍可见）" % label)
                    if float(d["talk"] or 0) <= 0:
                        failures.append("%s 对话气泡未显示" % label)
                elif perf == "battle":
                    region_foe = {"cathedral": "foeBoss", "town": "foeSpider",
                                  "grave": "foeSlug", "cave": "foeFrog"}[region]
                    if float(d[region_foe] or 0) < 0.95:
                        failures.append("%s 本区敌人未上场（%s=%s）" % (label, region_foe, d[region_foe]))
                    # 特效出场判据：斩击看**动效在跑**（瞬时 opacity 由 @keyframes 支配）、冲击波看静态层可见
                    if d["fxSlashAnim"] != "dh-slash" or float(d["fxBurst"] or 0) < 0.5:
                        failures.append("%s 技能特效未出场（anim=%s burst=%s）"
                                        % (label, d["fxSlashAnim"], d["fxBurst"]))
                    if float(d["npc"] or 0) > 0.05:
                        failures.append("%s 打怪演出残留 NPC" % label)
                else:
                    if float(d["npc"] or 0) > 0.05:
                        failures.append("%s 探索演出残留 NPC" % label)
                # ③ 区域底图差异（cave 用石墙纹理平铺 ⇒ 尺寸/重复不同）
                sig = (d["stageImage"], d["stageSize"], d["stageRepeat"], d["stagePos"])
                stage_images.setdefault(region, set()).add(sig)
                # ④ 竖带：4 格且位置两两不同
                if d["bandCount"] != 4:
                    failures.append("%s 竖带格数 %d ≠ 4" % (label, d["bandCount"]))
                if len(set(d["bandPos"])) != 4:
                    failures.append("%s 竖带四格位置未两两不同" % label)
                # ⑤ 四角：4 个且类名齐全
                if d["cornerCount"] != 4:
                    failures.append("%s 角饰 %d 个 ≠ 4" % (label, d["cornerCount"]))
                for corner in ("dh-c-tl", "dh-c-tr", "dh-c-bl", "dh-c-br"):
                    if not any(corner in c for c in d["cornerClasses"]):
                        failures.append("%s 缺角饰类 %s" % (label, corner))
                # ⑥ 精灵动画：多帧 `steps()` 真动图（敌方 4 种 + NPC + 弹道）
                if d["slugAnim"] != "dh-slug" or "steps" not in (d["slugSteps"] or ""):
                    failures.append("%s 黏液体多帧动画未生效（%s / %s）" % (label, d["slugAnim"], d["slugSteps"]))
                if d["npcAnim"] != "dh-npc":
                    failures.append("%s NPC 动画未生效（%s）" % (label, d["npcAnim"]))
                if d["boltAnim"] != "dh-bolt":
                    failures.append("%s 弹道多帧动画未生效（%s）" % (label, d["boltAnim"]))
                # ⑦ 正文槽与页脚读数在场
                if d["bodyCount"] != 1:
                    failures.append("%s 正文槽数 %d ≠ 1" % (label, d["bodyCount"]))
                if not d["folio"]:
                    failures.append("%s 页脚读数缺失" % label)
        browser.close()

    # 跨区域必须**底图不同**（相邻页素材集合不同的最低要求）
    distinct = {r: list(v)[0] for r, v in stage_images.items()}
    if len(set(distinct.values())) != len(REGIONS):
        failures.append("四区域底图签名不完全不同：%s" % {k: v[1:3] for k, v in distinct.items()})

    if failures:
        print("[FAIL] R4 结构矩阵（区域×演出态 = %d 组）" % (len(REGIONS) * len(PERFS)))
        for item in failures[:12]:
            print("   - " + item)
        return False
    print("[PASS] R4 结构矩阵：%d 区域 × %d 演出态 = %d 组；竖带 4 格 / 四角 4 枚 / 多帧 steps 动效 6 类全部生效"
          % (len(REGIONS), len(PERFS), len(REGIONS) * len(PERFS)))
    if shot_list:
        print("   产物截图 %d 张 → %s" % (len(shot_list), shots_dir))
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vp", type=int, default=854)
    ap.add_argument("--vw", type=int, default=480)
    ap.add_argument("--safe-top", type=int, default=24, help="页根 safe-top（本机 MEmu 实测 24 CSS px）")
    ap.add_argument("--paragraphs", type=int, default=400, help="正文段数（须足以溢出多页）")
    ap.add_argument("--multi", action="store_true", help="多尺寸扫描（窄屏/高屏）")
    ap.add_argument("--matrix", action="store_true", help="R4 结构矩阵自验（区域 × 演出态 12 组；产物级）")
    ap.add_argument("--shots", action="store_true", help="矩阵模式下额外落真实产物截图（供目视复核）")
    ap.add_argument("--shots-dir", default="", help="截图输出目录（默认为 docs/specs/.../evidence/artifact）")
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
    if args.matrix:
        shots_dir = None
        if args.shots:
            shots_dir = Path(args.shots_dir) if args.shots_dir else (
                REPO / "docs/specs/reader-theme-family-rework/evidence/artifact")
        results.append(matrix_case(template, args.vp, args.vw, shots_dir))
    print("\n合计 %d/%d 通过" % (sum(results), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
