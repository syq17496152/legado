#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""打包「幽夜回廊」阅读页主题：可评审源文件 + 素材 → 模板 JSON（+ 离线预览页）。

对齐 archive `build-minecraft.py` 的做法：
    源文件（first/other/footer.html · style.css · live.css · script.js）与素材分开放，
    构建时把素材按 **data: URI 内联**并产出单文件 `readerTemplate.json`
    ⇒ 阅读/复制/导出模板都不依赖网络，也不依赖源码仓库。

与 archive 的两点差异（本项目约定）：
    ① 素材不是"逐帧单图"，而是**按帧横向拼接成精灵表**（像素零改动，仅排布方式不同）
       —— 逐帧 CSS `steps()` 动画必须共用同一张表才能严格同步；
    ② 构建后**自检预算**（元素数 / @keyframes / animation 声明 / vh / 字段白名单 / 关键词），
       超限即 fail-fast —— 这些正是内置模板此前踩过的坑（掉帧、装饰带吃掉正文、假字段）。

用法：
    ai_tests\\venv\\Scripts\\python.exe tools/reader-templates/build-dungeon-hud.py
"""

import base64
import hashlib
import io
import json
import re
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "dungeon-hud"
ASSETS = SOURCE / "assets"
REPO = HERE.parents[1]
THEME_ID = "builtin.dungeon-hud"
THEME_NAME = "幽夜回廊"
DEST = REPO / "app/src/main/assets/reader" / THEME_ID
PREVIEW = REPO / "docs/specs/reader-theme-family-rework/prototypes/dungeon-hud-preview.html"

SCHEMA_VERSION = 1
TYPE_PAGED = "paged"

# 主角 12 层部件（顺序须与 style.css 的 .dh-p-* 及 first.html 的层序一致）
HERO_PARTS = [
    "body", "leg_left", "leg_right", "foot_left", "foot_right", "skirt",
    "chest", "arm_left", "arm_right", "head", "hair", "shoulder_right",
]
# 精灵表：key -> 素材清单前缀
SHEETS = [("sheet_hero_" + p, "hero_" + p) for p in HERO_PARTS]
# R4 扩容（2026-10-19）：敌方从 2 种扩到 **4 种**（用户「不得只用单一敌方」「怪物元素多一点」）
SHEETS += [
    ("sheet_boss_walk", "boss_walk"),
    ("sheet_spider_body", "spider_body"),
    ("sheet_slug_s_idle", "slug_s_idle"),
    ("sheet_frog_l_idle", "frog_l_idle"),
]
# 单体素材：key 即 manifest 里的 key
PLAIN = [
    "book_deco2", "book_deco3", "book_face_spine1", "birdcage3", "barrel4",
    "lectern2", "sample_box", "treasure_box1_004",
    # R4：弹道两帧改由 `sheet_bullet_pair` 承载（不再各自内联 ⇒ 避免"登记了但没人引"的死登记）
    # R4：区域底图（大教堂 / 石墙纹理 / 城镇屋舍 / 墓道地面——后两张已等比降采样）
    "bg_cathedral_library_boss", "isigaki", "house_01_2t", "sample_floor1",
]

# R4 左右两侧**竖区域**点缀（对齐 archive 左右竖带）：4 张小件**合并为 1 张图集**（控 `data:` 个数）
BAND_KEYS = ["rock16", "stalactite_l3", "pillar_dining1", "wall_stone_block2_alter2"]
BAND_CELL = 64      # 图集每格边长（等比 contain + 透明留白 ⇒ 不拉伸）

# R4：显式清单式精灵表（key 无统一前缀，用"按键列表"而非"前缀扫描"）
#   `sheet_bullet_pair` = 弹道两帧（`spider_bullet_hold1/2`）⇒ 让「多帧弹道特效」走 `steps()` 真动图
EXTRA_SHEETS = [
    ("sheet_bullet_pair", ["spider_bullet_hold1", "spider_bullet_hold2"]),
]

# NPC 整身分层顺序（背 → 前；与主角同构，只是该目录没有 skirt / hair）
NPC_LAYER_ORDER = [
    "body", "leg_left", "leg_right", "foot_left", "foot_right",
    "chest", "arm_left", "arm_right", "head", "shoulder_right",
]

# 预算（与 app/src/test 的 `ReaderBuiltinTemplateCatalogTest` 4.19 / NFR-08 契约**同口径**）
# ⚠️ 2026-10-19（R4 扩容）：用户明确要求「怪物/特效多引、页眉密度上去」⇒ 元素与动效规模必然上升。
#   引擎运行时上限是 `MAX_ELEMENTS = 3000`（`md/template-runtime.js`），本门禁是**提交期静态门禁**，
#   故按"仍远离运行时上限"上调：keyframes 14 / animation 声明 20 / 结构元素 200（原 8 / 12 / 120）。
MAX_KEYFRAMES = 14
MAX_ANIMATION_DECLARATIONS = 20
MAX_ELEMENTS = 200
# 引擎硬约束（AD-9，与 Kotlin 常量同口径；2026-10-19 用户裁决「忽略 1 MB 单套总量」）
#   · 内联 `data:` 个数 ≤ 64（`ReaderTemplateImportMapper.MaxInlineDataCount`；超限**不显示**）
#   · 单张内联素材 ≤ 8 MiB（`ReaderAssetPathPolicy.MaxAssetBytes` / `MaxInlineDataBytes`）
MAX_INLINE_DATA_COUNT = 64
MAX_ASSET_BYTES = 8 * 1024 * 1024
ALLOWED_FIELDS = {"bookName", "chapterTitle", "progress", "page", "pageCount", "time", "battery", "percent"}
FORBIDDEN_IP = ["asuka", "eva", "evangelion", "doraemon", "minecraft", "pokemon", "naruto", "hatsune"]

NOTICE = (
    "/*\n"
    "幽夜回廊 · 非官方阅读页主题。\n"
    "素材来源：用户自行提供的《SiNiSistar2》解密资源（AES AssetBundle 反向提取的原始 PNG）。\n"
    "  本次入库口径由用户裁定为「原图直接内联」，仅做**按帧横向拼接**（像素零改动）；\n"
    "  成人向 / 恐怖向（血腥）素材已剔除，仅保留中性的角色部件、怪物动作、建筑陈设与 UI 底图。\n"
    "版权归原作方所有；本主题不代表原作方，也不把这些素材重新声明为开源或 CC0。\n"
    "逐项来源路径与 SHA-256 见同目录 sources.json（由 prepare-dungeon-hud-assets.py 记录）。\n"
    "*/\n"
)


def read_key(manifest: dict) -> dict:
    return {item["key"]: item for item in manifest["items"]}


def sheet_data_uri(files: list, expect: int) -> tuple:
    """按帧横向拼接（像素零改动）并转 data URI；返回 (uri, 拼接后字节, 单帧宽, 单帧高, 帧数)。"""
    if len(files) != expect:
        raise SystemExit("[精灵表] 期望 %d 帧，实得 %d ⇒ 请先跑 prepare 脚本" % (expect, len(files)))
    frames = [Image.open(path).convert("RGBA") for path in files]
    width, height = frames[0].size
    for frame in frames:
        if frame.size != (width, height):
            raise SystemExit("[精灵表] 帧尺寸不一致：%s vs %s" % (frame.size, (width, height)))
    sheet = Image.new("RGBA", (width * len(frames), height), (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        sheet.paste(frame, (index * width, 0))
    buffer = io.BytesIO()
    sheet.save(buffer, format="PNG", optimize=True)
    raw = buffer.getvalue()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii"), raw, width, height, len(frames)


def aligned_sheet_data_uri(files: list) -> tuple:
    """把**尺寸不一致**的帧统一到"最大帧尺寸"画布（等比 contain + 居中）后横向拼接。

    为什么单独一个函数（2026-10-19 实测）：弹道两帧实测 18×11 与 14×15 **尺寸不一致**，
    直接拼接会被 `sheet_data_uri` 的尺寸一致性守卫拦下（守卫本身是对的：帧尺寸不齐会让
    `steps()` 的步进像素错位 ⇒ 动画"跳格"）。故这里**先对齐再拼**（等比缩放，不拉伸）。
    """
    frames = [Image.open(path).convert("RGBA") for path in files]
    width = max(frame.width for frame in frames)
    height = max(frame.height for frame in frames)
    sheet = Image.new("RGBA", (width * len(frames), height), (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        if frame.size != (width, height):
            frame = frame.copy()
            frame.thumbnail((width, height), Image.LANCZOS)
        sheet.paste(frame, (index * width + (width - frame.width) // 2, (height - frame.height) // 2), frame)
    buffer = io.BytesIO()
    sheet.save(buffer, format="PNG", optimize=True)
    raw = buffer.getvalue()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii"), raw, width, height, len(frames)


def png_data_uri(path: Path) -> tuple:
    raw = path.read_bytes()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii"), raw


def frames_of(manifest: dict, prefix: str) -> list:
    """按 key 前缀取帧文件（key 自带序号 ⇒ 排序即可复现顺序）。"""
    items = [item for item in manifest["items"] if item["key"].startswith(prefix + "_")]
    items.sort(key=lambda item: item["key"])
    return [ASSETS / item["file"] for item in items]


def npc_sheet_data_uri(manifest: dict) -> tuple:
    """NPC 整身精灵表：按部件逐层叠加成**每帧一张整身像**。

    为什么这样合成（素材侧约束，如实登记）：NPC 目录**没有多层公共帧号**（实测公共帧 = 0）⇒
    不能像主角那样"按同帧号叠加"。本函数取"**各部件最大帧数**"为整身帧数，单帧部件在该帧**复用**
    ⇒ 帧间差异落在头部（该目录唯一有 2 帧的部件）⇒ 仍是**多帧真动图**（引擎 `steps()` 逐帧可见）。
    """
    by_part = {}
    for part in NPC_LAYER_ORDER:
        files = frames_of(manifest, "npc_villager_" + part)
        if files:
            by_part[part] = files
    if not by_part:
        raise SystemExit("[NPC] 无任何部件素材 ⇒ 先跑 prepare-dungeon-hud-assets.py")
    first_part = next(p for p in NPC_LAYER_ORDER if p in by_part)
    width, height = Image.open(by_part[first_part][0]).size
    frame_count = max(len(v) for v in by_part.values())
    if frame_count < 2:
        raise SystemExit("[NPC] 整身帧数 < 2 ⇒ 无法产出多帧动图（须改选素材目录）")
    composed = []
    for k in range(frame_count):
        canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        for part in NPC_LAYER_ORDER:
            layer_files = by_part.get(part)
            if not layer_files:
                continue
            layer = Image.open(layer_files[min(k, len(layer_files) - 1)]).convert("RGBA")
            if layer.size != (width, height):
                # ⚠️ **禁非等比拉伸**（用户 2026-10-19 裁定）：仅等比缩放到画布内，再**底部居中**贴合
                layer.thumbnail((width, height), Image.LANCZOS)
                canvas.paste(layer, ((width - layer.width) // 2, height - layer.height), layer)
                continue
            canvas.paste(layer, (0, 0), layer)
        composed.append(canvas)
    sheet = Image.new("RGBA", (width * frame_count, height), (0, 0, 0, 0))
    for index, frame in enumerate(composed):
        sheet.paste(frame, (index * width, 0))
    buffer = io.BytesIO()
    sheet.save(buffer, format="PNG", optimize=True)
    raw = buffer.getvalue()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii"), raw, width, height, frame_count


def atlas_data_uri(paths: list, cell: int) -> tuple:
    """把多张小件合并成 **1 张横向图集**（R4 竖带点缀用；控引擎 `data:` 个数上限）。

    ⚠️ 每格用 `thumbnail`（**等比 contain**，禁非等比拉伸）+ 透明留白 + **底部对齐**（小件是"贴地装饰"）
    ⇒ CSS 用 `background-size: <格数*100>% 100%` + `background-position: i/(格数-1)*100% 50%` 取第 i 格。
    """
    tiles = []
    for path in paths:
        image = Image.open(path).convert("RGBA")
        image.thumbnail((cell, cell), Image.LANCZOS)
        tile = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
        tile.paste(image, ((cell - image.width) // 2, cell - image.height), image)
        tiles.append(tile)
    sheet = Image.new("RGBA", (cell * len(tiles), cell), (0, 0, 0, 0))
    for index, tile in enumerate(tiles):
        sheet.paste(tile, (index * cell, 0))
    buffer = io.BytesIO()
    sheet.save(buffer, format="PNG", optimize=True)
    raw = buffer.getvalue()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii"), raw, cell * len(tiles), cell, len(tiles)


def budget_report(template: dict) -> list:
    """构建期自检：把测试侧的硬约束前移到打包环节，超限即阻断。"""
    css = template["css"]
    source = "\n".join(
        [template["firstPageHtml"], template["otherPageHtml"], template.get("scrollHtml", ""),
         template["css"], template["javascript"]])
    keyframes = len(re.findall(r"@keyframes", css))
    animations = len(re.findall(r"animation\s*:", css))
    elements = len(re.findall(r"<[a-zA-Z][^>]*>", source))
    # 内联 base64 载荷必须先剥除：其字符表含 `v`/`h`/数字 ⇒ 会随机拼出 `NNvh` 假尺寸
    # （实测本主题曾因此被判出 50vh/87vh 两个"越界值"，属门禁假阳性）
    payload = re.compile(r"data:[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+")
    # ⚠️ 注释必须一并剥除：`vh` 门禁扫的是"生效值"，注释里出现的 `13.5vh`（旧值说明）不算违规，
    # 但会被 `vh` 正则误扫进来（假阳性来源之一）。
    plain_css = payload.sub(" ", re.sub(r"/\*.*?\*/", " ", css, flags=re.S))
    vh = [float(value) for value in re.findall(r"(\d+(?:\.\d+)?)vh", plain_css)]
    fields = set(re.findall(r'data-reader-field="([^"]+)"', source))
    stripped = payload.sub(" ", source).lower()
    # 死规则机检：CSS 声明的 `.dh-*` 类 vs HTML/JS 里出现的类名（含 `classList.toggle('dh-x')` 字面量）
    declared = set(re.findall(r"\.(dh-[a-z0-9_-]+)", plain_css))
    supplied = set(re.findall(r"dh-[a-z0-9_-]+", source))
    dead_rules = sorted(declared - supplied)
    # 引擎硬约束（AD-9）：`data:` 个数 + 单张解码后体积（与 Kotlin 常量同口径）
    inline_count = len(re.findall(r"data:[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+;base64,", source))
    max_asset_bytes = 0
    for match in re.finditer(r"data:[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+;base64,([A-Za-z0-9+/=]+)", source):
        max_asset_bytes = max(max_asset_bytes, len(match.group(1)) * 3 // 4)

    problems = []
    # 行高门禁（R1，硬指标）：`.dh-page` 的**页眉行 + 页脚行 ≤ 0.2 × 视口高**（保正文区 ≥80%）。
    # 按参考视口 854 CSS px（本轮 MEmu 口径）折算 `clamp(min, Nvh, max)` 的实际取值。
    REF_VP = 854
    HEADER_FOOT_LIMIT = 0.2 * REF_VP
    rows_match = re.search(r"\.dh-page\s*\{[^}]*grid-template-rows:\s*([^;]+);", plain_css)
    hdr_foot = None
    if rows_match:
        row_text = rows_match.group(1).strip()
        # 取**首个**与**末个**轨道（页眉行 / 页脚行）；轨道可能含空格（`clamp(a, b, c)` / `minmax(...)`）
        # ⇒ 不能用 `split()` 分词，必须按"整段轨道"正则捕获。
        track = r"clamp\([^)]*\)|minmax\([^)]*\)|[\d.]+px"
        first = re.match(r"\s*(%s)" % track, row_text)
        last = re.search(r"(%s)\s*$" % track, row_text)
        if first and last:
            hdr_foot = 0.0
            for part in (first.group(1), last.group(1)):
                clamp = re.match(r"clamp\(\s*([\d.]+)px\s*,\s*([\d.]+)vh\s*,\s*([\d.]+)px\s*\)", part)
                fixed = re.match(r"([\d.]+)px$", part)
                if clamp:
                    lo, ratio, hi = (float(clamp.group(1)), float(clamp.group(2)), float(clamp.group(3)))
                    hdr_foot += max(lo, min(hi, ratio * REF_VP / 100.0))
                elif fixed:
                    hdr_foot += float(fixed.group(1))
                else:
                    hdr_foot = None
                    break
    checks = [
        ("@keyframes ≤ %d" % MAX_KEYFRAMES, keyframes <= MAX_KEYFRAMES, "实有 %d" % keyframes),
        ("animation 声明 ≤ %d" % MAX_ANIMATION_DECLARATIONS, animations <= MAX_ANIMATION_DECLARATIONS, "实有 %d" % animations),
        ("结构元素 ≤ %d" % MAX_ELEMENTS, elements <= MAX_ELEMENTS, "实有 %d" % elements),
        ("vh 仅允许 ≤15 或 100", all(value <= 15 or value == 100 for value in vh), "实测 %s" % vh),
        ("字段白名单", fields <= ALLOWED_FIELDS, "越界 %s" % (fields - ALLOWED_FIELDS)),
        ("正文槽位存在", 'data-reader-flow="body"' in source, ""),
        ("字号/行高变量在", "--md-font-size" in css and "--md-line-height" in css, ""),
        ("减少动效静默分支", "prefers-reduced-motion" in css, ""),
        ("钩子 motionChange/dispose 在", "'motionChange'" in template["javascript"] and "'dispose'" in template["javascript"], ""),
        ("无第三方 IP 关键词", not any(word in stripped for word in FORBIDDEN_IP), "命中 %s" % [w for w in FORBIDDEN_IP if w in stripped]),
        ("无外部网络依赖", "http://" not in source and "https://" not in source, ""),
        ("无 @import", "@import" not in source, ""),
        ("已消费派生字段 page/time", {"page", "time"} <= fields, "实测 %s" % sorted(fields)),
        ("内联 data: 个数 ≤ %d" % MAX_INLINE_DATA_COUNT, inline_count <= MAX_INLINE_DATA_COUNT,
         "实有 %d（余量 %d）" % (inline_count, MAX_INLINE_DATA_COUNT - inline_count)),
        ("单张内联素材 ≤ 8 MiB", max_asset_bytes <= MAX_ASSET_BYTES,
         "最大 %.2f MiB" % (max_asset_bytes / 1048576)),
        ("页眉/页脚行 ≤ 0.2×视口（正文区 ≥80%）",
         hdr_foot is not None and hdr_foot <= HEADER_FOOT_LIMIT,
         "页眉+页脚=%.1f（上限 %.1f）" % (hdr_foot, HEADER_FOOT_LIMIT) if hdr_foot is not None else "未能解析 grid-template-rows"),
        # **死规则清零**（spec R4 Scenario）：CSS 里声明的 `.dh-*` 类必须全部在 HTML/JS 中有供给。
        # 为什么机检：旧任务抓到 `dh-c-bl`/`dh-c-tr` 两条"声明了却从不出现"的死规则（与用户报的
        # 「元素缺失」同族）——而这类缺陷**不会报错**，只能靠静默的"规则恒空"暴露。
        ("死规则清零（CSS 类皆有供给）", not dead_rules, "悬空类 %s" % dead_rules),
    ]
    print("\n== 预算自检 ==")
    for name, ok, detail in checks:
        print("  [%s] %-28s %s" % ("OK" if ok else "!!", name, detail))
        if not ok:
            problems.append(name)
    return problems


def main() -> int:
    manifest_path = ASSETS / "manifest.json"
    if not manifest_path.is_file():
        print("[缺素材清单] 请先运行 prepare-dungeon-hud-assets.py", file=sys.stderr)
        return 2
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    # **文件名大小写一致性守卫**（跨平台可移植）：Windows 文件系统**大小写不敏感** ⇒
    # `manifest` 里的 `file` 名与磁盘真实名可能不一致（本轮实测踩到：`House_01_2t.png` vs
    # `house_01_2t.png`），到 Linux/CI 上就会"素材缺失"（且本地永远复现不了）。
    actual_names = {p.name for p in ASSETS.iterdir() if p.is_file()}
    case_mismatch = [item["file"] for item in manifest["items"] if item["file"] not in actual_names]
    if case_mismatch:
        print("[素材文件名大小写不一致] %s（请删掉旧名文件后重跑 prepare）" % case_mismatch, file=sys.stderr)
        return 2
    by_key = read_key(manifest)

    assets = {}
    registered = []
    sheet_geom = {}
    for key, prefix in SHEETS:
        files = frames_of(manifest, prefix)
        uri, raw, frame_w, frame_h, count = sheet_data_uri(files, len(files))
        assets[key] = uri
        sheet_geom[key] = (frame_w * count, frame_h, count)
        registered.append({
            "alias": key, "type": "sheet-inline", "kind": "art", "frames": count,
            "frameSize": "%dx%d" % (frame_w, frame_h),
            "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
            "sources": [by_key[item["key"]]["sourcePath"]
                        for item in manifest["items"] if item["key"].startswith(prefix + "_")],
        })
        print("  %-26s %2d 帧 %3dx%3d → %6.1f KB" % (key, count, frame_w, frame_h, len(uri) / 1024))

    # R4：显式清单式精灵表（弹道两帧 ⇒ `steps()` 真动图；帧尺寸不一致 ⇒ 走"先对齐再拼"）
    for key, key_list in EXTRA_SHEETS:
        files = [ASSETS / by_key[k]["file"] for k in key_list]
        uri, raw, frame_w, frame_h, count = aligned_sheet_data_uri(files)
        assets[key] = uri
        sheet_geom[key] = (frame_w * count, frame_h, count)
        registered.append({
            "alias": key, "type": "sheet-inline", "kind": "art", "frames": count,
            "frameSize": "%dx%d" % (frame_w, frame_h),
            "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
            "sources": [by_key[k]["sourcePath"] for k in key_list],
        })
        print("  %-26s %2d 帧 %3dx%3d → %6.1f KB" % (key, count, frame_w, frame_h, len(uri) / 1024))

    # R4：NPC 整身精灵表（分层部件 → 逐帧整身像）
    npc_key = "sheet_npc_villager"
    uri, raw, w, h, count = npc_sheet_data_uri(manifest)
    assets[npc_key] = uri
    sheet_geom[npc_key] = (w * count, h, count)
    registered.append({
        "alias": npc_key, "type": "sheet-inline", "kind": "art", "frames": count,
        "frameSize": "%dx%d" % (w, h), "sha256": hashlib.sha256(raw).hexdigest(),
        "bytes": len(raw),
        "sources": [item["sourcePath"] for item in manifest["items"]
                    if item["key"].startswith("npc_villager_")],
    })
    print("  %-26s %2d 帧 %3dx%3d → %6.1f KB" % (npc_key, count, w, h, len(uri) / 1024))

    # R4：左右竖带小件 → 合并为 **1 张图集**（控 `data:` 个数）
    band_key = "sheet_band_atlas"
    band_paths = [ASSETS / by_key[k]["file"] for k in BAND_KEYS]
    uri, raw, w, h, cells = atlas_data_uri(band_paths, BAND_CELL)
    assets[band_key] = uri
    registered.append({
        "alias": band_key, "type": "atlas-inline", "kind": "decor", "labels": BAND_KEYS,
        "frameSize": "%dx%d" % (w, h), "sha256": hashlib.sha256(raw).hexdigest(),
        "bytes": len(raw), "sources": [by_key[k]["sourcePath"] for k in BAND_KEYS],
    })
    print("  %-26s %2d 格 %3dx%3d → %6.1f KB" % (band_key, cells, w, h, len(uri) / 1024))

    for key in PLAIN:
        if key not in by_key:
            print("[缺素材] %s（请核对 prepare 清单）" % key, file=sys.stderr)
            return 2
        uri, raw = png_data_uri(ASSETS / by_key[key]["file"])
        assets[key] = uri
        registered.append({
            "alias": key, "type": "png-inline", "kind": "texture",
            "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
            "sources": [by_key[key]["sourcePath"]],
        })

    def expand(text: str) -> str:
        text = re.sub(r"\{\{asset:([a-z0-9_]+)\}\}", lambda match: assets.get(match.group(1), match.group(0)), text)
        # 精灵表几何占位（R4）：`{{sheet-size:k}}` = "<整表宽>px <帧高>px"；`{{sheet-span:k}}` = "-<整表宽>px"。
        # 为什么做成占位而不是在 CSS 里写死像素：帧宽高来自素材（可随素材更换变化），
        # 写死会在换素材时静默错位（本项目已多次实证"两处口径漂移"类缺陷）。
        def sheet_token(match):
            kind, key = match.group(1), match.group(2)
            geom = sheet_geom.get(key)
            if not geom:
                return match.group(0)
            total_w, height, count = geom
            if kind == "sheet-size":
                return "%dpx %dpx" % (total_w, height)
            if kind == "sheet-span":
                return "-%dpx" % total_w
            # 单帧尺寸（元素盒必须等于**帧**尺寸，像素式精灵动画才不错位——见 live.css 的 steps() 纪律）
            if kind == "sheet-fw":
                return "%d" % (total_w // count)
            return "%d" % height
        return re.sub(r"\{\{(sheet-size|sheet-span|sheet-fw|sheet-fh):([a-z0-9_]+)\}\}", sheet_token, text)

    footer = (SOURCE / "footer.html").read_text(encoding="utf-8").strip()
    # ⚠️ 先留**未展开**的原文（占位符还在）用于"素材登记闭合"机检，再展开 —— 展开后 `{{asset:…}}`
    # 已被替换成 data URI，再扫就恒为空（会把每一件素材都误判成"死登记"）。
    raw_first = (SOURCE / "first.html").read_text(encoding="utf-8").replace("{{footer}}", footer)
    raw_other = (SOURCE / "other.html").read_text(encoding="utf-8").replace("{{footer}}", footer)
    raw_css = ((SOURCE / "style.css").read_text(encoding="utf-8") + "\n"
               + (SOURCE / "live.css").read_text(encoding="utf-8"))
    raw_script = (SOURCE / "script.js").read_text(encoding="utf-8")
    all_raw = raw_first + raw_other + raw_css + raw_script

    # **素材登记闭合**（与 `ReaderBuiltinTemplateCatalogTest` 的「登记 SHA == 内联 SHA」同源，提前到构建期）：
    # ①「登记了但没人引」= 死登记（会让测试的"登记数 == 内联数"失守）；②「引了但没登记」= 走私。
    # 二者都必须 fail-fast —— 这是本项目"改了素材但忘记同步台账"的高频坑。
    referenced = set(re.findall(r"\{\{asset:([a-z0-9_]+)\}\}", all_raw))
    dead_entries = sorted(set(assets) - referenced)
    missing_entries = sorted(referenced - set(assets))
    if dead_entries or missing_entries:
        print("[素材登记不闭合] 死登记=%s 缺失登记=%s" % (dead_entries, missing_entries), file=sys.stderr)
        return 1

    first = expand(raw_first)
    other = expand(raw_other)
    css = NOTICE + expand(raw_css)
    script = raw_script

    # **内联次数 == 登记项数**（与 Kotlin 侧「素材登记与内联字节双向闭合」同口径，提前到构建期）：
    # 同一素材被**多处**直接 `url("{{asset:…}}")` 内联时，data URI 会出现 2 次而只登记 1 项
    # （本轮实测：弹道精灵表在"技能特效"与"续页对话框"两处使用 ⇒ 32 次内联 vs 31 项登记）。
    # 正解是**走 CSS 变量单源**（`--dh-x: url(...)` + `var(--dh-x)`），不是改门禁。
    inline_occurrences = len(re.findall(r"data:[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+;base64,", first + other + css + script))
    if inline_occurrences != len(registered):
        print("[内联次数 != 登记项数] 内联 %d 次 / 登记 %d 项 ⇒ 同一素材被多处内联（应改走 CSS 变量单源）"
              % (inline_occurrences, len(registered)), file=sys.stderr)
        return 1

    leftover = re.search(r"\{\{(?:asset:|footer|sheet-)", first + other + css + script)
    if leftover:
        print("[占位未替换] %s" % leftover.group(0), file=sys.stderr)
        return 2

    template = {
        "schemaVersion": SCHEMA_VERSION,
        "id": THEME_ID,
        "name": THEME_NAME,
        "description": "游戏渲染界面范式的阅读页主题：页眉是主角（原作分层部件逐帧）与怪物对峙的战斗场景，"
                       "续页换景为矿洞行进；页脚红条读整书剩余、蓝条读本页序列剩余，随翻页递减，"
                       "读到末尾归零时页眉切「牺牲场景」。素材原图内联（非官方，成人/恐怖向素材已剔除）。",
        "type": TYPE_PAGED,
        "firstPageHtml": first,
        "otherPageHtml": other,
        "scrollHtml": "",
        "css": css,
        "javascript": script,
    }

    problems = budget_report(template)

    DEST.mkdir(parents=True, exist_ok=True)
    out = DEST / "readerTemplate.json"
    out.write_text(json.dumps(template, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    sources = {
        "id": THEME_ID,
        "name": THEME_NAME,
        "note": "非官方阅读页主题。素材来自用户提供的《SiNiSistar2》解密资源（原始 PNG 原样内联，"
                "仅按帧横向拼接为精灵表，像素零改动）；成人向/恐怖向素材已剔除。",
        "notice": "素材版权归原作方所有；本主题不代表原作方，也不将其重新声明为开源或 CC0。",
        "provenance": {
            "root": manifest.get("root", ""),
            "script": "tools/reader-templates/prepare-dungeon-hud-assets.py",
            "rawCount": len(manifest["items"]),
        },
        "assets": [
            dict(item, author="原作方（用户提供解密资源）",
                 source="；".join(item["sources"]),
                 sourceUrl="",
                 license="未授权（非官方主题，仅本地装饰用途）",
                 purpose="幽夜回廊主题的角色/怪物/陈设/界面元素")
            for item in registered
        ],
    }
    (DEST / "sources.json").write_text(json.dumps(sources, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    PREVIEW.write_text(render_preview(template, css, script), encoding="utf-8")

    total = out.stat().st_size
    print("\n== 产物 ==")
    print("  模板 = %s (%.1f KB)" % (out, total / 1024))
    print("  声明 = %s" % (DEST / "sources.json"))
    print("  预览 = %s" % PREVIEW)
    print("  素材内联合计 = %.1f KB" % (sum(len(value) for value in assets.values()) / 1024))
    if problems:
        print("\n[预算超限] %s ⇒ 阻断" % problems, file=sys.stderr)
        return 1
    print("\n  全部门禁 PASS")
    return 0


def render_preview(template: dict, css: str, script: str) -> str:
    harness = """
