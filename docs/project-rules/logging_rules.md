# 日志规范

> 基于 Legado 项目源码深度分析提取的项目特有日志约定。
> 2026-09-06 同步 log-system-upgrade：recordLog 默认值按包类型区分 + DEBUG 级完整记录 + 内存容量 500 + 日志管理中心（ui/log/LogActivity）。
> 2026-09-11 批次F F9 增补：周期日志禁令 + 级别语义表 + putThrottled/putSampled 频控机制（真机日志 5521 条 MemoryPressure 洪水教训驱动）。

---

## F9 频控与级别纪律（2026-09-11 增补，强制）

### 条款一：周期性日志禁令

- **禁止**在固定间隔轮询/定时器/高频调用路径中直接打点（无论级别）——每个周期 tick 都是一条日志。
- 监控类/内存类周期路径只允许**状态迁移打点**：仅在档位跃迁时输出一条（如 MemoryPressure 压力级别 normal/low/critical 迁移）。
- 高频失败路径用 `AppLog.putThrottled(key, msg, throwable, level, tag?)`：同 key 60s 窗口仅输出首条，下一条合并"（前一窗口累计 N 条）"。
- 高频成功路径（cache hit 等）用 `AppLog.putSampled(key, msg, n, level, tag?)`：每 n 条输出 1 条汇总（附累计计数）。
- 新增周期日志需在 TaskList 说明豁免理由，否则视为违规。

### 条款二：级别语义表（AppLog.Level 使用纪律）

| 级别 | 语义 | 典型场景 |
|------|------|---------|
| ERROR（put 默认） | 用户可感知的失败 | 功能报错、任务失败、异常捕获 |
| WARN | 降级/兜底发生（功能仍可用） | 探测恢复、回退通道、重试后成功 |
| INFO | **仅状态迁移** | 引擎路由切换、压力级别跃迁、阶段翻转 |
| DEBUG | 过程细节 | recordLog 门控下的过程埋点 |

- **禁止**用 ERROR 级别输出成功/过程信息（真机教训：预连接成功打 ERROR 级=级别语义违规+噪音）。
- 正式诊断日志 tag（TtsTrace 等白名单，见 AGENTS.md 诊断日志保留铁律）**只降频不删除**。（⚠️ `PageDebug` **不在**此白名单，见下方"一次性临时排查 tag"条目——本条曾误列 PageDebug，与其矛盾，2026-09-11 已修正）

### 条款三：putThrottled/putSampled 使用指南

- 单点收编：新代码禁止自建"60s 去重/采样计数"轮子（如 CronetInterceptor 的 lastLoggedError 模式），统一走 AppLog 双机制。
- key 命名：`{模块}_{场景}`（如 `HttpHelper_preconnect_ok`）；key 空间有界（200，FIFO 淘汰）。
- tag 透传：`putThrottled/putSampled` 的 `tag` 参数走 `putDebugWithTag` 链路（模块化 logcat 采集 `adb logcat -s <Tag>:I` 可过滤）。
- 节流/采样不适用于：用户可见错误（应 toast+put）、崩溃链路、状态迁移打点。

### 条款四：诊断 tag 白名单（F9/2.21）

- **白名单权威源 = `constant/AppLog.kt` 的 TAG 常量区**（TAG_TTS_TRACE/TAG_HIGHLIGHT_STYLE/TAG_SOURCE_MECHANISM 等正式登记 tag），白名单 tag 对应"重大功能升级内置的正式诊断日志"，**只降频不删除**（AGENTS.md 诊断日志保留铁律 2026-09-10）。
- 一次性临时排查 tag（如 SwipeTest/VbsDiag/PageDebug）**不入白名单**，验证闭环后必须清理（铁证：PageDebug 临时日志遗留致 2110 条噪音）。
- 新增正式诊断 tag 必须登记进 AppLog TAG 常量区并在此条款记录用途与事件全集。

---

## 日志体系（三系统 + 业务侧第四通道）

### 第一层：AppLog（核心日志，面向用户/调试）

