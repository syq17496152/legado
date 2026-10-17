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
PREVIEW = REPO / "docs/specs/reader-theme-family/prototypes/dungeon-hud-preview.html"

SCHEMA_VERSION = 1
TYPE_PAGED = "paged"

# 主角 12 层部件（顺序须与 style.css 的 .dh-p-* 及 first.html 的层序一致）
HERO_PARTS = [
    "body", "leg_left", "leg_right", "foot_left", "foot_right", "skirt",
    "chest", "arm_left", "arm_right", "head", "hair", "shoulder_right",
]
# 精灵表：key -> 素材清单前缀
SHEETS = [("sheet_hero_" + p, "hero_" + p) for p in HERO_PARTS]
SHEETS += [("sheet_boss_walk", "boss_walk"), ("sheet_spider_body", "spider_body")]
# 单体素材：key 即 manifest 里的 key
PLAIN = [
    "book_deco2", "book_deco3", "book_face_spine1", "birdcage3", "barrel4",
    "lectern2", "sample_box", "treasure_box1_004",
    "spider_bullet_hold1", "spider_bullet_hold2",
    "bg_cathedral_library_boss", "isigaki",
]

# 预算（与 app/src/test 的 4.19 / NFR-08 契约同口径）
MAX_KEYFRAMES = 8
MAX_ANIMATION_DECLARATIONS = 12
MAX_ELEMENTS = 120
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


def png_data_uri(path: Path) -> tuple:
    raw = path.read_bytes()
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii"), raw


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
    plain_css = payload.sub(" ", css)
    vh = [float(value) for value in re.findall(r"(\d+(?:\.\d+)?)vh", plain_css)]
    fields = set(re.findall(r'data-reader-field="([^"]+)"', source))
    stripped = payload.sub(" ", source).lower()

    problems = []
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
    by_key = read_key(manifest)

    def frames_of(prefix: str) -> list:
        items = [item for item in manifest["items"] if item["key"].startswith(prefix + "_")]
        items.sort(key=lambda item: item["key"])
        return [ASSETS / item["file"] for item in items]

    assets = {}
    registered = []
    for key, prefix in SHEETS:
        files = frames_of(prefix)
        uri, raw, frame_w, frame_h, count = sheet_data_uri(files, len(files))
        assets[key] = uri
        registered.append({
            "alias": key, "type": "sheet-inline", "kind": "art", "frames": count,
            "frameSize": "%dx%d" % (frame_w, frame_h),
            "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
            "sources": [by_key[item["key"]]["sourcePath"]
                        for item in manifest["items"] if item["key"].startswith(prefix + "_")],
        })
        print("  %-26s %2d 帧 %3dx%3d → %6.1f KB" % (key, count, frame_w, frame_h, len(uri) / 1024))
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
        return re.sub(r"\{\{asset:([a-z0-9_]+)\}\}", lambda match: assets.get(match.group(1), match.group(0)), text)

    footer = (SOURCE / "footer.html").read_text(encoding="utf-8").strip()
    first = (SOURCE / "first.html").read_text(encoding="utf-8").replace("{{footer}}", footer)
    other = (SOURCE / "other.html").read_text(encoding="utf-8").replace("{{footer}}", footer)
    css = NOTICE + expand((SOURCE / "style.css").read_text(encoding="utf-8") + "\n"
                          + (SOURCE / "live.css").read_text(encoding="utf-8"))
    script = (SOURCE / "script.js").read_text(encoding="utf-8")

    leftover = re.search(r"\{\{(?:asset:|footer)", first + other + css + script)
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
