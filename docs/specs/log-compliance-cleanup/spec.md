# spec.md - 日志合规清理与规范机制化

## Intent

清零 2026-09-11 审计发现的全部日志违规项（PageDebug 残留、裸 android.util.Log、自建去重轮子、级别纪律、字面量 tag），并把"临时日志必须手动清理、完成声称靠自觉"这两个已两次失效的环节机制化（DebugLog 守卫 + Grep 证据门禁），使日志规范从"文档纪律"升级为"结构保证"。

## Scope

**包含**：
- PageDebug 临时日志 5 文件 7 处删除（ExploreShowViewModel×2 / SearchViewModel×1 / RssArticlesViewModel×2 / AnalyzeUrl×2）
- 核心模块裸 Log 收编 5 文件 21 处（AnalyzeUrl×3、AnalyzeRule×2、OkHttpStreamFetcher×5、AppDatabase×2、HlsDownloader×9）+ VideoPlay.kt 未使用 Log import 删除（原审计 23 处含 cronet 系 2 处误报，实施期实证修正，见 AD-03 v1.1）
- CronetInterceptor 去重轮子收编 putThrottled（删 4 个手写状态字段 + 1 个常量）
- HttpHelper DNS 路径 3 处级别/节流修正
- 字面量 tag 收编 7 处；AppLog 新增 5 个 TAG 常量（31→36）
- **业务侧调试源日志（Debug 第四通道）定位入规范 + 排障能力补强**：logging_rules.md 新增「第四通道」章节与四通道总表（独立于 AppLog，不并入——用户裁决口径）；Debug 单例新增会话环形缓冲（500 行）与 `getSessionLogs()`；两个调试页（Book/RssSourceDebugActivity）顶栏新增「导出日志」（复制到剪贴板/分享 txt）；logcat 守卫放宽为 `BuildConfig.DEBUG || recordLog`（调试会话短暂，用户主动行为，AI 可经 `adb logcat -s sourceDebug` 从用户真机采集）
- logging_rules.md 增补三节（DebugLog 强制条款 / Grep 证据门禁 / 审计 Grep 排除 DebugLog 子串纪律）+ TAG 表同步
- AGENTS.md 门禁第 4 条追加"完成声称附 Grep 证据"
- updateLog.md 编译前同步

**不包含**：
- **上游遗留文件不动**（ACache / ZipUtils / ImageUtils / ExplosionView / DragSelectTouchHelper / UmdFile / ImportOldData / SymmetricCryptoAndroid 的 Log 调用维持原状，登记白名单）——最小侵入原则，避免与原版 legado 产生无谓 diff
- DebugLog.kt / Debug.kt / AppLog.kt 自身的 Log 调用（三层体系合法组成）
- C5 LogModule.kt 双层架构实施（维持预登记、排期待定，本批只做字面量 tag 收编这一子集）
- 视频 HlsRemux 诊断日志的删除（转封装属重大功能诊断链，收编为正式登记 tag，遵循"只降频不删除"铁律）
- LogActivity / AppLog 数据结构变更

## Approach

### Selected Approach

**分流收编，不新造机制**：所有违规点按其在 F9 语义表中的定位三分类——①验证期临时日志（PageDebug 类）→ 直接删除；②过程/状态日志 → `putDebugWithTag`（recordLog 守卫，零开销）并挂模块 TAG；③错误/降级路径 → `putThrottled/putWarn`（频控 + 级别修正）。CronetInterceptor 收编经实证为纯日志去重（三个 helper 只读写 lastLoggedError/lastCertError，无控制流消费），机械替换为 `putThrottled(key, msg, level=WARN)` 语义等价且额外获得"窗口累计计数"合并能力。新增 TAG 常量 5 个（ImageLoad/HlsRemux/RssSourceEdit/CrashReport/DeviceInfo），走 logging_rules 条款四的登记闭环（AppLog 常量区 → 文档两表 → 用途记录）。

### Alternatives Considered

