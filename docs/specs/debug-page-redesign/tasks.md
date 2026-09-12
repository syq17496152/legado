# tasks.md - 调试源页与调试日志重构

## 1. 准备
- [x] 1.1 MD3 三文件精读完成（Debug.kt/Screen/ViewModel），对标表落 README
- [x] 1.2 本仓实证：state 码产生点（BookList=10/BookInfo=20/BookChapterList=30/BookContent=40/RssParserByRule=10/Rss=20）；composeHost 宿主先例（AllBookmark/DownloadManage）；ThemeSpec 含 errorContainer/primaryContainer/tertiaryContainer
- [x] 1.3 **已核实（AOAdapt）**：`CompositeCoroutine.clear()` 逐个 `coroutine.cancel()`——FAB 停止经 cancelDebug() 真正取消调试协程 ✅

## 2. 核心实现
- [x] 2.1 `model/Debug.kt`：DebugEvent + events StateFlow(1000) + clearEvents()/clearSessionLogs()；log() 尾部发射（保留 callback/isChecking/校验链）
- [x] 2.2 两个调试布局瘦身为 compose_top_bar + compose_host
- [x] 2.3 `BookSourceDebugScreen.kt` 新增
- [x] 2.4 `RssSourceDebugScreen.kt` 新增
- [x] 2.5 两个 Activity 重写为宿主；两个 Model 去 Debug.Callback；删除两个 Adapter
- [x] 2.6 导出日志承接批次E（复制/分享 txt），导出内容=结构化事件渲染（getSessionLogs 含时间戳全文）
  - AOAdapt：①labelMediumEmphasized 本仓 typography 不支持 → labelMedium；②MenuBook 用 AutoMirrored（仓内有先例）③PowerShell Remove-Item 偶发失败需 try/catch 重试

## 3. 验证
- [x] 3.1 编译 `compileAppDebugKotlin` **BUILD SUCCESSFUL in 15m31s**（首编即过，logs/compile_debugpage.log）(L1)；警告修复后复编译 ✅（labelMediumEmphasized→labelMedium）
- [x] 3.2 单测 `testAppDebugUnitTest` 全量 317/3 failed/2 skipped——3 failed 为 RhinoClassShutterTest 套件顺序环境问题（隔离运行 SUCCESSFUL），非本批回归（见 log-compliance-cleanup tasks 3.2 证据）(L1)
- [ ] 3.3 真机/模拟器 L2：书源全链调试/过滤/全文/停止/导出 + 订阅源调试 + `adb logcat -s sourceDebug`
- [ ] 3.4 用户验收（对标 MD3 体验确认）

## 4. 文档收尾
- [x] 4.1 logging_rules.md 第四通道章节补"结构化事件流"说明
- [ ] 4.2 updateLog.md 已写（第二十七批）；docs/INDEX.md 登记；issues-found.md 记录
- [ ] 4.3 沉淀
