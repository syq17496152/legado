#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""选区脚本：从游戏解密素材根目录挑出本主题要用的精灵/道具/UI 原图，原样拷入 assets/。

为什么单独一步（对齐 archive `fetch-*-assets.py` 的做法）：
    入库的图片必须**可由随仓脚本重放生成**（AGENTS.md §11）。本脚本是"入库素材的唯一入口"：
    它按**显式清单**（下方 CONFIG）挑图、算 SHA-256、写 `assets/manifest.json`，因此
    "素材从哪来 / 挑了什么 / 有没有被改过"三件事都可离线复现与审计。

合规口径（2026-10-17 用户裁定 + 2026-10-19 检查点「需调整」意见）：
    · 素材**原图直接内联**入库（不做**非等比**改动）；**区域底图允许等比降采样**（登录取景→尺寸）；
    · **成人向 / 恐怖向（血腥）素材一律剔除**，不进入清单（下方 `DENY_TOKENS` 机检 + 显式目录白名单）；
    · 每项登记来源路径 + SHA-256 + 字节数（降采样项另记 `sourceSize`）；模板内声明"非官方、非原作声明"。

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

from PIL import Image

# --------------------------------------------------------------------------- 选图清单
# 主角部件（Lelia / Animation / Normal / Base）：12 个部件同帧号叠加即为一帧完整人物。
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
# R4 扩容（2026-10-19）：敌方从 2 种扩到 **4 种**（用户要求「不得只用单一敌方」「怪物元素多一点」）。
ENEMIES = [
    {"key": "boss_walk", "dir": "Character/Enemy/Slug_Boss/Go",
     "pattern": r"^slug_boss_go1_\d{3}\.png$", "take": 7},
    # 蛛类的前向集是"部件分层"（body / foot1..4 / ...），本主题只借其躯干做悬浮摆动 ⇒ 只取躯干 3 帧
    {"key": "spider_body", "dir": "Character/Enemy/Spider_Huntsman/Front",
     "pattern": r"^spider_huntsman_front_body_\d{3}\.png$", "take": 3},
    # R4 新增敌方 ③：小型黏液怪（岩窟/湿地）——待机 7 帧
    {"key": "slug_s_idle", "dir": "Character/Enemy/Slug_S/Animation",
     "pattern": r"^slug_s_idle_\d{3}\.png$", "take": 7},
    # R4 新增敌方 ④：蛙形怪（湿地）——待机 5 帧
    {"key": "frog_l_idle", "dir": "Character/Enemy/Frog_L/Egg_Born",
     "pattern": r"^frog_l_idle_\d{3}\.png$", "take": 5},
]

# R4 新增 NPC（页眉"对话"演出的在场者）：中性村民的**分层部件**。
# ⚠️ 素材侧约束（如实登记）：该目录**没有**多层公共帧号（实测各部件公共帧 = 0）⇒ 本主题用
#    "**头部 2 帧 + 其余部件单帧复用**"合成 2 帧整身像（帧 0/1 的差异在头部 ⇒ 仍是**多帧真动图**），
#    并由 `build-dungeon-hud.py` 在同帧号叠加成整身。
NPC_PARTS = [
    "body", "chest", "head", "arm_left", "arm_right",
    "foot_left", "foot_right", "leg_left", "leg_right", "shoulder_right",
]
NPC_DIR = "Character/NPC/VillagerAged"

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
# R4 扩容：**区域推进**要有不同的场景底图（用户要求「不同分页引用不同素材组合」）。
# ⚠️ 实测素材侧只有约 4 类场景（大教堂 / 城镇屋舍 / 墓道地面 / 岩窟碎片）⇒ **不做 8 张独立底图**，
#    用"实拍底图（可降采样）+ 石墙纹理取景位 + 区域调色"表达区域差异（见 design AD-8 第 10 条）。
SCENES = [
    # 大教堂图书馆全景（1440×720）——区域 1（cathedral）：页眉带与纸页底图的唯一底图源
    "Bg/Object/Cathedral/bg_cathedral_library_boss.png",
    # 可平铺石墙纹理（512×512）——区域 4（cave）底图 + 纸页/页脚 HUD 岩石底纹
    "Bg/Sirohone/StoneWall_1031/isigaki.png",
    # R4 新增区域 2（town）：城镇屋舍立面
    "Bg/Object/Furniture/House/House_01_2t.png",
    # R4 新增区域 3（grave）：墓道地面（原图 2 MB 级 ⇒ **等比降采样**到取景宽，见 DOWNSAMPLE）
    "Bg/Terrain/Road_Graveyard/sample_floor1.png",
]

# R4 新增：**左右两侧竖区域**点缀（对齐 archive 的左右竖带做法）——小尺寸中性建筑碎片，
# 由 `build-dungeon-hud.py` 合并成 **1 张图集**（控制引擎 `data:` 个数上限）。
BANDS = [
    "Bg/Object/IsolationWard/rock16.png",
    "Bg/Object/IsolationWard/stalactite_l3.png",
    "Bg/Object/Cathedral/pillar_dining1.png",
    "Bg/Object/Cathedral/Nouhin/wall_stone_block2_alter2.png",
]

