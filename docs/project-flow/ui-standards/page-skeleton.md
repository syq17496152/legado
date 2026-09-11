# §9.4 页面骨架（Scaffold）

> 本节说明 `ui/widget/compose/` 下三个通用页面/内容骨架的用法，作为管理页/配置页 Compose 化的统一框架（E2「列表」类迁移的承载壳）。
> 相关文件均在 `app/src/main/java/io/legado/app/ui/widget/compose/`。

## 一、`AppManagementScaffold`（管理页通用壳）

**文件**：`AppManagementScaffold.kt`

### 角色
通用「管理页」三层壳：**顶栏 + 内容 + 底部多选批量操作栏**，是列表管理页（如替换规则、订阅源管理等）Compose 化的主骨架，也是 §7.11 若干列表类迁移的落点选择。

### 用法签名
```kotlin
AppManagementScaffold(
    title: String,                    // 顶栏标题
    selectedCount: Int,               // 已选数量（>0 时展示批量栏）
    totalCount: Int,                  // 总数量（角标/批量计数用）
    modifier: Modifier = Modifier,
    palette: AppManagementPalette = rememberAppManagementPalette(),
    searchQuery: String? = null,      // 搜索词（null 不显示搜索框）
    searchHint: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    topActions: List<AppManagementAction> = emptyList(),   // 顶栏动作/菜单
    bottomActions: List<AppManagementAction> = emptyList(), // 底部批量动作
    onBack: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,                       // 全选
    onInvertSelection: (() -> Unit)? = null,                 // 反选
    content: @Composable (AppManagementPalette) -> Unit      // 内容区
)
```

### 内部结构
- 外包 `LegadoComposeTheme`，实施主题统一。
- 顶栏 `GlassTopAppBar`（管理族由 `AppManagementScaffold` 委托；~~`AppManagementTopBar`~~ 定义已于 2026-09-08 删除，见 architecture.md §三.1）：返回 + 标题（**20sp / Medium(500)**，全 App 统一 `titleLarge`；原 19sp/半粗口径已废）+ 动作（`AppManagementAction`，可为「更多」菜单 `menuActions`）；顶栏取色以 architecture.md 单源 `resolvePageBarColorWithAlpha` + `contrastOn(barColor)` 为准（本文旧的 `backgroundColor`/`primaryColor` 切换表述已过时）；`statusBars` insets。
- 内容区 `Box(weight=1f)` 交给调用方。
- 底部 `AppManagementSelectionBottomBar`：`selectedCount > 0` 时 `AnimatedVisibility` 出现；含「已选 X/Y」、反选、主危险动作（`danger` 优先）+ 更多菜单；`navigationBars` insets。

### 数据模型
- `AppManagementAction(text, iconRes?, primary, danger, onClick, menuActions?)`：顶栏或批量动作描述。
- `AppManagementMenuAction(text, checked, enabled, danger, onClick)`：更多菜单项，支持勾选态/show 态。

## 二、`AppSettingComponents`（调色板 + 脚手架组件）

**文件**：`AppSettingComponents.kt`

### 角色
提供设置/管理样式的**调色板**与若干内容脚手架组件，是 `AppManagementScaffold` 及设置类页面的内部实现基础（对应用侧 E3 半迁移壳 / E4 统一外观的样式来源）。

### 调色板
- `AppSettingPalette`：`@Immutable`，含 `page/row/rowPressed/divider/bottomBar/bottomBarText/border/primaryText/secondaryText/accent/danger/disabledText/onAccent/panelRadiusPx/bodyFontFamily/titleFontFamily/themeSignature`。所有样式组件据此取色，禁止硬编码色。
- `AppManagementPalette`：`settings(AppSettingPalette)` + `miuix(LegadoMiuixPalette)`。
- `rememberAppSettingPalette()`、`rememberAppManagementPalette()`：按依赖重算的记忆函数。
- `onAccent` 由 accent 明暗推导；`secondaryText` 默认透明度 0.72；危险的红色强化 `dialogStyle.danger`。

