# 幽夜回廊 · 素材来源与许可

非官方阅读页主题。**素材来自用户自行提供的《SiNiSistar2》解密资源**
（AES 加密 AssetBundle 反向提取出的原始 PNG），本次入库口径由用户裁定为
**「原图直接内联」**：仅按帧**横向拼接为精灵表**（像素零改动），不做降采样/改色/裁切。

## 一、入库范围（已剔除部分）

| 类别 | 是否入库 | 说明 |
| --- | --- | --- |
| 主角 `Animation/Normal/Base` 部件帧 | ✅ | 12 个部件 × 10 个**公共帧号**（body 缺 10..18 帧，故公共帧为 0..9） |
| 主角 `Animation/Broken`·`Broken2`（破损态） | ❌ | **成人向 / 恐怖向，剔除** |
| 主角 `Breast` / `Pregnant` 变体 | ❌ | **成人向，剔除** |
| 敌人 `Slug_Boss/Go`、`Spider_Huntsman/Front` 躯干 | ✅ | 仅取中性的行走/悬浮动作帧；带 `_b_`（血腥）变体一律不取 |
| 敌人剧情事件目录（战败/事件 CG） | ❌ | **成人向 / 恐怖向，剔除** |
| 建筑陈设（`Bg/Object/Cathedral` 等） | ✅ | 只取书、鸟笼、桶、讲经台、箱等中性物件 |
| `Bg/Terrain` 中 `meat_*` / `corpse_*` | ❌ | **恐怖向（血腥），剔除** |
| `UI/Common/window_base.png` | ✅ | 对话框底（黑墨渗染框），用作续页底部对话框 |

## 二、逐项登记

每项素材的**来源相对路径 / SHA-256 / 字节数**由选区脚本写入
`assets/manifest.json`，构建后再汇总进
`app/src/main/assets/reader/builtin.dungeon-hud/sources.json`（含内联字节的 SHA-256，
可离线复核"未被篡改"）。

- 选区（可重放）：`python tools/reader-templates/prepare-dungeon-hud-assets.py --root <解密素材根> --out tools/reader-templates/dungeon-hud/assets`
- 打包（可重放）：`ai_tests\venv\Scripts\python.exe tools/reader-templates/build-dungeon-hud.py`
- 素材根目录：`<用户提供>//Design`（本次为 `E:\sinisistar2_extracted\Design`）

## 三、合规声明

- 素材**版权归原作方所有**；本主题**不代表原作方**，也**不将这些素材重新声明为开源或 CC0**。
- 主题**不内置任何动漫/游戏 IP 的角色名、Logo 或原作文案**；页眉/页脚文字均为自撰。
- 模板默认仅本地使用；导出与分发的授权风险由分发者自行承担（同 `assets/NOTICE.md` 第 4 条）。
