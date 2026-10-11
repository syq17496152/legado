# 第三方素材与字体 · 许可与来源

> 本清单聚合**随包分发**的第三方素材/字体/库的许可与来源，供用户与审计核对。
> 每套内置阅读页模板的**逐项素材登记**（别名 / 类型 / 作者 / 来源 URL / 许可 / **SHA-256**）在
> `assets/reader/<模板 id>/sources.json`，可离线复核"未被篡改"。

---

## 一、字体（SIL Open Font License 1.1）

| 字体 | 用途 | 作者 / 提供方 | 许可 |
|------|------|--------------|------|
| **Orbitron**（拉丁子集，woff2） | 内置模板「霓虹夜行」标题与 HUD 读数 | Matt McInerney（The Orbitron Project Authors） | **OFL-1.1** |
| **Share Tech Mono**（拉丁子集，woff2） | 内置模板「星图」刻度读数与坐标标签 | Carrois Corporate / Carrois Type Design | **OFL-1.1** |

- 来源：Google Fonts（`fonts.gstatic.com` 子集 woff2）。
- **许可原文随包**：`assets/reader/builtin.neon-night/orbitron-OFL.txt`、
  `assets/reader/builtin.star-chart/share-tech-mono-OFL.txt`（登记中的 `licenseSha256` 可校验未被改动）。
- 其余内置模板**不内联字体文件**，正文使用系统自带的 **Noto / Source Han（OFL）** 字族。

## 二、图片素材（CC0 / 公有领域）

| 素材 | 用途 | 来源与作者 | 许可 |
|------|------|-----------|------|
| 古典天球刻印图（1660，Haemisphaerium Stellatum Boreale Antiquum） | 内置模板「星图」页首底纹 | Andreas Cellarius；**Wikimedia Commons** | **Public domain** |
| 水墨山水（方从义《山水墨戏》） | 内置模板「墨迹」页首横卷底纹 | 方从义（元代）；克利夫兰艺术博物馆藏，**Wikimedia Commons** | **CC0** |

- 处理方式：仅做**降采样 + 转 WebP + 统一不透明度**（alpha 烘焙），未做内容改动。
- CC0 / 公有领域素材**允许商用与再分发**；本清单中的机构名称仅作**来源标注**，
  **不代表**相关机构对本应用的认可或背书。
- 其余美术（开篇图、边框、页脚图形）为 **WeAgentChat / Legado 自研几何 SVG**，随应用分发。

## 三、随包 JS 库（富渲染）

| 库 | 版本 | 用途 | 许可 |
|----|------|------|------|
| mermaid | 11.x | Markdown 图表（` ```mermaid `） | **MIT** |
| KaTeX | 0.16.11 | 数学公式（`$…$` / `$$…$$`，含 OFL 字体子集） | **MIT** |
| highlight.js | 11.x | 代码块语法高亮 | **BSD-3-Clause** |

- 以上脚本随应用离线内置（`assets/md/vendor/`），**阅读时不联网**。

## 四、合规红线（内置素材的准入门槛）

1. **不内置第三方 IP 原作素材**：不含动漫 / 游戏 / 影视的角色、Logo、原画或衍生图案。
2. 素材仅限三类：**自研原创**、**CC0 / 公有领域**、**许可明确允许再分发且可署名**。
3. 每项素材必须有**可考证来源**与**许可**（见各模板 `sources.json`）。
4. 第三方风格模板由**用户自行导入**并**仅限本地使用**；导出与他处分发的授权风险由用户自行承担。

## 五、应用自身许可

本应用代码的许可条款见「开源许可」页（`assets/LICENSE.md`）。