| 备选方案 | 否决理由 |
|---------|---------|
| 上游遗留文件一并清零 | ACache/ZipUtils 等为原版 legado 代码，改动无收益且扩大与原版 diff（项目原则：与原版行为不一致先对比）；登记白名单即可让后续审计不再重复误报 |
| PageDebug 降级为 putDebugWithTag 保留 | F9 条款四已定性：PageDebug 不入白名单、验证闭环后必须清理；真机铁证 2110 条噪音，保留即继续制造噪音；SearchModel 同批已删，留一半反而制造不一致 |
| HlsRemux 9 处直接删除 | 转封装是 F1（MPEG4Writer 平台缺陷规避）的现役诊断链，属"重大功能内置正式诊断日志"，AGENTS.md 铁律禁止清理；收编为登记 tag（可降频不删除）才合规 |
| CronetInterceptor 保留手写轮子不动 | F9 条款三点名收编对象；手写版无窗口累计计数、无 LRU 有界、双份状态（cert/protocol）易漂移；实证无控制流耦合，替换零行为风险 |
| 新增 TAG 收编字面量推迟到 C5 实施 | C5 排期未定，字面量在 fromTag 实施时是必然收编清单，提前收编零风险（仅改字符串字面量为等值常量引用）且消除累积债务 |

### Drawbacks

- **putThrottled 级别从 ERROR 改 WARN 的可观测性变化**：Cronet 降级日志从 ERROR 级变为 WARN 级——logcat 输出不变（AppLog 对 ERROR/WARN 同等输出），仅内存日志级别筛选归类变化。兜底：ai_tests 采集命令用 `-s <TAG>:I` 及以上不受影响。
- **AppLog TAG 增至 36**：文档两表与代码需同批同步，否则重蹈"文档漂移"覆辙。兜底：tasks.md 单列同步任务并附 Grep 证据。
- **DebugLog 强制条款对存量改造任务的约束**：已是规范增量而非代码变更，无回归风险；但要求后续 AI 任务改变习惯（验证日志统一 DebugLog），需在 AGENTS.md 门禁中显式化才有约束力。
- **风险点：CronetInterceptor 收编后日志 key 粒度变化**（原"错误消息前 50 字符"→ putThrottled key 含 host/CERT 前缀）：key 空间由 AppLog LRU 200 兜底，且 key 更细反而降低不同 host 间互相抑制的概率，属改善
- **风险点（红队 R5）：协议错误块内降级计数若与日志去重强耦合**，收编会改变计数频率 → 兜底：AD-02 实施期硬检查点，边界不可分离时该打点保留原结构仅换 putError。

## Requirements

### Requirement: PageDebug 临时日志清零
全仓 `PageDebug` 日志调用（含 put / putDebugWithTag 变体） SHALL 为 0；注释中的历史说明可保留。

### Requirement: 核心模块禁用裸 android.util.Log
`AnalyzeUrl` / `AnalyzeRule` / `OkHttpStreamFetcher` / `AppDatabase` / `HlsDownloader` 中 SHALL 无 `android.util.Log` 直接调用，全仓该 import 仅允许存在于 `AppLog.kt`（三层体系自身）；`VideoPlay.kt` 的未使用 import 删除。审计 Grep 须排除 DebugLog/LogUtils 前缀子串（AD-03 v1.1 审计纪律）。

### Requirement: 频控单点化
CronetInterceptor 的协议错误 / 证书错误 / NAME_NOT_RESOLVED 三类日志 SHALL 统一走 `AppLog.putThrottled`，删除手写去重状态；降级类日志级别按 F9 语义表定为 WARN。

### Requirement: DNS 路径级别纪律
HttpHelper 的 DNS retry SHALL 走节流（putThrottled 或 putDebugWithTag）；negative cache hit 与无效地址过滤 SHALL 为 WARN 及以下级别。

### Requirement: 字面量 tag 收编与 TAG 登记
AppWebDav / RssSourceEditViewModel / MainActivity(CrashReport) / DeviceInfoHelper 的字面量 tag SHALL 改为 TAG 常量引用；新增 TAG 常量同批登记进 AppLog TAG 常量区与 logging_rules.md 模块 Tag 表（含归属模块与用途）。

### Requirement: 日志内容脱敏
OkHttpStreamFetcher 等收编点的消息 SHALL 遵循脱敏铁律：URL 只保留路径模式或长度信息，禁止完整 URL。

