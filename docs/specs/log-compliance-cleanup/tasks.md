# tasks.md - 日志合规清理与规范机制化

> 每个涉及"删除/清零/同步"声称的任务完成后必须附 Grep 证据（本 spec 自身的 AD-04 门禁，自食其果验证）。

## 1. 准备

- [x] 1.1 基线记录（工作区仅本 spec 文档与记忆文件变更）
- [x] 1.2 实证完成。**AOAdapt 日志**：
  - Action: Read CronetInterceptor 全量打点上下文
  - Observation: `protocolErrorCount++` 在去重 if 块**外**（:372），去重只包日志输出（:374-378）→ 收编不影响计数频率；logCertError/logNameNotResolved 为纯日志 helper；logNameNotResolved 与协议错误共享 lastLoggedError 但 key 前缀不同，putThrottled 天然隔离
  - Adapt: 三处均可安全 putThrottled；协议错误主日志补注释说明计数边界
  - **误报修正（AOAdapt）**：CronetHelper/NewCallBack/AbsCallBack/ACache/ZipUtils/ImageUtils 等的 `Log.x(` 匹配实为 `DebugLog.x(` **子串误报** → 无需改动；"上游白名单"改为"审计 Grep 排除 DebugLog/LogUtils 子串"纪律（AD-03 v1.1）；VideoPlay.kt 发现未使用 `import android.util.Log`（0 调用点）→ 删除

## 2. 核心实现

### 批次A：临时日志清零
- [x] 2.1 删 ExploreShowViewModel×2 / SearchViewModel×1 / RssArticlesViewModel×2 的 PageDebug AppLog.put（含对应注释）
  - 证据：Grep `PageDebug`（app/src/main/java）仅剩 SearchModel.kt 历史注释 1 处，0 活跃调用
- [x] 2.2 删 AnalyzeUrl×2 PageDebug putDebugWithTag（含注释；空 else 分支一并清理）
  - 证据：同上，全仓 PageDebug 活跃调用 = 0

### 批次B：裸 Log 收编
- [x] 2.3 AnalyzeUrl 3 处 Log.d → putDebugWithTag(TAG_ANALYZE)（参数解析失败=ERROR，retry=INFO，retry exhausted=ERROR）；import android.util.Log 删除
- [x] 2.4 AnalyzeRule 2 处 Log.d → putDebugWithTag(TAG_ANALYZE, Level.DEBUG)；import 删除
- [x] 2.5 AppLog.kt 新增 5 个 TAG 常量（TAG_IMG_DECRYPT/TAG_HLS_REMUX/TAG_RSS_SOURCE_EDIT/TAG_CRASH_REPORT/TAG_DEVICE_INFO）
  - 证据：Grep `const val TAG_` = 36（原 31）
  - AOAdapt：TAG_IMAGE_LOAD("ImageLoad") 改为 TAG_IMG_DECRYPT("ImgDecrypt")——OkHttpStreamFetcher 既有采集 tag 值不变，避免破坏采集链
- [x] 2.6 OkHttpStreamFetcher 5 处 Log.e → putDebugWithTag(TAG_IMG_DECRYPT)（loadData=INFO/inject Referer=INFO/onFailure=ERROR/skip decode×2=WARN）；URL 全部改长度化脱敏（urlLen=）；companion 原 TAG 字面量删除
- [x] 2.7 AppDatabase 2 处 → putDebugWithTag(TAG_DATA)（成功=INFO/失败=ERROR）；补 AppLog import
- [x] 2.8 HlsDownloader 9 处 → 类内单点 `remuxLog()` 助手统一 putDebugWithTag(TAG_HLS_REMUX)（过程=DEBUG，异常=ERROR）；import 删除
- [x] 2.9 ~~CronetHelper/NewCallBack 收编~~ → **审计误报，无需改动**（AOAdapt 见 1.2）
  - 证据：全仓 `^import android\.util\.Log$` 仅剩 AppLog.kt（三层体系自身）+ VideoPlay.kt（未使用，已删）
- [x] 2.9b VideoPlay.kt 未使用 Log import 删除（0 调用点，按"清理后不移除未使用定义"反模式处理）

### 批次C：频控与级别修正
- [x] 2.10 CronetInterceptor：logCertError/logNameNotResolved/协议错误打点 → putThrottled(level=WARN)；删 lastLoggedError/lastLoggedErrorTime/lastCertError/lastCertErrorTime/LOG_DEDUP_INTERVAL_MS；过时注释同步修正
  - 证据：文件 Grep `lastLoggedError|lastCertError|LOG_DEDUP_INTERVAL_MS` 仅剩收编说明注释
- [x] 2.11 HttpHelper：DNS retry → putThrottled("HttpHelper_dns_retry", level=DEBUG)；negative cache hit → putWarn；无效地址过滤 → putWarn；retry exhausted 保留 ERROR（最终失败）
  - 证据：DNS 路径无默认 ERROR 的 `AppLog.put(` 调用