- 文件：`constant/AppLog.kt`
- 单例对象，维护内存日志列表（上限 500 条，`MAX_LOG_SIZE` 常量；最新在前，超限移除最旧）
- 方法（**清单以 `constant/AppLog.kt` 源码为准**，2026-09-11 全量核对）：
  - **级别族**：
    - `put(message, throwable?, toast?)` — ERROR 级（默认）+ 写文件 + Logcat
    - `putError(message, throwable?, toast?)` — 显式 ERROR
    - `putWarn(message, throwable?, toast?)` — WARN（降级/兜底）
    - `putInfo(message, throwable?, toast?)` — INFO（**仅状态迁移**）
  - **频控族**（高频路径必用，禁止自建去重轮子）：
    - `putThrottled(key, message, throwable?, level?, tag?)` — 失败路径，同 key 60s 首条 + 合并"前一窗口累计 N 条"
    - `putSampled(key, message, n, level?, tag?)` — 成功路径，每 n 条汇总 1 条
  - **Debug 族**：
    - `putDebug(message, throwable?)` — 仅 `AppConfig.recordLog` 开启时记录
    - `putDebugWithTag(tag, message, throwable?, level?)` — 模块化打点（Tag 常量见下表）
  - **其他**：
    - `putNotSave(message, throwable?, toast?)` — 仅内存 + Logcat，不落文件
    - `logs` — @Synchronized 快照（UI 侧读取安全，规避并发 CME）
    - `removeLogs(entries)` — 多选删除（按对象引用 `===` 匹配，规避 data class equals 误删重复项与索引漂移）
    - `clear()` — 清空内存日志
    - `truncateSafely(msg, maxLen = 2000)` — 超长消息截断（脱敏前置处理）
    - `syncEventBusLogger()` — EventBus 日志桥接注册
- `toast = true` 时直接 Toast 提示用户
- 日志可在 App 内通过 `AppLogDialog`（轻量弹框，约 20 处入口）或「日志管理」全屏页查看

```kotlin
AppLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
AppLog.put("保存成功", toast = true)
```

### 第二层：LogUtils（文件日志，面向开发）

- 文件：`utils/LogUtils.kt`
- 基于 `java.util.logging.Logger`，Logger 名 `"Legado"`
- 使用自定义 `AsyncFileHandler`（异步写入，避免 IO 阻塞）
- 日志文件存储在 `externalCacheDir/logs/`，自动清理 7 天前（含 `.lck` 锁文件）
- 日志级别由 `AppConfig.recordLog` 控制

### 第三层：DebugLog（纯 Logcat 调试日志）

- 文件：`utils/DebugLog.kt`
- 仅在 `BuildConfig.DEBUG` 时输出到 Logcat
- 提供 e/d/i/w 四个级别

### 第四通道：Debug（业务侧调试源日志，独立于 AppLog——用户裁决不并入）

- 文件：`model/Debug.kt`（object 单例）
- **定位**：书源/订阅源「调试源」功能的业务日志（`BookSourceDebugActivity` / `RssSourceDebugActivity`），面向**用户排障自己的规则**，与 AppLog（系统/模块日志）职责不同，**不并入 AppLog**（2026-09-11 用户裁决：不期望一套）
- 数据流：`Debug.log()` → ①logcat（tag=`sourceDebug`，守卫=`BuildConfig.DEBUG || AppLog.recordLogEnabled()`，recordLog 开启时 release 也可 `adb logcat -s sourceDebug` 采集）→ ②**会话环形缓冲**（上限 500 行，`Debug.getSessionLogs()` 全文读取，新会话/页面销毁时清空）→ ③UI 实时列表（callback）→ ④校验摘要（`debugMessageMap`，isChecking 场景）
- **结构化事件流（debug-page-redesign AD-01，2026-09-11）**：`Debug.events: StateFlow<List<DebugEvent>>`（上限 1000，takeLast），每条含 kind/message/timestamp/elapsedMillis；kind 复用 state 码（1 过程/-1 错误/1000 完成/10 搜索(列表)响应/20 详情(内容)响应/30 目录响应/40 正文响应）。调试页 UI 消费事件流（过滤 Chips + 着色卡片），callback 保留供校验链，**新增调试消费方一律优先订阅 events，不要再挂 callback**
- **导出**：两个调试页顶栏菜单「导出日志」= 复制到剪贴板（`sendToClip`）/ 分享 txt 文件（cacheDir + FileProvider）
- 守卫语义：单条消息经 `AppLog.truncateSafely` 截断（2000 字符）；缓冲环形淘汰防内存膨胀；调试会话有限时长，无需节流
- **脱敏豁免说明**：调试日志面向用户自己的源数据（含完整 URL/规则），不强制走 AI 层脱敏铁律；但 AI 分析用户提供的调试日志导出文件时，**输出侧仍按 `.trae/rules/output-safety.md` 代号化**
- 使用规则：书源/订阅源调试链路内的过程日志用 `Debug.log(debugSource, msg)`；**禁止**在调试链路混用 `AppLog.put*`（用户日志列表会被业务调试过程污染），也禁止反向把系统日志塞给 Debug