* { box-sizing: border-box }
body { margin:0; padding:22px; background:#0b0d12; color:#c9ccd3; min-height:100vh;
  font-family:-apple-system,"Noto Sans CJK SC",system-ui,sans-serif; display:flex; flex-direction:column; align-items:center; gap:18px }
.pv-tb { display:flex; flex-wrap:wrap; gap:10px; align-items:center; padding:10px 16px; border:1px solid #23262d; border-radius:12px; background:#0e1015 }
.pv-tb h1 { margin:0 12px 0 0; font-size:13px; letter-spacing:.2em; color:#e8c98a }
.pv-tb button { cursor:pointer; border:1px solid #23262d; background:#15181f; color:#9aa0ab; border-radius:7px; padding:5px 10px; font-size:11.5px }
.pv-tb button[aria-pressed="true"] { background:#e8c98a; color:#12141a; font-weight:700 }
.pv-stage { display:flex; gap:34px; flex-wrap:wrap; justify-content:center }
.pv-cell { display:flex; flex-direction:column; gap:9px; align-items:center }
.pv-cap { font-size:11px; letter-spacing:.16em; color:#7c828d; text-transform:uppercase }
.pv-phone { position:relative; width:480px; height:854px; overflow:hidden; border-radius:22px;
  box-shadow:0 24px 70px rgba(0,0,0,.7), 0 0 0 1px rgba(255,255,255,.06) inset }
"""
    controls = """
var root = document.documentElement;
root.style.setProperty('--rp-percent', '38');

var SAMPLE = [
  '夜色自拱顶的裂隙渗下，落成一束苍白的光。她把披风拢紧，指尖沿石壁划过——那道纹路仍在，一如多年前。',
  '回廊深处传来滴水声，间隔得极有耐心。她没有加快脚步，只是把提灯举高了一些，让光先于她抵达下一个转角。',
  '石柱上的浮雕被磨得只剩轮廓，像一段被反复讲述、终于失真的旧事。她伸手触了触，凉意顺着指骨爬上来。',
  '“还在等我。”她说。声音没有回音，走廊把它收走了。',
  '再往里走，两侧的壁龛一格格亮起，又在她身后一格格熄灭。她数着它们，数到第七格时停住——那里本该有一尊石像，现在只剩一座空台。',
  '台座上刻着一行小字，被谁用指甲刮去了后半截。她凑近，把提灯压低，辨出前半截的意思是「此处留予后来者」。',
  '她直起身，把披风的下摆从台座边缘抽开，继续走。壁龛的光又亮了一格，像是有人在替她数数。',
  '回廊在前方分作两条。左边那条更暗，石砖上结着薄薄一层水汽；右边那条干净得不像话，连灰尘都被人清扫过。',
  '她站在岔口，没有立刻选。她提着灯，把两条路的尽头一一照过，然后走向了那条干净的路——因为干净本身，就是此地最不合常理的事。',
  '脚步声在身后叠成两份。她没有回头看，只是把提灯换到了另一只手上。'
];
var bodies = document.querySelectorAll('.dh-body');
for (var b = 0; b < bodies.length; b++) {
  var html = '';
  for (var s = 0; s < SAMPLE.length; s++) {
    html += '<p class="reader-paragraph' + (s === 0 ? ' dh-lead' : '') + '">' + SAMPLE[s] + '</p>';
  }
  bodies[b].innerHTML = html;
}

function group(id, attr) {
  var box = document.getElementById(id);
  box.addEventListener('click', function (event) {
    var btn = event.target.closest('button');
    if (!btn) return;
    var all = box.querySelectorAll('button');
    for (var i = 0; i < all.length; i++) all[i].setAttribute('aria-pressed', all[i] === btn ? 'true' : 'false');
    root.setAttribute(attr, btn.dataset.v);
  });
}
group('pv-theme', 'data-reader-theme');
group('pv-deco', 'data-rp-decoration');
group('pv-motion', 'data-reader-motion');

var fallenOn = false;
document.getElementById('pv-fallen').addEventListener('click', function () {
  fallenOn = !fallenOn;
  root.style.setProperty('--rp-percent', fallenOn ? '100' : '38');
  var pages = document.querySelectorAll('.dh-page');
  for (var i = 0; i < pages.length; i++) pages[i].classList.toggle('dh-fallen', fallenOn);
});
document.getElementById('pv-entry').addEventListener('click', function () {
  var phones = document.querySelectorAll('.pv-phone');
  for (var i = 0; i < phones.length; i++) phones[i].setAttribute('data-reader-entry', 'pending');
  requestAnimationFrame(function () {
    requestAnimationFrame(function () {
      for (var j = 0; j < phones.length; j++) phones[j].setAttribute('data-reader-entry', 'playing');
    });
  });
});
"""
    return (
        "<!DOCTYPE html>\n<html lang=\"zh-CN\" data-reader-theme=\"day\" data-rp-decoration=\"medium\" "
        "data-reader-motion=\"running\">\n<head>\n<meta charset=\"utf-8\" />\n"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
        "<title>%s · 离线预览</title>\n<style>%s</style>\n<style>%s</style>\n</head>\n<body>\n"
        "<div class=\"pv-tb\"><h1>%s</h1>"
        "<div id=\"pv-theme\"><button data-v=\"day\" aria-pressed=\"true\">日间</button>"
        "<button data-v=\"night\">夜间</button><button data-v=\"eink\">水墨屏</button></div>"
        "<div id=\"pv-deco\"><button data-v=\"none\">装饰·无</button><button data-v=\"light\">轻</button>"
        "<button data-v=\"medium\" aria-pressed=\"true\">中</button><button data-v=\"strong\">强</button></div>"
        "<div id=\"pv-motion\"><button data-v=\"running\" aria-pressed=\"true\">动效·运行</button>"
        "<button data-v=\"paused\">暂停</button></div>"
        "<button id=\"pv-fallen\">切换牺牲场景</button><button id=\"pv-entry\">重播进页动效</button></div>\n"
        "<div class=\"pv-stage\">\n"
        "<div class=\"pv-cell\"><div class=\"pv-cap\">开篇壳 · first</div>"
        "<div class=\"pv-phone\" data-reader-entry=\"playing\">%s</div></div>\n"
        "<div class=\"pv-cell\"><div class=\"pv-cap\">续页壳 · other</div>"
        "<div class=\"pv-phone\" data-reader-entry=\"playing\">%s</div></div>\n"
        "</div>\n<script>%s</script>\n<script>%s</script>\n</body>\n</html>\n"
    ) % (THEME_NAME, harness, css, THEME_NAME, template["firstPageHtml"], template["otherPageHtml"], script, controls)


if __name__ == "__main__":
    raise SystemExit(main())