### 批次D：字面量 tag 收编
- [x] 2.12 AppWebDav 3 处 → AppLog.TAG_WEBDAV_BACKUP；RssSourceEditViewModel → TAG_RSS_SOURCE_EDIT；MainActivity → TAG_CRASH_REPORT；DeviceInfoHelper×2 → TAG_DEVICE_INFO
  - 证据：Grep 4 个字面量仅剩 AppLog.kt 常量定义行 + LogUtils.kt 内部 Logger 名（非模块 tag，合法）

### 批次E：业务侧调试源日志（AD-06，用户指出的审计盲区补强）
- [x] 2.13 logging_rules.md 新增「第四通道：Debug」章节 + 四通道总表（定位/守卫/导出/脱敏豁免；不并入 AppLog 为用户裁决口径）；标题"三层日志体系"→"日志体系（三系统 + 业务侧第四通道）"
- [x] 2.14 AppLog 暴露 `internal fun recordLogEnabled()`（recordLogOrOff 只读快照）
- [x] 2.15 Debug.kt：会话环形缓冲（MAX_SESSION_LINES=500，log() 单点写入，cancelDebug 清空防跨会话串日志）+ `getSessionLogs()`；logcat 守卫放宽 `BuildConfig.DEBUG || AppLog.recordLogEnabled()`
- [x] 2.16 BookSourceDebugActivity：顶栏「导出日志」（R.string.log_export_logs）菜单 → 选择框（复制到剪贴板 sendToClip / 分享 txt cacheDir+FileProvider）
- [x] 2.17 RssSourceDebugActivity：同 2.16
  - 证据（2.13-2.17）：Grep `getSessionLogs` = Debug.kt + 2 个 Activity；Grep `第四通道` 命中 logging_rules.md
  - AOAdapt：MenuAction 无图标调用已核实（icon 默认 null）；导出文件名含时间戳防覆盖

## 3. 验证测试

- [x] 3.3 全量回归 Grep 四件套证据汇总：
  1. PageDebug：全仓活跃调用 = 0（仅 SearchModel 历史注释）
  2. 裸 Log：`^import android\.util\.Log$` 全仓仅 AppLog.kt（三层体系自身）
  3. TAG 常量：Grep `const val TAG_` = 36 = logging_rules 表行数
  4. 字面量 tag：4 个字面量仅剩常量定义行
- [x] 3.1 编译：`compileAppDebugKotlin` **BUILD SUCCESSFUL in 15m31s**（logs/compile_debugpage.log）(L1)
- [x] 3.2 单测：`testAppDebugUnitTest` 全量 **317 tests / 3 failed / 2 skipped**——3 个失败全部为 `RhinoClassShutterTest` guardLog 族，根因=**套件顺序性环境问题**（先前测试类在 appCtx 注入前毒化 AppConfig；该测试类头注自述此已知机制，其 assume 跳过设计印证）；**隔离运行该测试类 BUILD SUCCESSFUL**（logs/test_rhino_solo_tail.txt）→ 非本批回归 (L1)
  - 遗留登记：BookSourceGuardLog.observeClass 直取 AppConfig 无守卫，套件顺序敏感——记入 issues-found，既有问题不属本批 scope
- [ ] 3.4 真机/模拟器 L2：安装测试包，触发搜索（TAG_ANALYZE）、图片加载（ImgDecrypt）、DNS 弱网（HttpHelper）、Cronet 失败（putThrottled 节流）、调试源导出（复制/分享），`adb logcat -s` 分 tag 采集确认输出与级别 (L2)
- [ ] 3.5 用户打包验收（L2→L3，build-legado.bat 后交付）

## 4. 文档收尾

- [x] 4.1 logging_rules.md：TAG 表 31→36（5 新行含归属/用途）、字面量收编表标记已完成、新增条款五（验证期日志强制 DebugLog）/条款六（Grep 证据门禁 + 子串误报纪律）/第四通道章节与四通道总表（+结构化事件流说明，debug-page-redesign 联动）
  - 证据：Grep `const val TAG_` 计数=36 与文档表一致（文档行 36 行含 3 死常量）
- [x] 4.2 AGENTS.md 门禁第 4.2 条追加：删除/清零类完成声称必须附 Grep 证据 + 审计定性模式
- [x] 4.3 updateLog.md 已更新（第二十七批，编译前完成）
- [x] 4.4 docs/INDEX.md 已登记；issues-found.md 已建（6 项含 tasks 2.19 矛盾与 DebugLog 子串误报教训）
- [ ] 4.5 沉淀：编译验收通过后评估将"日志合规审计 Grep 套件"沉淀进 ai_tests 或 skill