## 四通道总表（2026-09-11 增补）

| 通道 | 载体 | 守卫 | 去向 | 适用 |
|------|------|------|------|------|
| AppLog | `constant/AppLog.kt` | recordLog 门控 DEBUG 级 | 内存 500 + 文件 + logcat | 系统/模块日志（用户可感知错误、状态迁移、诊断埋点） |
| LogUtils | `utils/LogUtils.kt` | recordLog | 文件（7 天轮转） | AppLog 的落盘底层，不直接调用 |
| DebugLog | `utils/DebugLog.kt` | BuildConfig.DEBUG | 仅 logcat | 开发期调试 + **验证期临时日志唯一合法载体**（见下方条款五） |
| Debug | `model/Debug.kt` | DEBUG 守卫（logcat 部分放宽至 recordLog） | UI 实时 + 会话缓冲（500 行）+ logcat | 书源/订阅源调试源业务日志，独立不并入 |

## recordLog 开关语义（log-system-upgrade AD-01/AD-02）

- **默认值按包类型区分**：无用户偏好值时，debug 测试包默认 `true`、release 正式包默认 `false`（`AppConfig.recordLog` getter 按 `BuildConfig.DEBUG` 取默认）；用户显式设置后以设置为准（两类包行为一致）
- **单一权威开关**：`开 = 全量四级记录（内存 + 文件）`；`关 = 仅 ERROR/WARN/INFO 进内存`（DEBUG 级丢弃）
- release 包下 DEBUG 级 logcat 输出仍由 `BuildConfig.DEBUG` 守卫（避免噪音）；ERROR/WARN/INFO 无条件输出 logcat（线上可采集）

## 辅助工具

- `printOnDebug()` 扩展函数（`LogUtils.kt`）：Throwable 扩展，仅 Debug 模式打印堆栈
- `Debug` 对象（`model/Debug.kt`）：书源调试专用日志，带时间戳，支持 UI 回调显示
- `ui/log/LogActivity`（日志管理中心，log-system-upgrade）：全屏 4 Tab——应用日志（搜索/级别筛选/多选删除/详情/复制）、崩溃日志（查看/单删/多删/分享）、文件日志（尾部 500 行截断查看/删除）、堆转储（大小/删除）；顶栏一键清除（确认+占用文件跳过）+ 导出 logs.zip（`ui/log/LogExporter` 复用精准管理 saveLog）；支持 Intent extra `EXTRA_INITIAL_TAB` 直达指定 Tab

## 日志标签约定

| 组件 | 标签 |
|------|------|
| AppLog 写入 LogUtils | `"AppLog"` |
| Debug 模式 Logcat | 调用类名 `stackTrace[3].className` |
| Debug 对象 | `"sourceDebug"` |
| LogUtils 文件日志 | `"Legado"` |

## 使用规则

