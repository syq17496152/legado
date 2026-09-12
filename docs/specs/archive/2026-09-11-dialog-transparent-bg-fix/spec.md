# Spec — 遗留弹窗背景图模式透明穿帮修复

## Intent

背景图模式下弹窗必须保持不透明卡片底，与 `ComposeDialogFragment` 家族基线视觉一致，消除"数字轮子悬空"式透明穿帮。

## Scope

**改**：`AlertDialog.applyTint()`、`Dialog.applyModernWindowStyle()`、prefs 弹窗三兄弟的窗口背景 drawable 源（`filletBackground` → `dialogSurfaceBackground`）。

**不改**：
- `Context.backgroundColor` 语义本身（主界面沉浸依赖它返回透明，动它 = 全局回归）
- 故意设 transparent 的弹窗家族（`BaseDialogFragment` 子类、阅读器 ReaderDialog 系列——其布局根节点自带卡片底）
- E-Ink 分支（AndroidAlertBuilder 已在 applyTint 之后覆写 `bg_eink_border_dialog`，时序不受影响）
- NumberPickerDialog 不迁移 Compose 工厂（9 调用方，另行立项）

## Approach（快速路径精简版）

**选定**：单点替换 drawable 源。`dialogSurfaceBackground` 是已存在的专用弹窗底色（`themeColorOrNull(PreferKey.themeCardColor)` → `R.color.dialog_surface` 日 #FFFFFF/夜 #1C1C1E 兜底，`UiCorner.opaqueRounded` + `panelRadius` 不透明圆角），注释即声明其设计目的为"避免背景图模式下弹窗整体透明穿帮"。applyTint 是遗留弹窗唯一的公共窗口背景入口，改它=根因修复。

**否决的备选**：

| 备选 | 否决理由 |
|------|---------|
| NumberPickerDialog 迁移 `showComposeNumberPickerDialog` 工厂 | 9 个调用方全改，>50 行，治标不治本（alert{} DSL 25 文件与 prefs 三兄弟仍穿帮） |
| 修改 `backgroundColor` 让弹窗特判 | backgroundColor 消费点遍布主界面/顶栏/底部导航，加特判污染语义、回归面不可控 |
| 只改 NumberPickerDialog 一处 | 用户明确要求排查同类；同类不改=同 bug 换个入口复发 |

**Drawbacks**：
1. `alert{}` DSL 25 豁免文件弹窗底色从"背景图主色（透明穿帮）"变为 `dialog_surface`/themeCardColor——这正是修复目的，但豁免登记口径需在 migration-registry 注明，风险低。
2. `dialogSurfaceBackground` 圆角为 `ui_panel_radius`(10dp×scale)，旧 `filletBackground` 硬编码 3dp——视觉略变（更接近基线圆角），属对齐规范而非回归。
3. 兜底：若 `themeCardColor` 未配置且 dialog_surface 色值与个别页面突兀，真机走查发现后可微调色值，不影响结构。

## Requirements

### Requirement: 弹窗底色不透明
背景图模式（非 E-Ink）下，所有经 applyTint / prefs 三兄弟的弹窗窗口背景 SHALL 为不透明色（themeCardColor 或 dialog_surface）。

#### Scenario: 订阅源页码弹框
- **WHEN** 配置背景图后打开订阅源文章列表 → 点右上角页码图标
- **THEN** NumberPickerDialog 显示不透明卡片底，标题/按钮/轮子可读

#### Scenario: 非背景图模式不回归
- **WHEN** 未配置背景图
- **THEN** 上述弹窗底色 = themeCardColor/兜底色，与基线弹框一致（不再用页面 backgroundColor）

### Requirement: E-Ink 行为保持
- **WHEN** E-Ink 模式
- **THEN** 弹窗仍走 `bg_eink_border_dialog` 覆写（applyTint 之后的时序不变）

### Requirement: 夜间模式正确
- **WHEN** 夜间主题下打开上述任一弹窗
- **THEN** 底色为 `R.color.dialog_surface` 夜间值 #1C1C1E 或用户 themeCardColor，文字对比可读

## Out of Scope

- NumberPickerDialog / alert{} DSL 向 ComposeDialogFragment 基线迁移
- `applyModernWindowStyle()` 死代码的启用或删除（仅同步换色源）