# 等比降采样表：key -> 目标最长边（px）。**只允许等比**（禁非等比拉伸）。
# 为什么只对区域底图降采样：区域底图是"大面积铺底"，视觉信息密度低 ⇒ 降采样几乎无损观感；
# 而角色/道具/特效是"小面积精读"，保持原尺寸。
# ⚠️ key 一律 **小写**（与 `build-dungeon-hud.py` 的 `{{asset:...}}` 占位符正则 `[a-z0-9_]+` 同口径）。
DOWNSAMPLE = {
    "house_01_2t": 480,
    "sample_floor1": 480,
}

# 机检剔除词（防成人/恐怖向素材混入）：路径段或文件名命中即 **fail-fast**（不静默跳过）
DENY_TOKENS = [
    "broken", "preg", "breast", "vore", "gore", "blood", "_b_", "dead", "corpse",
    "cg", "event_h", "death", "hurt",
]

UI = []


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def assert_clean(rel: str) -> None:
    """成人/恐怖向剔除的**机检**：命中即 fail-fast（此前只在文档里声明，无法机检）。"""
    low = rel.lower()
    for token in DENY_TOKENS:
        if token in low:
            raise SystemExit("[合规红线] 素材路径命中剔除词 %r：%s ⇒ 请改选目录或补充说明" % (token, rel))


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


def rescale_if_needed(dst: Path, key: str) -> tuple:
    """按 `DOWNSAMPLE` 对入库图做**等比**缩放；返回 (原尺寸, 入库尺寸)。"""
    with Image.open(dst) as img:
        origin = img.size
        target = DOWNSAMPLE.get(key)
        if not target or max(origin) <= target:
            return origin, origin
        scale = target / float(max(origin))
        new_size = (max(1, round(origin[0] * scale)), max(1, round(origin[1] * scale)))
        img.convert("RGBA").resize(new_size, Image.LANCZOS).save(dst, format="PNG", optimize=True)
        return origin, new_size


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
        assert_clean(rel)
        dst = out / (key + ".png")
        shutil.copyfile(src, dst)
        origin, stored = rescale_if_needed(dst, key)
        entries.append({
            "key": key,
            "sourcePath": rel.replace("\\", "/"),
            "file": dst.name,
            "bytes": dst.stat().st_size,
            "sha256": sha256(dst),
            # 降采样登记（2026-10-19 用户裁定）：允许等比降采样，但**必须逐项记录尺寸变化**
            "sourceSize": "%dx%d" % origin,
            "storedSize": "%dx%d" % stored,
            "resampled": origin != stored,
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

    # R4 新增：NPC 分层部件（每部件取**其自身可用帧**，按序号命名；合成在 build 里做）
    npc_dir = root / NPC_DIR
    if not npc_dir.is_dir():
        raise SystemExit("[缺失目录] %s" % npc_dir)
    npc_counts = {}
    for part in NPC_PARTS:
        rx = re.compile(r"^%s_(\d{3})\.png$" % re.escape(part))
        hits = sorted((p for p in npc_dir.iterdir() if p.is_file() and rx.match(p.name)),
                      key=lambda p: p.name)
        if not hits:
            print("  ! NPC 部件缺失（跳过）：%s" % part)
            continue
        npc_counts[part] = len(hits)
        for seq, src in enumerate(hits):
            rel = "%s/%s" % (NPC_DIR, src.name)
            copy(src, rel, "npc_villager_%s_%03d" % (part, seq))
    print("  NPC 部件帧数 = %s" % npc_counts)
    if max(npc_counts.values() or [0]) < 2:
        raise SystemExit("[NPC 动图不足] 无任何部件具备 ≥2 帧 ⇒ 无法产出多帧 NPC（须改选目录）")

    # 敌人动作帧
    for enemy in ENEMIES:
        hits = pick_dir(root, enemy["dir"], enemy["pattern"], enemy["take"])
        for i, src in enumerate(hits):
            rel = "%s/%s" % (enemy["dir"], src.name)
            copy(src, rel, "%s_%03d" % (enemy["key"], i))
        print("  %s -> %d 帧：%s" % (enemy["key"], len(hits), ", ".join(p.name for p in hits)))

    # 场景道具 / 特效 / 界面 / 全幅底图 / 竖带碎片
    # ⚠️ key 统一小写（`build` 的 `{{asset:...}}` 占位符只认 `[a-z0-9_]`；否则占位符替换会静默失败）
    for rel in DECO + EFFECTS + UI + SCENES + BANDS:
        src = root / rel
        if not src.is_file():
            raise SystemExit("[缺失素材] %s" % src)
        copy(src, rel, Path(rel).stem.lower())

    manifest = out / "manifest.json"
    manifest.write_text(
        json.dumps({"root": str(root), "count": len(entries), "items": entries},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    raw = sum(e["bytes"] for e in entries)
    resampled = [e["key"] for e in entries if e["resampled"]]
    print("\n== 选区完成 ==")
    print("  文件数 = %d" % len(entries))
    print("  原始字节 = %d B (%.1f KB)" % (raw, raw / 1024))
    print("  等比降采样项 = %d（%s）" % (len(resampled), ", ".join(resampled)))
    print("  内联 base64 估算 = %.1f KB" % (raw * 4 / 3 / 1024))
    print("  清单 = %s" % manifest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