1. **业务错误**：使用 `AppLog.put()`，重要错误加 `toast = true`
2. **调试信息**：使用 `AppLog.putDebug()` 或 `DebugLog`
3. **书源调试**：使用 `Debug.log()` 对象
4. **禁止**直接使用 `android.util.Log`（release 构建会被 ProGuard 移除）。例外：改造验证期允许临时使用 Log.d/Log.e 打验证日志（统一自定义 tag），验证通过后必须 Grep 确认 0 残留并移除（见 logging-during-refactoring.md 双轨制）
5. **禁止**使用 Timber（项目未引入）

## 条款五：验证期临时日志强制 DebugLog（2026-09-11 增补，机制化）

- 改造验证期需要打临时验证日志时，**统一用 `DebugLog`**（自带 `BuildConfig.DEBUG` 守卫 + 统一功能性 tag），**禁止用裸 `android.util.Log` 作为临时日志载体**——裸 Log 忘清理的后果是 release 包噪音（铁证：PageDebug 2110 条），DebugLog 忘清理 release 也零输出，机制上消除后果
- 验证闭环后的清理要求不变：Grep tag 确认 0 残留并移除（logging-during-refactoring.md 双轨制）

## 条款六：完成声称 Grep 证据门禁（2026-09-11 增补，机制化）

- 任务勾选 `[x]` 涉及"已删除/已清零/已同步"类声称时，**必须附 Grep 校验证据**（模式 + 命中数），禁止口头声称——铁证：real-device-bugfix-0911 tasks 2.19 声称"PageDebug 已删"，实际 5 文件 7 处残留
- 审计 Grep 模式注意：`Log\.(d|e|w|i|v)\(` 会把 `DebugLog.x(` / `LogUtils.x(` **子串误报**为裸 Log 违规，正确模式为 `^import android\.util\.Log$`（import 定性）或加负向排除（AD-03 v1.1，log-compliance-cleanup 实施期实证）

## 模块 Tag 规范

> 登记规则（总线 X6）：本表按 `constant/AppLog.kt` TAG 常量**实际全集**登记，**2026-09-11 log-compliance-cleanup 后实测 36 个**；不锚定历史 26 TAG 基线；ng P1/P2 等分期新增 Tag 按落地顺序顺延，**新增/修改 TAG 时必须同步更新本表与下节 fromTag 映射**（对照流程：Grep `TAG_` 常量定义 + `putDebugWithTag` 调用点全集 + 字面量 tag）。