### 内容脚手架组件
| 组件 | 用途 |
|------|------|
| `AppManagementLazyColumn` | 带垂直滚动条（`ComposeLazyListFastScroller`）的 LazyColumn 壳 |
| `AppManagementCard` | 可点击/长按卡片容器（可叠加面板背景图、边框），处理按压态 |
| `AppManagementListRow` | 复合列表行：标题/副标题 + 选中槽（勾选框）+ 开关 + 编辑/更多/删除动作 |
| `AppManagementMoreActionButton` | 浮出更多菜单按钮（AndroidView + `ModernActionPopup`） |
| `AppManagementIconAction` | 图标按钮动作 |
| `AppSettingSectionTitle` | 设置分组节标题（accent 色） |
| `Modifier.appSettingPanelBackground` | Canvas 绘制带面板背景图 + 圆角 + 边框的底 |
| `Modifier.appSettingRowDecoration` | 列表行按压/危险态/分隔线绘制（支持首/尾行圆角） |

## 三、`AppComposeDialogs`（对话框骨架总成）

**文件**：`AppComposeDialogs.kt`（详见 dialog-shell.md）

### 角色
含 **Compose 对话框族**（8+ 类）与对话框样式/框架组件（`AppDialogStyle`、`rememberAppDialogStyle`、`AppDialogFrame`、`AppDialogSwitchRow`/`AppDialogOptionGroup`/`AppDialogSliderRow`/`AppDialogSliderGrid`/`AppDialogSliderItem`）。

### 对话框宽度档位
由 `AppUiTokens.kt` 的 `AppDialogSize` 决定：`Confirm`/`Form`/`Management`/`Wide`（宽度比例 + 平板上限），`ComposeDialogFragment` 据此换算窗宽。

### 对话框类清单（部分）
`ComposeTextInputDialog`、`ComposeSuggestionTextInputDialog`、`ComposeTextFormDialog`、`ComposeNumberPickerDialog`、`ComposeMultiChoiceDialog`、`ComposeConfirmDialog`、`ComposeSingleChoiceDialog`、`ComposeActionListDialog`、`ComposeFetchedModelDialog`——均为 `ComposeDialogFragment()` 子类，详见 dialog-shell.md。

## 四、三骨架的选取原则

| 场景 | 骨架 |
|------|------|
| 全屏管理页（顶栏+列表+批量栏） | `AppManagementScaffold` + `AppManagementLazyColumn/ListRow/Card` |
| 配置/设置类页面（分区卡片） | `ComposeSettingFragment` + `SettingSpecScreen`（见 dialog-shell.md § 二）+ `AppSettingSectionTitle` |
| 弹层/对话框 | `ComposeDialogFragment` 子类 + `AppDialogFrame`（见 dialog-shell.md） |

## 五、根背景规则（2026-08-27 硬约束）

- **页面根容器背景**：`rememberAppSettingPalette().settings.page`（Compose）/ `ThemeStore.backgroundColor()`（View）；**禁止** `MaterialTheme.colorScheme.surface / background` 做页面根背景（M3 派生色，H9/H11 实测偏色）。**实施状态（2026-08-27 实况核查）**：PreciseManage 根背景已归 page ✅；AutoTask/TxtTocRule/AllBookmark/Highlight/DictRule/RecycleBin 6 页列表项卡片已有 5/6 归 `palette.settings.row`（**仅剩 TxtTocRuleScreen**，实施状态以 issue-list/tasks.md 为准）。
- **卡片 / 行底色**：`UiCorner.surfaceColor(themeUiPalette.cardColor)`（settings）或 `palette.settings.row`（management）；**禁止** SettingsCard/ListCard M3 默认 surface（H9/H10）。
- **顶栏容器色**：`Color(context.primaryColor)`（GlassTopAppBar 默认）；管理页随 `immersiveManageBar` 切 backgroundColor/primaryColor。
- **检查点**：新建 Compose 页面 Grep `colorScheme.surface` 自检；页面根容器与同屏同类页背景一致。