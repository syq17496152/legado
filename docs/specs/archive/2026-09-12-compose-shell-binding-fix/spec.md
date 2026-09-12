# spec.md — compose-shell-binding-fix

## Intent

修复"合成 ViewBinding 空壳"模式中 Kotlin 合成属性遮蔽导致的无限自递归崩溃，消除全项目同型地雷，并将该模式收敛为单一受控实现；同时把 RssFree 自由布局的逐帧诊断日志降频到不掩盖有效信息的水平。

## Scope

**做**：
1. 修复 `RssArticleInfoActivity`（已爆）、`AiImageProviderEditActivity`、`AiImageGalleryActivity`（后两处为潜伏雷，全仓审计确认共 3 处）类级 `root` 写法导致的 `getRoot` 自递归
2. 新增公共工厂 `base/ComposeBindingShells.kt`（安全实现唯一权威），两个雷区页面迁移至工厂
3. `RssFreeGridLayoutManager` 诊断日志节流：常规逐帧状态按时间窗降频，关键状态事件（首次布局、rectsValid 翻转、footer 高度校准、异常兜底路径）保留全量
4. 交付前 Grep 审计：全仓不再存在"匿名 ViewBinding 对象内引用类级 root"写法

**不做**：
- 不动 `RssFreeGridLayoutManager` 的布局/回收逻辑（footer 单实例崩溃已由 9e7d1fa 修复，本次日志包未见复发）
- 不动书源级错误（Empty JSON string / 验证结果为空等，属源规则问题）
- 不改 `BaseActivity` 的 binding 抽象（避免动全 app 基类）
- 不删除 RssFree 诊断日志（只降频）
- 不迁移 `RelaySettingsActivity` / `ConfigActivity` / `AiProviderEditActivity`（现实现安全，渐进迁移，降低本次回归面）

## Approach

### Selected Approach（方案 C：公共工厂 + 雷区迁移 + 日志节流）

在 `io.legado.app.base` 新增顶层工厂函数，把 root 创建收进函数局部作用域（局部变量天然遮蔽接口合成属性，结构上不可能再写出自递归），两个雷区页面改用工厂。选它而非纯最小修复（方案 A）的原因：方案 A 修复后"空壳模式"仍有 5 处复制粘贴、3 种写法并存，下一个复制者仍可能踩雷；工厂函数把正确性内聚到一处。选它而非改造 BaseActivity（方案 B）的原因：BaseActivity 是全 app 核心基类，为两处雷动基类不符合风险收益比。

### Alternatives Considered

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| A 最小修复 | 两处雷改成 RelaySettingsActivity 式 lazy 块局部变量 | 修复快，但 5 处模式 3 种写法并存，复制粘贴风险依旧；无任何结构防护 |
| B 基类改造 | BaseActivity 增加"纯 Compose 页"支持（binding 默认实现） | 动全 app 核心基类，影响所有 Activity，回归面与收益严重不成比例 |
| C 公共工厂（选定） | `base/ComposeBindingShells.kt` 工厂 + 雷区迁移 | 正确性内聚一处；不动基类；3 个安全页面保持原样零风险 |

### Drawbacks

- 工厂函数存在后，其余安全页面旧写法与工厂并存（两种安全写法）——接受：本次以"消灭崩溃+防再犯"为目标，旧页面渐进迁移，避免一次改动 9 个使用点引入回归。兜底：spec 要求 Grep 审计确认无危险写法即可，不要求写法统一。
- `HlsDownloader` MediaMuxer native SIGABRT（系统 MPEG4Writer 长时间写轨整数溢出，视频缓存链路）为独立 P1 链路，与 UI 崩溃修复无交集，不并入本 spec（单一变更原则），在 design.md 留后续立项建议。
- 日志节流可能丢失部分逐帧数据——接受：节流窗口内丢弃的仅是重复的常规状态行（limit/viewport 等稳态值），关键事件全量保留；且节流期间连续日志合并输出计数。兜底：若后续排查自由布局问题需要逐帧数据，`LocalConfig` 预留开关恢复全量。

## Requirements

### Requirement: R1 统一搜索详情页可正常打开
`RssArticleInfoActivity` SHALL 在"统一搜索 → 点击结果"路径下正常创建，无 StackOverflowError。

#### Scenario: 点击搜索结果进详情页
- **WHEN** 用户在 RssSearchActivity 点击任一搜索结果
- **THEN** RssArticleInfoActivity 正常显示标题/封面/多源列表，进程不崩溃

### Requirement: R2 全项目无危险写法残留
代码库 SHALL 不存在"匿名 ViewBinding 对象（或其 getRoot 实现）内引用同名类级属性 root"的写法。

#### Scenario: Grep 审计
- **WHEN** 对全仓执行 `object : ViewBinding` 审计
- **THEN** 每处使用点要么 root 为局部捕获、要么经公共工厂创建，并附 Grep 证据

### Requirement: R3 合成壳模式收敛为受控工厂
新代码 SHALL 通过 `base/ComposeBindingShells.kt` 工厂创建合成 ViewBinding，工厂实现结构上杜绝自递归（root 为函数局部变量）。

#### Scenario: 工厂单点实现
- **WHEN** 需要为纯 Compose 页创建空壳 binding
- **THEN** 调用工厂函数获得 ViewBinding，不再手写匿名对象

### Requirement: R4 RssFree 诊断日志降频
`RssFreeGridLayoutManager` 常规逐帧诊断日志 SHALL 按时间窗节流（同窗口合并输出计数），关键事件（首次布局、rectsValid 翻转、footer 高度校准、提前返回等异常路径）保留全量输出；单次浏览会话日志占比显著下降（目标 ≤15%）。

#### Scenario: 连续滚动日志量
- **WHEN** 用户在自由布局列表连续滚动 30 秒
- **THEN** `[填充]`/`[布局]` 常规状态行按节流窗口合并输出，且每次合并行附带"本窗口被抑制的条数"

#### Scenario: 异常路径不节流
- **WHEN** 出现 rectsValid 翻转 / footer 高度校准 / 提前返回 / 兜底 rebuild
- **THEN** 对应日志立即全量输出，不受节流影响

### Requirement: R5 真机端到端验证
修复 SHALL 在真机/模拟器（测试包 `io.legado.miss.app.debug`）通过端到端验证：统一搜索详情页、AI 图片供应商编辑页、自由布局滚动三个场景。

#### Scenario: E2E 回归
- **WHEN** 执行真机验证流程
- **THEN** 三场景无崩溃、无新增异常，issues-found.md 记录验证结论