| Tag 常量 | 值 | 归属模块（C5 预登记） | 调用点状态 |
|---------|-----|---------|------|
| `TAG_WEB_BOOK` | `"WebBook"` | SOURCE_NETWORK | 在用（WebBook/BookList/BookInfo 等） |
| `TAG_ANALYZE` | `"AnalyzeRule"` | SOURCE_NETWORK | 在用（AnalyzeByJSonPath/AnalyzeRule 等） |
| `TAG_HTTP` | `"HttpHelper"` | SOURCE_NETWORK | 在用（AnalyzeUrl 等） |
| `TAG_WEB_VIEW` | `"BackstageWebView"` | SOURCE_NETWORK | ⚠️ 死常量（0 调用点，BackstageWebView 类未打点） |
| `TAG_DATA` | `"DataLayer"` | GENERAL | 在用（BookshelfFragment1/2） |
| `TAG_RSS` | `"Rss"` | RSS | 在用（Rss/RssSearchModel/RssParserByRule/VideoPlay） |
| `TAG_CONTENT` | `"ContentProcess"` | READING | 在用（BookHelp/ContentProcessor/Rss 正文） |
| `TAG_SOURCE_MECHANISM` | `"SourceMechanism"` | SOURCE_NETWORK | 在用（SourceNetworkClient/SourceContentFilter 等 6 文件） |
| `TAG_IMAGE_CANVAS` | `"ImageCanvas"` | IMAGE | 在用（ImageCanvasViewModel/Adapter/Gallery） |
| `TAG_IMAGE_DETAIL` | `"ImageDetail"` | IMAGE | 在用（ImageDetailActivity/Adapter） |
| `TAG_IMAGE_PLAY` | `"ImagePlay"` | IMAGE | 在用（ImagePlay.kt） |
| `TAG_IMAGE_SNIFF` | `"ImageSniff"` | IMAGE | 在用（ImageUrlExtractor/ImageSnifferWebView） |
| `TAG_CRYPTO_SCOPE` | `"CryptoScope"` | SOURCE_NETWORK | 在用（SharedJsScope B1） |
| `TAG_DECOMPRESS` | `"Decompress"` | SOURCE_NETWORK | 在用（DecompressInterceptor B2） |
| `TAG_NETWORK_LOG` | `"HttpLog"` | SOURCE_NETWORK | 在用（NetworkLog B4） |
| `TAG_SEARCH_STORAGE` | `"SearchStorage"` | GENERAL | 在用（SearchBookDao B5） |
| `TAG_BOOK_ORIGIN_MIGRATE` | `"BookOriginMigrate"` | SOURCE_NETWORK | 在用（BookSourceEditActivity B6） |
| `TAG_SOURCE_RECYCLE_BIN` | `"SourceRecycleBin"` | SOURCE_NETWORK | 在用（SourceRecycleBinHelp B7） |
| `TAG_SPECIAL_CONTENT` | `"SpecialContent"` | READING | 在用（ContentProcessor B8） |
| `TAG_SHELF_PROGRESS` | `"ShelfProgress"` | READING | ⚠️ 死常量（0 调用点，B9 未打点） |
| `TAG_MEMORY_PRESSURE` | `"MemoryPressure"` | PERFORMANCE | 在用（MemoryPressure B13） |
| `TAG_CACHE_STATS` | `"CacheStats"` | PERFORMANCE | 在用（CacheManageViewModel B11） |
| `TAG_CACHE_CONCURRENT` | `"CacheConcurrent"` | PERFORMANCE | 在用（ConcurrentRateLimiter B12） |
| `TAG_WEBDAV_BACKUP` | `"WebDavBackup"` | GENERAL | 在用（AppWebDav 以字面量形式传参，值同常量） |
| `TAG_HIGHLIGHT_STYLE` | `"HighlightStyle"` | READING | 在用（HighlightRuleMatcher/CssStyleParser B15） |
| `TAG_THOUGHT_EXPORT` | `"ThoughtExport"` | READING | 在用（ThoughtObsidianExporter B16） |
| `TAG_SOURCE_SANDBOX` | `"SourceSandbox"` | SOURCE_NETWORK | ⚠️ 死常量（0 调用点，ng P0-S1 沙箱未以该 Tag 打点） |
| `TAG_SOURCE_DIALOG` | `"SourceDialog"` | SOURCE_NETWORK | 在用（JsExtensions P0-S3） |
| `TAG_SOURCE_CACHE` | `"SourceCache"` | SOURCE_NETWORK | 在用（SourceHelp/BookSourceCacheStore P0-S2） |
| `TAG_SOURCE_GUARD` | `"SourceGuard"` | SOURCE_NETWORK | 在用（BookSourceGuardLog P0-S4） |
| `TAG_IMG_DECRYPT` | `"ImgDecrypt"` | IMAGE | 在用（OkHttpStreamFetcher 图片加载/解密链路；log-compliance-cleanup 收编，**值沿用既有采集 tag 不变**） |
| `TAG_HLS_REMUX` | `"HlsRemux"` | VIDEO | 在用（HlsDownloader HLS 转封装诊断，正式诊断链只降频不删除） |
| `TAG_RSS_SOURCE_EDIT` | `"RssSourceEdit"` | RSS | 在用（RssSourceEditViewModel clearCookie） |
| `TAG_CRASH_REPORT` | `"CrashReport"` | GENERAL | 在用（MainActivity 崩溃栈回灌） |
| `TAG_DEVICE_INFO` | `"DeviceInfo"` | VIDEO | 在用（ExoPlayer DeviceInfoHelper 播放域设备信息） |

统计：**36 常量**（2026-09-11 log-compliance-cleanup 后：原 31 + 收编新增 5）；分布快照 = SOURCE_NETWORK 14 / READING 5 / IMAGE 5 / PERFORMANCE 3 / GENERAL 4 / RSS 2 / VIDEO 2 / TtsTrace 1；死常量 3（TAG_WEB_VIEW / TAG_SHELF_PROGRESS / TAG_SOURCE_SANDBOX，保留待后续分期接线，不删除）。

