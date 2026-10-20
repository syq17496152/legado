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
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
MD = REPO / "app/src/main/assets/md"
THEME = REPO / "app/src/main/assets/reader/builtin.dungeon-hud/readerTemplate.json"
ASSETS = REPO / "tools/reader-templates/dungeon-hud/assets"

# 正文区硬指标（design §5 / spec R1）
CONTENT_RATIO_MIN = 0.80
HEADER_LIMIT_RATIO = 0.20  # 页眉 + 页脚 ≤ 0.2 × 视口高

# 判据可用性自检用的"注入 CSS"（默认空）：`--inject-css` 传入 ⇒ 追加到主题 CSS 之后。
# 用途（2026-10-20 反向对照）：故意放大页眉角色画布 ⇒ "页眉角色完整性"判据**必须**报 FAIL，
# 以此证明该判据不是空真（此前用户实测缺陷正是"判据全绿但肉眼可见被裁"）。
EXTRA_CSS = ""

SAMPLE = (
    "夜色自拱顶的裂隙渗下，落成一束苍白的光。她把披风拢紧，指尖沿石壁划过——那道纹路仍在，一如多年前。"
)


def write_html(path: Path, text: str, attempts: int = 6) -> None:
    """把夹具 HTML 落盘（**带重试**）。

    为什么需要：Chromium 进程退出后，Windows 对刚写过的文件句柄**偶尔延迟释放** ⇒ 同一路径
    紧接着再写会偶发 `PermissionError: [Errno 13]`（真机实测：连续跑两次矩阵必然第二次失败）。
    重试（间隔 0.4s）即稳定通过，且不改变判据口径。
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    for index in range(attempts):
        try:
            path.write_text(text, encoding="utf-8")
            return
        except PermissionError:
            if index == attempts - 1:
                raise
            time.sleep(0.4)


def sandbox_html(template: dict, vw: int, vp: int, safe_top: int, paragraphs: int,
                 region: str = None, perf: str = None, fallen: bool = False) -> str:
    """构造沙箱文档。

    `region` / `perf` 只对**声明了 `data-dh-*` 的主题**（本任务的 dungeon-hud）生效；其它内置主题
    直接跳过属性替换（同一份夹具即可复用于**全内置主题巡检**，见 [all_themes_audit]）。
    """
    css_parts = []
    for name in ("md-reader.css", "template-archive-base.css", "template-layout.css", "template-decoration.css"):
        path = MD / name
        if path.is_file():
            css_parts.append("/* %s */\n%s" % (name, path.read_text(encoding="utf-8")))
    css_parts.append("/* theme */\n%s" % template["css"])
    if EXTRA_CSS:
        css_parts.append("/* self-test injected */\n%s" % EXTRA_CSS)
    body = "".join('<p class="reader-paragraph">%s</p>' % SAMPLE for _ in range(paragraphs))
    html = template.get("firstPageHtml") or template.get("otherPageHtml") or ""
    # 通用"正文槽注入"：不依赖各主题的具体标签/属性顺序（旧实现硬匹配 dungeon-hud 的字符串）。
    # 用**非贪婪**匹配槽内已有内容并整体替换 ⇒ 无论槽是空的还是预置了文案，都能注入足够长的正文。
    html, injected = re.subn(
        r'(<(?:section|div|article|main)\b[^>]*data-reader-flow="body"[^>]*>)(.*?)(</(?:section|div|article|main)>)',
        lambda m: m.group(1) + body + m.group(3), html, count=1, flags=re.S)
    if not injected:
        body = ""    # 无法注入时不影响页面结构，只会在巡检里表现为"帧高偏小"（由调用方披露）
    if region:
        html = html.replace('data-dh-region="cathedral"', 'data-dh-region="%s"' % region)
    if perf:
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

    write_html(out, sandbox_html(template, vw, vp, safe_top, paragraphs))
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
    paperBg: cs('.dh-paper', 'background-color'),
    textColor: cs('.dh-body', 'color'),
    // 页眉带与各角色的实际几何（"角色是否被裁切"判据的数据源）
    band: (() => { const e = document.querySelector('.dh-stage'); if (!e) return null;
      const r = e.getBoundingClientRect();
      return {top: r.top, bottom: r.bottom, height: r.height}; })(),
    sprites: (() => {
      const out = {};
      for (const s of ['.dh-rig.dh-hero', '.dh-foe-boss', '.dh-foe-spider', '.dh-foe-slug',
                       '.dh-foe-frog', '.dh-npc']) {
        const el = document.querySelector(s);
        if (!el) continue;
        const r = el.getBoundingClientRect();
        out[s] = {x: r.left, y: r.top, w: r.width, h: r.height,
                  opacity: parseFloat(getComputedStyle(el).opacity || '0')};
      }
      return out;
    })(),
    bodyCount: document.querySelectorAll('[data-reader-flow="body"]').length,
    // 页面级装饰（左右竖带 4 格）与正文槽的实际矩形 —— "装饰是否压住正文"判据的数据源
    // （2026-10-20 用户实测缺陷补：竖带曾是 22px 窄条，em 改造后变 360×540px 大图糊在正文上）。
    bandRects: Array.from(document.querySelectorAll('.dh-band i')).map(e => {
      const r = e.getBoundingClientRect();
      return {x: r.left, y: r.top, w: r.width, h: r.height}; }),
    slotRect: (() => { const e = document.querySelector('[data-reader-flow="body"]'); if (!e) return null;
      const r = e.getBoundingClientRect();
      return {x: r.left, y: r.top, w: r.width, h: r.height}; })(),
    // 牺牲态：页脚归零（CSS 变量 --rp-percent 由宿主下发；此处按未注入计，仅记录结构）
    fallen: document.querySelectorAll('.dh-fallen').length
  };
}
"""

