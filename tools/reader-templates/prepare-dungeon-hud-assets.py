#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""选区脚本：从游戏解密素材根目录挑出本主题要用的精灵/道具/UI 原图，原样拷入 assets/。

为什么单独一步（对齐 archive `fetch-*-assets.py` 的做法）：
    入库的图片必须**可由随仓脚本重放生成**（AGENTS.md §11）。本脚本是"入库素材的唯一入口"：
    它按**显式清单**（下方 CONFIG）挑图、算 SHA-256、写 `assets/manifest.json`，因此
    "素材从哪来 / 挑了什么 / 有没有被改过"三件事都可离线复现与审计。

合规口径（2026-10-17 用户裁定）：
    · 素材**原图直接内联**入库（不做像素改动）；
    · **成人向 / 恐怖向（血腥）素材一律剔除**，不进入清单；
    · 每项登记来源路径 + SHA-256 + 字节数；模板内声明"非官方、非原作声明"。

用法：
    python tools/reader-templates/prepare-dungeon-hud-assets.py \
        --root "E:\\sinisistar2_extracted\\Design" \
        --out  tools/reader-templates/dungeon-hud/assets
"""

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

# --------------------------------------------------------------------------- 选图清单
# 主角部件（Lelia / Animation / Normal / Base）：13 个部件同帧号叠加即为一帧完整人物。
# 只取 **Normal 正常态**：Broken / Broken2（破损态）与 Breast / Pregnant 变体属成人向，**剔除**。
HERO_PARTS = [
    "head", "hair", "body", "chest", "skirt",
    "arm_left", "arm_right",
    "leg_left", "leg_right", "foot_left", "foot_right",
    "shoulder_right",
]
# 逐帧动画要求**所有部件帧号严格对齐**（同帧号叠加才是一帧完整人物），
# 所以取"12 个部件都有的公共帧号"的前 N 个，而不是各部件各取自己的前 N 个。
# 实测：body 缺 10..18 帧 ⇒ 公共帧号为 0..9（10 帧），部件数不一致时本脚本会直接报错。
HERO_FRAMES = 10
HERO_BASE = "Character/Player/Lelia/Animation/Normal/Base"

# 敌人：只取"行走 / 待机"这类中性的战斗动作帧；不取任何剧情事件目录，也不取带 `_b_`（血腥）后缀的变体。
ENEMIES = [
    {"key": "boss_walk", "dir": "Character/Enemy/Slug_Boss/Go",
     "pattern": r"^slug_boss_go1_\d{3}\.png$", "take": 7},
    # 蛛类的前向集是"部件分层"（body / foot1..4 / ...），本主题只借其躯干做悬浮摆动 ⇒ 只取躯干 3 帧
    {"key": "spider_body", "dir": "Character/Enemy/Spider_Huntsman/Front",
     "pattern": r"^spider_huntsman_front_body_\d{3}\.png$", "take": 3},
]

# 弹道/特效小图（中性，无血腥）
EFFECTS = [
    "Character/Enemy/Spider_Huntsman/Front/spider_bullet_hold1.png",
    "Character/Enemy/Spider_Huntsman/Front/spider_bullet_hold2.png",
]

# 场景道具：只取建筑/陈设类中性物件（书、鸟笼、桶、讲经台、箱）。
DECO = [
    "Bg/Object/Cathedral/book_deco2.png",
    "Bg/Object/Cathedral/book_deco3.png",
    "Bg/Object/Cathedral/book_face_spine1.png",
    "Bg/Object/Cathedral/birdcage3.png",
    "Bg/Object/Cathedral/barrel4.png",
    "Bg/Object/Home/Monastery/lectern2.png",
    "Bg/Terrain/Dungeon_Sewage/Area_Boss/sample_box.png",
    "Bg/Terrain/Dungeon_Sewage/Area_Boss/treasure_box1_004.png",
]

# 全幅底图（用户裁定：头部/中部/尾部的可视区底图要由游戏图片构建）
SCENES = [
    # 大教堂图书馆全景（1440×720）：页眉带与纸页底图的唯一底图源；
    # 开篇/续页用**同一张图的不同取景位**（background-position）呈现不同场景，避免重复内联与体积翻倍。
    "Bg/Object/Cathedral/bg_cathedral_library_boss.png",
    # 可平铺石墙纹理（512×512）：纸页与页脚 HUD 的岩石底纹
    "Bg/Sirohone/StoneWall_1031/isigaki.png",
]

# 弹道图标（中性特效小图；替代原对话框底图 —— 底图改用 CSS 自绘以省下 205KB 内联体积）
UI = []


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pick_dir(root: Path, rel: str, pattern: str, take: int):
    """按文件名正则挑帧：**排序后取前 N** ⇒ 可复现（不依赖文件系统枚举顺序）。"""
    folder = root / rel
    if not folder.is_dir():
        raise SystemExit("[缺失目录] %s" % folder)
    rx = re.compile(pattern)
    hits = sorted(p for p in folder.iterdir() if p.is_file() and rx.match(p.name))
    if not hits:
        raise SystemExit("[%s] 正则 %s 无命中 ⇒ 请核对素材命名后重跑" % (rel, pattern))
    if len(hits) < take:
        print("  ! %s 仅 %d 帧（清单要 %d）⇒ 按实有数取" % (rel, len(hits), take))
        take = len(hits)
    return hits[:take]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="解密素材根目录（其下含 Character/ Bg/ UI/ 等）")
    ap.add_argument("--out", required=True, help="输出 assets 目录")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    out = Path(args.out).resolve()
    if not root.is_dir():
        print("[素材根不存在] %s" % root, file=sys.stderr)
        return 2
    out.mkdir(parents=True, exist_ok=True)

    entries = []

    def copy(src: Path, rel: str, key: str):
        dst = out / (key + ".png")
        shutil.copyfile(src, dst)
        entries.append({
            "key": key,
            "sourcePath": rel.replace("\\", "/"),
            "file": dst.name,
            "bytes": dst.stat().st_size,
            "sha256": sha256(dst),
        })
        return dst

    # 主角部件逐帧：先算 12 个部件的**公共帧号**，再取前 HERO_FRAMES 个
    base_dir = root / HERO_BASE
    if not base_dir.is_dir():
        raise SystemExit("[缺失目录] %s" % base_dir)
    shared = None
    for part in HERO_PARTS:
        idx = {int(m.group(1)) for p in base_dir.iterdir() if p.is_file()
               for m in [re.match(r"^%s_(\d{3})\.png$" % re.escape(part), p.name)] if m}
        shared = idx if shared is None else (shared & idx)
    frames = sorted(shared)[:HERO_FRAMES]
    if len(frames) != HERO_FRAMES:
        raise SystemExit("[公共帧不足] 12 部件公共帧仅 %s ⇒ 请下调 HERO_FRAMES" % frames)
    print("  主角公共帧号 = %s" % frames)
    for part in HERO_PARTS:
        for i in frames:
            rel = "%s/%s_%03d.png" % (HERO_BASE, part, i)
            src = root / rel
            if not src.is_file():
                raise SystemExit("[缺失部件帧] %s" % src)
            copy(src, rel, "hero_%s_%03d" % (part, i))

    # 敌人动作帧
    for enemy in ENEMIES:
        hits = pick_dir(root, enemy["dir"], enemy["pattern"], enemy["take"])
        for i, src in enumerate(hits):
            rel = "%s/%s" % (enemy["dir"], src.name)
            copy(src, rel, "%s_%03d" % (enemy["key"], i))
        print("  %s -> %d 帧：%s" % (enemy["key"], len(hits), ", ".join(p.name for p in hits)))

    # 场景道具 / 特效 / 界面 / 全幅底图
    for rel in DECO + EFFECTS + UI + SCENES:
        src = root / rel
        if not src.is_file():
            raise SystemExit("[缺失素材] %s" % src)
        copy(src, rel, Path(rel).stem)

    manifest = out / "manifest.json"
    manifest.write_text(
        json.dumps({"root": str(root), "count": len(entries), "items": entries},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    raw = sum(e["bytes"] for e in entries)
    print("\n== 选区完成 ==")
    print("  文件数 = %d" % len(entries))
    print("  原始字节 = %d B (%.1f KB)" % (raw, raw / 1024))
    print("  内联 base64 估算 = %.1f KB" % (raw * 4 / 3 / 1024))
    print("  清单 = %s" % manifest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