ai_tests 可通过 `adb logcat -s WebBook:E AnalyzeRule:E` 精确过滤模块日志；文件日志可按 Tag grep 定位模块。

## putDebugWithTag 使用指南

`putDebugWithTag(tag, message, throwable?, level?)` 方法用于带模块 Tag 的调试日志：

- **recordLog 守卫**：仅在 `AppConfig.recordLog` 开启时记录，关闭时直接 return 零开销，不影响用户功能
- **tag 透传**：tag 透传给 `LogUtils.d` 和 `Log.e`，实现模块级过滤
- **写入文件 + 内存 + logcat(DEBUG)**：recordLog 开启时写入文件日志（带 tag）+ 内存 mLogs + logcat（仅 DEBUG）

### 何时用 putDebugWithTag vs put

| 场景 | 使用方法 | 理由 |
|------|---------|------|
| catch 块异常补全 | `putDebugWithTag` | recordLog 关闭时零开销 |
| 关键操作成功/失败日志 | `putDebugWithTag` (level=INFO/WARN) | recordLog 关闭时零开销 |
| 关键参数日志 | `putDebugWithTag` (level=INFO) | recordLog 关闭时零开销 |
| 用户可感知的错误 | `put` (toast=true) | 始终记录 + Toast 提示 |
| 重要业务错误 | `putError` | 始终记录 |

### 使用示例

```kotlin
// catch 块异常补全
} catch (e: Exception) {
    AppLog.putDebugWithTag(AppLog.TAG_WEB_BOOK, "搜索失败: ${e.localizedMessage}", e)
}

// 关键操作成功日志
AppLog.putDebugWithTag(AppLog.TAG_WEB_BOOK, "搜索开始 page=$page", level = AppLog.Level.INFO)

// 关键参数日志
AppLog.putDebugWithTag(AppLog.TAG_HTTP, "请求路径=/path/{id} code=${response.code()}", level = AppLog.Level.INFO)
```

## 三维度日志覆盖要求

核心模块日志覆盖需满足三个维度：

### 维度1：catch 块日志（异常捕获）

- 所有 catch 块必须有 `AppLog.putDebugWithTag` 调用
- **例外**：CancellationException 重新抛出的 catch 块不需要（异常会重新抛出由上层处理）
- **不重复记录**：已有 AppLog.put/putError/putWarn 调用的 catch 块不重复添加
- 日志内容：模块 Tag + 操作描述 + 异常对象

### 维度2：关键操作成功/失败日志（操作流程）

- 在关键操作的方法入口/成功出口/失败分支添加 `putDebugWithTag`（level=INFO/WARN）
- 覆盖操作：搜索/详情/目录/正文/发现页/规则解析/HTTP请求/RSS请求/内容处理
- 日志内容：模块 Tag + 操作名称 + 关键结果（结果数量/响应码/耗时）

### 维度3：关键参数日志（参数传递）

- 在关键参数传递点添加 `putDebugWithTag`（level=INFO）
- 覆盖参数：URL构建结果/规则解析结果/网络响应状态码/RSS源URL/解析结果
- 日志内容：模块 Tag + 参数名 + 参数值（脱敏后）

### 脱敏原则（铁律）

- URL 只保留路径模式（`/path/{id}`），禁止输出完整 URL
- cookie/token/key/secret 隐藏为 `***`
- 源名称不记录，只记源 ID 编号
- 日志消息格式：操作描述 + 关键参数（脱敏后）
- **app.log 持久化门禁（C5 R4 预固化）**：C5 实施后 `AppLog.put` 系与 `putDebugWithTag` 将落盘 `filesDir/app.log`（重启可恢复、可复制外传），脱敏从"建议"升级为 persist 前置门禁——未经上述脱敏的消息禁止进入持久化路径；data URI 仍由 `truncateSafely` 专项截断；L2 测试断言 app.log 无 `cookie=`/data URI 全文/完整 URL 模式。