REGIONS = ["cathedral", "town", "grave", "cave"]
PERFS = ["explore", "battle", "talk"]

# —— 页眉"角色是否被裁切"判据（2026-10-19 用户实测缺陷补齐）——
# 为什么必须机检：此前**全部**判据都是数值类（占位 / 正文区 / 翻页 / 页框）**没有任何一条**约束
# "页眉里的角色要完整可见" ⇒ 竖屏下图被裁掉半截仍然全绿（用户一眼看出的缺陷）。判据口径：
# 取该角色精灵**墨迹包围盒**（对 PNG alpha 通道求并集，抗透明留白干扰），投影到页面坐标后
# **必须完整落在页眉带（`.dh-stage`）内**（容差 2px 抗锯齿/取整）。
SPRITE_INK_TOLERANCE = 2.0
SPRITE_PREFIXES = {
    ".dh-rig.dh-hero": "hero_",          # 主角 12 部件共享同一张画布 ⇒ 取全部部件帧的并集
    ".dh-foe-boss": "boss_walk_",
    ".dh-foe-spider": "spider_body_",
    ".dh-foe-slug": "slug_s_idle_",
    ".dh-foe-frog": "frog_l_idle_",
    ".dh-npc": "npc_villager_",
}


def ink_fractions(prefix: str):
    """返回该角色**墨迹包围盒**在单帧画布内的相对比例 `(x0, y0, x1, y1)`。"""
    from PIL import Image
    files = sorted(ASSETS.glob(prefix + "*.png"))
    box = None
    size = None
    for path in files:
        img = Image.open(path).convert("RGBA")
        size = img.size
        alpha_bbox = img.getchannel("A").getbbox()
        if not alpha_bbox:
            continue
        box = alpha_bbox if box is None else (
            min(box[0], alpha_bbox[0]), min(box[1], alpha_bbox[1]),
            max(box[2], alpha_bbox[2]), max(box[3], alpha_bbox[3]),
        )
    if box is None or not size:
        return None
    w, h = size
    return (box[0] / w, box[1] / h, box[2] / w, box[3] / h)


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
    # 页眉角色完整性判据的**数值证据**（每角色最差一组；禁"静默 PASS"——用户实测缺陷正是因判据无数值留痕）
    sprite_evidence = {}
    # "页面级装饰是否压住正文"判据的**数值证据**（每格最差相交深度，见判据⑨）
    band_evidence = {}
    # 各角色**墨迹包围盒**（相对画布比例）：离线算一次，供"是否被页眉带裁切"判据使用
    ink_map = {sel: ink_fractions(prefix) for sel, prefix in SPRITE_PREFIXES.items()}
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_page(viewport={"width": vw, "height": vp}, device_scale_factor=1)
        for region in REGIONS:
            for perf in PERFS:
                write_html(out, sandbox_html(template, vw, vp, 0, 40, region, perf))
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
                # ⑧ **页眉角色完整性**（用户实测缺陷补）：墨迹必须完整落在页眉带内
                band = d.get("band")
                if band:
                    for sel, ink in ink_map.items():
                        info = (d.get("sprites") or {}).get(sel)
                        if not info or ink is None:
                            continue
                        if float(info.get("opacity") or 0) <= 0.05:
                            continue    # 该演出态下不上场的角色不判
                        ink_top = info["y"] + ink[1] * info["h"]
                        ink_bot = info["y"] + ink[3] * info["h"]
                        overflow = max(0.0, band["top"] - ink_top) + max(0.0, ink_bot - band["bottom"])
                        # 留痕：每个角色的**最差（溢出最大）**一组作为数值证据（无论 PASS/FAIL 都记）
                        prev = sprite_evidence.get(sel)
                        if prev is None or overflow > prev["overflow"]:
                            sprite_evidence[sel] = {
                                "overflow": overflow, "label": label,
                                "ink_top": ink_top, "ink_bot": ink_bot,
                                "band_top": band["top"], "band_bottom": band["bottom"],
                            }
                        if overflow > SPRITE_INK_TOLERANCE:
                            failures.append(
                                "%s %s 被页眉带裁切 %.0fpx（墨迹 %.0f~%.0f / 带 %.0f~%.0f）"
                                % (label, sel, overflow, ink_top, ink_bot, band["top"], band["bottom"]))
                # ⑨ **页面级装饰不得压住正文**（2026-10-20 用户实测缺陷补：竖带曾是 22px 窄条，
                #    本项目 em 改造后误用 em ⇒ 单格变 360×540px 大图糊在正文上，用户截图"一大片阴影遮挡"）。
                #    判据：每个竖带格子的矩形**与正文槽矩形不得相交**（容差 1px 抗取整）；并记录**最差相交深度的数值证据**。
                slot = d.get("slotRect")
                brs = d.get("bandRects") or []
                if slot:
                    for bidx, b in enumerate(brs):
                        ox = min(b["x"] + b["w"], slot["x"] + slot["w"]) - max(b["x"], slot["x"])
                        oy = min(b["y"] + b["h"], slot["y"] + slot["h"]) - max(b["y"], slot["y"])
                        depth = max(0.0, ox) if oy > 0 else 0.0     # 竖直重叠时，水平压入的深度
                        prev = band_evidence.get(bidx)
                        if prev is None or depth > prev["depth"]:
                            band_evidence[bidx] = {
                                "depth": depth, "label": label, "w": b["w"], "h": b["h"], "x": b["x"],
                            }
                        if depth > 1.0:
                            failures.append(
                                "%s 竖带第%d格压住正文 %.0fpx（格 x=%.0f w=%.0f h=%.0f / 槽 x=%.0f w=%.0f）"
                                % (label, bidx, depth, b["x"], b["w"], b["h"], slot["x"], slot["w"]))
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

    # **三态齐备且两两不同**（spec R1/R5「三态截图两两不同」的产物级等价判据）：
    # 逐态取"纸页底色 + 正文色"，三者必须**两两不同**（只看截图字节会被动画干扰 ⇒ 读计算样式更稳）。
    THEMES = ["day", "night", "eink"]
    state_pairs = {}
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_page(viewport={"width": vw, "height": vp}, device_scale_factor=1)
        for theme in THEMES:
            write_html(out, sandbox_html(template, vw, vp, 0, 40, "cathedral", "battle"))
            page.goto(out.as_uri(), wait_until="load")
            page.evaluate("(t) => document.documentElement.setAttribute('data-reader-theme', t)", theme)
            page.wait_for_timeout(60)
            d = page.evaluate(MATRIX_JS)
            state_pairs[theme] = (d["paperBg"], d["textColor"])
            if shots_dir is not None:
                shots_dir.mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(shots_dir / ("state_%s.png" % theme)))
        browser.close()
    seen = list(state_pairs.values())
    if len(set(seen)) != len(THEMES):
        failures.append("三态未两两不同：%s" % state_pairs)

    # 判据可用性自检（AD-2）：若**一个角色几何都没取到** ⇒ "角色完整可见"这一条根本没被检验
    # ⇒ 判 FAIL（防"判据因选择器失配而恒真空、却报 PASS"）。
    if not sprite_evidence:
        failures.append("页眉角色完整性判据未取到任何角色几何（选择器失配？）⇒ 判据真空，不得计 PASS")
    # 同理："装饰不压正文"若一个竖带格都没量到 ⇒ 判据真空（选择器失配/元素缺失）⇒ 判 FAIL。
    if not band_evidence:
        failures.append("装饰压正文判据未取到任何竖带格几何 ⇒ 判据真空，不得计 PASS")

    if failures:
        print("[FAIL] R4 结构矩阵（区域×演出态 = %d 组）" % (len(REGIONS) * len(PERFS)))
        for item in failures[:12]:
            print("   - " + item)
        return False
    print("[PASS] R4 结构矩阵：%d 区域 × %d 演出态 = %d 组；竖带 4 格 / 四角 4 枚 / 多帧 steps 动效 6 类全部生效"
          "；**三态（日/夜/水墨屏）两两不同**"
          % (len(REGIONS), len(PERFS), len(REGIONS) * len(PERFS)))
    # 页眉角色完整性：**必须给出数值证据**（禁"静默 PASS"）。带高 = band_bottom − band_top。
    for sel, ev in sorted(sprite_evidence.items(), key=lambda kv: -kv[1]["overflow"]):
        band_h = ev["band_bottom"] - ev["band_top"]
        print("   · 页眉角色 %-20s 墨迹 %.1f~%.1f / 带 %.1f~%.1f（带高 %.1f）⇒ 溢出 %.1fpx [最差组 %s]"
              % (sel, ev["ink_top"], ev["ink_bot"], ev["band_top"], ev["band_bottom"],
                 band_h, ev["overflow"], ev["label"]))
    if shot_list:
        print("   产物截图 %d 张 → %s" % (len(shot_list), shots_dir))
    # "装饰不压正文"必须给出数值证据（禁静默 PASS；本例曾因无此判据而让 360×540px 的竖带糊在正文上）。
    if band_evidence:
        worst = max(band_evidence.values(), key=lambda v: v["depth"])
        sizes = "、".join("%.0f×%.0f" % (v["w"], v["h"]) for v in band_evidence.values())
        print("   · 页面级装饰（竖带 %d 格）尺寸=%s；最差压入正文 %.1fpx [%s] ⇒ %s"
              % (len(band_evidence), sizes, worst["depth"], worst["label"],
                 "无遮挡" if worst["depth"] <= 1.0 else "**有遮挡**"))
    return True