### Requirement: 规范机制化条款
logging_rules.md SHALL 新增：①验证期临时日志强制使用 DebugLog（禁止裸 Log.d 作为验证日志载体）；②任务完成声称必须附 Grep 校验证据；③审计 Grep 排除 DebugLog/LogUtils 子串误报纪律。AGENTS.md 门禁第 4 条 SHALL 同步引用②。

### Requirement: 业务侧调试源日志独立通道（第四通道，不并入 AppLog）
书源/订阅源调试源日志（`model/Debug.kt`）SHALL 保持独立业务通道（用户裁决不并入 AppLog），但 SHALL 具备排障闭环能力：
- **会话缓冲**：`Debug.log` 单点同步写入环形缓冲（上限 500 行，超限淘汰最旧），`getSessionLogs()` 供导出；新会话开始/页面销毁时清空
- **导出入口**：Book/RssSourceDebugActivity 顶栏菜单新增「导出日志」= 复制到剪贴板 / 分享 txt 文件（FileProvider）
- **release 可采集**：logcat 守卫由 `BuildConfig.DEBUG` 放宽为 `BuildConfig.DEBUG || AppLog.recordLogEnabled()`——调试是用户主动的短时会话，不构成噪音；recordLog 开启时 `adb logcat -s sourceDebug` 可从用户真机采集调试日志（此前 release 完全不可见，用户只能截图排障）
- **规范登记**：logging_rules.md 登记第四通道定位、守卫语义、脱敏豁免说明（业务侧面向用户自己的源数据；AI 分析导出文件时输出侧仍代号化）与四通道总表

## Scenarios

#### Scenario: PageDebug 回归清零
- **WHEN** 全仓 Grep `PageDebug`（排除注释行）
- **THEN** 日志调用命中数为 0

#### Scenario: 裸 Log 回归清零
- **WHEN** 对 7 个核心模块文件 Grep `Log\.(d|e|w|i|v)\(`（非 DebugLog/AppLog/LogUtils 前缀）
- **THEN** 命中数为 0；上游白名单文件不受影响

#### Scenario: Cronet 降级日志仍可采集且不再刷屏
- **WHEN** 真机触发证书错误 / NAME_NOT_RESOLVED / 协议错误高频失败
- **THEN** 每类 60s 窗口内仅首条入日志，后续合并"（前一窗口累计N条）"；`adb logcat` 仍可按 tag 采集

#### Scenario: DNS 高频路径不再洪水
- **WHEN** 弱网下 DNS 连续重试
- **THEN** retry 日志被节流或仅 recordLog 开启时记录，且不以 ERROR 级进入用户日志列表

#### Scenario: TAG 全集一致
- **WHEN** Grep `const val TAG_` 于 AppLog.kt
- **THEN** 常量数 = 36，与 logging_rules.md 模块 Tag 表行数一致，新增 5 个均有归属模块与用途说明

#### Scenario: 字面量 tag 消除
- **WHEN** Grep `"WebDavBackup"|"RssSourceEdit"|"CrashReport"|"DeviceInfo"`（排除 AppLog.kt 常量定义行）
- **THEN** 调用点均为常量引用，无裸字面量

#### Scenario: release 包行为不变
- **WHEN** release 正式包运行（recordLog 默认关）
- **THEN** 新增的 putDebugWithTag 调用零开销（不写文件不进内存），ERROR/WARN 仍可 logcat 采集——与现状一致，无行为回退

#### Scenario: 调试源日志会话内不丢失
- **WHEN** 用户在书源/订阅源调试页执行一次调试（搜索→详情→目录→正文），期间日志持续输出
- **THEN** 全部日志进入会话缓冲；点击「导出日志」可复制全文或分享 txt，内容包括各阶段时间戳与异常堆栈

#### Scenario: 调试日志环形淘汰
- **WHEN** 单次调试会话产生超过 500 行日志（规则错误循环输出）
- **THEN** 缓冲仅保留最近 500 行，内存占用有界，UI 与导出功能不受影响

#### Scenario: recordLog 开启时 release 调试可采集
- **WHEN** 用户 release 包开启"记录日志"后在真机调试书源，AI 通过 adb 连接采集
- **THEN** `adb logcat -s sourceDebug` 能取到调试日志（原 BuildConfig.DEBUG 守卫下 release 完全无输出）；recordLog 关闭时维持原状