## 用户日志模块归属规范（C5 双层架构·预登记）

> 权威设计：`docs/specs/legadoc-benchmark-analysis/migration-designs/C5-logging-engineering.md`（§3.2 双层架构 / §4.1 LogModule / §7 规范提升点 1）。本节为其实施级**预登记**（2026-09-01，对应总线 tasks 2.8/X6）：`constant/LogModule.kt` 尚未实施（C5 分册 P1 登记为独立批次），实施时**照本节登记表执行**，并以 `LogModuleFromTagTest` 全量断言守护。

### 双层架构（共存不冲突三铁律）

1. **AI 层输出通道不变**：TAG 常量 → `LogUtils.d` + `Log.e(logcat)` 的采集链路是 ai_tests 契约（`adb logcat -s <TAG>:E`），C5 不触碰任何 tag 透传逻辑；勾选体系只作用于用户可见视图，与 logcat 采集正交。
2. **用户层归属自动兜底**：`AppLog.put/putError/putWarn/putInfo` 不要求调用方传模块，`classify(调用方类名)` 自动归属，未匹配归 GENERAL——存量调用点零改动。
3. **AI 埋点显式映射**：`putDebugWithTag` 走 `LogModule.fromTag(tag)` 显式映射表（即上节"归属模块"列），映射 miss 时 fallback `callerModule()`，不丢日志。

### classify 三原则（legadoC LogModule.kt 纪律沉淀）

1. **单点归类**：模块归属只在 `LogModule.classify` 单点判定，调用方零打标。
2. **钉定表防双命中**：跨关键词组双命中类（如 `textfile$JsExtensions` 同含 tts/source 词根）必须进 `pinnedByClassPrefix` 显式钉定表，**禁止靠 when 分支顺序裁决**；双命中类实施前经归属表驱动单测全量断言。
3. **未匹配归兜底**：classify 未命中一律归 GENERAL，保证不丢日志、不需逐调用点改写。

### fromTag 映射表登记与新增 Tag 规则（X6）

- 映射全集 = 上节 30 TAG 常量表（快照 2026-09-01）；**不锚定 26 TAG 历史基线**（分册头注总线修订 2026-08-31）。
- 新增/修改 TAG（ng P1/P2 等）按落地顺序顺延，同一次提交内同步：AppLog 常量 → 本文档两表 → fromTag 代码分支 → LogModuleFromTagTest 断言。
- 死常量（TAG_WEB_VIEW/TAG_SHELF_PROGRESS/TAG_SOURCE_SANDBOX）保留登记不删除，接线时直接按本表归属模块实现。

### 字面量 tag 调用点现状（~~实施时收编清单~~ ✅ 2026-09-11 已全部收编，log-compliance-cleanup 批次D）

| 字面量值 | 调用点 | 收编结果 |
|---------|--------|---------|
| `"WebDavBackup"` | AppWebDav.kt（3 处） | ✅ → `TAG_WEBDAV_BACKUP` 常量引用 |
| `"DeviceInfo"` | DeviceInfoHelper.kt（2 处，ExoPlayer 播放域） | ✅ → 新增 `TAG_DEVICE_INFO` 归 VIDEO |
| `"RssSourceEdit"` | RssSourceEditViewModel.kt（1 处） | ✅ → 新增 `TAG_RSS_SOURCE_EDIT` 归 RSS |
| `"CrashReport"` | MainActivity.kt（1 处，崩溃上报） | ✅ → 新增 `TAG_CRASH_REPORT` 归 GENERAL |
| `"ImgDecrypt"` | OkHttpStreamFetcher.kt（companion TAG，5 处裸 Log.e 同步收编） | ✅ → 新增 `TAG_IMG_DECRYPT` 归 IMAGE（值不变） |

> 实施时新增的收编注意：OkHttpStreamFetcher 原裸 `Log.e` 中 `url.take(80)` 属完整 URL 泄露，收编时同步改为长度化脱敏（`urlLen=`）。fromTag（C5）实施时按本表归属模块登记即可。