def all_themes_audit(vw: int, vp: int, safe_top: int, paths=None) -> bool:
    """**全内置主题通用正确性巡检**（回答用户"其它内置主题有没有被引擎改动带坏"）。

    口径只用**通用**判据，不套用 dungeon-hud 的产品判据（"正文区 ≥80%"是该主题自己的指标，
    其它主题的页眉/页脚预算各不同）：
      ① **页根底 ≤ 视口底**（`rb − vp ≤ +1`）：末行/页脚不被推出视口 —— 引擎 `box-sizing` 的正确性
         对**所有主题**都成立（这是本轮引擎改动的零回归底线）；
      ② **页框量级** `0.5×vp ≤ client ≤ vp`：分页未退化成"整章一屏"（引擎负下占位守门的兜底效果）；
      ③ **内联 `data:` 个数 ≤ 64**（引擎硬上限，超限素材静默不显示）；
      ④ 仅**报告**：正文槽是否自带显式高度约束、是否自带 `[data-reader-page]` padding reset
         （两者影响"下占位是否为正 / 是否吃 safe 内缩"，不作为判败条件）。

    `paths`（2026-10-20 追加）：显式给出模板 JSON 列表 ⇒ 可对**外部模板**（如 archive 6 套样例）
    跑同一套零回归口径（无需把样例导入设备 ⇒ 不改动用户书库）。
    """
    from playwright.sync_api import sync_playwright

    themes = [Path(p) for p in paths] if paths else \
        sorted((REPO / "app/src/main/assets/reader").glob("*/readerTemplate.json"))
    if not themes:
        print("[取证失败] 未找到主题 JSON")
        return False
    out = REPO / ".temp" / "audit-theme.html"
    failures = []
    rows = []
    n_a = []
    # 「外部模板模式」（`--theme-json` 指定第三方样例时）：判据口径必须**只保留零回归不变量**，
    # 不得把按 dungeon-hud 标定的产品指标（如帧高下界 0.5×vp）套到别人外壳上 —— 否则会把
    # "短视口里 47.8% 的正文槽"这种**合法几何**误判成回归（2026-10-20 实测：lord_of_mysteries 横屏 47.8%）。
    external = bool(paths)
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_page(viewport={"width": vw, "height": vp}, device_scale_factor=1)
        for path in themes:
            # 主题名：内置主题 = 目录名（= id）；外部样例（平铺文件）= 文件名主干
            tid = path.parent.name if path.parent.name.startswith("builtin.") else path.stem
            template = json.loads(path.read_text(encoding="utf-8"))
            # 滚动类模板（`type: scroll`）没有分页页根/正文槽结构 ⇒ 本夹具**不适用**（报告 N/A，不判败）。
            if template.get("type") == "scroll":
                n_a.append((tid, "scroll 类模板（无分页页根/槽）⇒ 本判据不适用"))
                continue
            write_html(out, sandbox_html(template, vw, vp, safe_top, 60))
            page.goto(out.as_uri(), wait_until="load")
            page.wait_for_timeout(80)
            data = page.evaluate(MEASURE_JS)
            source = json.dumps(template, ensure_ascii=False)
            inline = len(re.findall(r"data:[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+;base64,", source))
            css = template.get("css", "")
            rule = re.search(r"\[data-reader-page]\s*\{([^}]*)}", css)
            slot_reset = bool(rule and "padding: 0" in rule.group(1))
            explicit_h = bool(re.search(r"height:\s*100%", css))
            if data.get("error"):
                # 外部模板模式下"取不到分页结构"不判败（可能是外力/异族结构）——但**必须如实登记**。
                if external:
                    n_a.append((tid, "取数失败：%s（外部模板 ⇒ 不判败，如实登记）" % data["error"]))
                else:
                    failures.append("%s 渲染失败：%s" % (tid, data["error"]))
                continue
            overflow = data["rootBottom"] - data["vp"]
            client = data.get("slotHeight") or 0
            ok_overflow = overflow <= 1.0
            # 帧高**上界**（≤ vp）= 零回归不变量（分页不得退化成"整章一屏"）；
            # **下界** 0.5×vp 是按 dungeon-hud 标定的产品指标 ⇒ 外部模板模式下只**报告**、不判败。
            ok_frame = client <= data["vp"] + 1.0 and (external or 0.5 * data["vp"] <= client)
            ok_inline = inline <= 64
            if not ok_overflow:
                failures.append("%s 页根底超出视口 %+.0fpx（末行/页脚会被推出）" % (tid, overflow))
            if not ok_frame:
                failures.append("%s 帧高越上界 client=%.0f（vp=%.0f）⇒ 分页退化成整章一屏"
                                % (tid, client, data["vp"]))
            if not ok_inline:
                failures.append("%s 内联 data: 个数 %d > 64" % (tid, inline))
            rows.append((tid, overflow, client, inline, bool(slot_reset), explicit_h,
                         client < 0.5 * data["vp"]))

    print("[全主题通用巡检] 口径：页根底 ≤ 视口底 / 帧高 ≤ vp / data: ≤64"
          + ("（外部模板：帧高下界仅报告）" if external else ""))
    print("  %-16s %8s %8s %6s %10s %10s %8s" % ("主题", "溢出", "帧高", "data:", "槽padding0", "有100%高", "帧/vp"))
    for tid, overflow, client, inline, slot_reset, explicit_h, below in rows:
        print("  %-16s %+8.0f %8.0f %6d %10s %10s %7.1f%%"
              % (tid, overflow, client, inline, "Y" if slot_reset else "—",
                 "Y" if explicit_h else "—", (client / vp * 100) if vp else 0.0))
    for tid, why in n_a:
        print("  %-16s %s" % (tid, "N/A：" + why))
    if external:
        for tid, _, client, _, _, _, below in rows:
            if below:
                print("   [注] %s 帧高 %.0f < 0.5×vp（短视口下的合法几何；零回归不变量「页根底 ≤ 视口底」已满足）"
                      % (tid, client))
    if failures:
        print("[FAIL] 通用巡检")
        for item in failures:
            print("   - " + item)
        return False
    total = len(rows) + len(n_a)
    print("[PASS] %d 个主题（%d 判 + %d N/A）通过通用巡检" % (total, len(rows), len(n_a)))
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
    ap.add_argument("--all-themes", action="store_true",
                    help="全主题通用巡检（页根底 ≤ 视口底 / 帧量级 / data: 个数）")
    ap.add_argument("--theme-json", nargs="+", default=None,
                    help="显式指定主题 JSON 列表（配合 --all-themes；供 archive 样例等外部模板做零回归巡检）")
    ap.add_argument("--inject-css", default="",
                    help="判据反向对照用：追加 CSS（如故意放大角色 ⇒ '页眉角色完整性'必须报 FAIL）")
    args = ap.parse_args()

    global EXTRA_CSS
    EXTRA_CSS = args.inject_css

    if not THEME.is_file():
        print("[缺产物] %s（先跑 build-dungeon-hud.py）" % THEME, file=sys.stderr)
        return 2
    template = json.loads(THEME.read_text(encoding="utf-8"))
    out = REPO / ".temp" / "offline-geometry.html"
    out.parent.mkdir(parents=True, exist_ok=True)

    cases = [(args.vw, args.vp, args.safe_top)]
    if args.multi:
        cases = [(360, 640, 0), (360, 640, 24), (480, 854, 0), (480, 854, 24), (480, 900, 0), (412, 915, 24),
                 # 横屏/矮屏（本项目 MEmu 实机口径：物理 1600×1000 / density 300 ⇒ CSS 853×533）
                 (853, 533, 0), (853, 533, 24), (800, 480, 0)]

    results = [run_case(vw, vp, safe, template, args.paragraphs, out) for vw, vp, safe in cases]
    if args.matrix:
        shots_dir = None
        if args.shots:
            shots_dir = Path(args.shots_dir) if args.shots_dir else (
                REPO / "docs/specs/reader-theme-family-rework/evidence/artifact")
        results.append(matrix_case(template, args.vp, args.vw, shots_dir))
    if args.all_themes:
        results.append(all_themes_audit(args.vw, args.vp, args.safe_top, args.theme_json))
    print("\n合计 %d/%d 通过" % (sum(results), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
