# spec.md - 调试源页与调试日志重构

## Intent

让书源/订阅源调试页达到阅读系第一梯队（MD3阅读）的调试体验：结构化日志事件、分类过滤、类型着色、目标选择 Chips、启停可控、全文查看、导出交付——让"用户调试源失败"到"把日志交给 AI/他人分析"全程顺畅。

## Scope

**包含**：
- `model/Debug.kt`：新增结构化 `DebugEvent(kind/message/timestamp/elapsedMillis)` + `events: StateFlow<List<DebugEvent>>`（takeLast 1000）+ `clearEvents()`；log() 单点双写（结构化流 + 既有 callback/isChecking 校验链不动）
- `BookSourceDebugActivity` / `RssSourceDebugActivity`：Compose 全量重写（布局瘦身为 compose_top_bar + compose_host）
  - 目标 Chips：书源=搜索/发现/详情/目录/正文；订阅源=分类/搜索/内容
  - 过滤 Chips：全部/过程/响应/错误
  - 日志卡片：类型标题 + 相对耗时 + 绝对时间戳 + 消息预览（maxLines=4），按类型着色（取色走 LegadoTheme M3 角色，禁硬编码色）
  - 条目点击 → TextDialog 全文（响应源码/异常堆栈）
  - FAB 开始/停止（状态机 Idle/Running/Failed/Success/Cancelled）+ 清空 + 导出日志（复制/分享 txt，承接 log-compliance-cleanup 批次E）
  - 示例 Chips（checkKeyWord/我的/系统 + 发现分类；RSS 同构）
  - 自动滚动（仅 Running）+ 空态
- `BookSourceDebugModel` / `RssSourceDebugModel`：不再实现 Debug.Callback（printLog 分支废弃），保留源加载与启动

**不包含**：
- `model/Debug.kt` 的 startDebug 全链路流程函数（searchDebug/infoDebug/... 链路与 RSS 链路保持原语义，只加事件发射）
- 校验源（CheckSource/批量校验）流程与 `debugMessageMap` 机制
- 订阅源编辑页（RssSourceEditActivity）本体（只动调试页）

## Requirements

### Requirement: 结构化调试事件流
`Debug` SHALL 提供 `events: StateFlow<List<DebugEvent>>`（上限 1000，最新在尾部），每条事件含 kind（1 过程 / -1 错误 / 1000 完成 / 10 搜索(列表)响应 / 20 详情(内容)响应 / 30 目录响应 / 40 正文响应）、message、timestamp、elapsedMillis；既有 callback / isChecking / debugMessageMap 校验链行为不变。

### Requirement: 调试页 Compose 重写
书源/订阅源调试页 SHALL 以 Compose 呈现（宿主 Activity 保留 intent 传参/扫码能力），含目标 Chips、过滤 Chips、示例 Chips、日志卡片列表、FAB 启停、清空、导出；取色遵循取色唯一基线（LegadoTheme M3 角色），顶栏遵循四组件族（GlassTopAppBar）。

### Requirement: 日志交付闭环
调试页 SHALL 提供「导出日志」：复制全文到剪贴板 / 分享 txt 文件（cacheDir + FileProvider）；会话缓冲与导出内容由结构化事件渲染为文本（含类型标题/耗时/时间戳/消息全文）。

### Requirement: 停止与会话状态
FAB SHALL 在 Running 时变为停止（调 `Debug.cancelDebug()` 终止 tasks），停止后状态记为 Cancelled；错误/完成事件驱动 Failed/Success 终态。

## Scenarios

#### Scenario: 按目标调试无魔法前缀
- **WHEN** 用户选中目标 Chip「目录」并输入 URL 开始
- **THEN** 内部拼装 `++url` 传给 Debug.startDebug，用户无需了解前缀约定

#### Scenario: 错误一眼定位
- **WHEN** 调试过程中某阶段抛异常
- **THEN** 该事件以错误样式着色显示，点击可见完整堆栈；过滤 Chip 切到「错误」只看错误行

#### Scenario: 响应源码即点即看
- **WHEN** 搜索页解析成功产生响应事件（kind=10）
- **THEN** 列表出现"搜索/发现响应"卡片，点击弹出全文（原 HTML 响应体）

#### Scenario: 中途停止
- **WHEN** 调试卡在弱网请求，用户点 FAB 停止
- **THEN** 调试协程被取消，状态记为 Cancelled，已产出的日志保留可导出

#### Scenario: 导出交付分析
- **WHEN** 用户调试失败后点「导出日志」→ 分享 txt
- **THEN** 分享面板出现，文件含全部事件（类型/耗时/时间戳/消息全文），可直接发给 AI 或他人
