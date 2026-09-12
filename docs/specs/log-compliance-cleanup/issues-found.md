# issues-found.md - 日志合规清理（log-compliance-cleanup）

> 2026-09-11 审计与实施期发现，非全部为真机问题，含流程教训。

| # | 问题 | 来源 | 状态 |
|---|------|------|------|
| 1 | PageDebug 临时日志在 real-device-bugfix-0911 tasks 2.19 声称"已删"后仍残留 5 文件 7 处（其中 5 处 ERROR 级 AppLog.put）——完成声称与代码现实矛盾 | 本次审计 | ✅ 本批清零；机制化对策=logging_rules 条款六（Grep 证据门禁） |
| 2 | 审计 Grep `Log\.(d|e)\(` 将 DebugLog.x()/LogUtils.x() 子串误报为裸 Log 违规（CronetHelper/NewCallBack/ACache 等 10+ 文件）| 实施期实证 | ✅ AD-03 v1.1：审计以 `^import android\.util\.Log$` 定性，已写入条款六 |
| 3 | OkHttpStreamFetcher 裸 Log.e 输出 `url.take(80)` 属完整 URL 泄露（脱敏铁律盲区） | 本次审计 | ✅ 收编时改长度化脱敏 |
| 4 | CronetInterceptor 手写日志去重与降级计数曾疑似耦合 → 实证 protocolErrorCount++ 在去重块外，解耦安全 | 实施期（红队 R5 检查点） | ✅ 已收编 putThrottled |
| 5 | 业务侧调试源日志（Debug/sourceDebug）是 logging_rules 盲区：无登记、release 不可采集、无导出 → 用户指出后补第四通道章节 + 会话缓冲 + 导出 + recordLog 采集 | 用户指出 | ✅ 并由 debug-page-redesign 深化 |
| 6 | VideoPlay.kt 未使用 `import android.util.Log`（0 调用点） | 实施期 | ✅ 已删 |
| 7 | **既有套件顺序 flakiness**（非本批回归）：全量 `testAppDebugUnitTest` 中 RhinoClassShutterTest guardLog 族 3 用例失败——先前测试类在 appCtx 注入前触发 `AppConfig.<clinit>`（splitties 反射 ActivityThread，纯 JVM 无此类）毒化 AppConfig，`BookSourceGuardLog.observeClass:40` 直取 `AppConfig.bookSourceClassPolicyLog` 无守卫即抛 NoClassDefFoundError；隔离运行该测试类 SUCCESSFUL 实证 | 全量单测 | ⚠️ 既有问题，建议后续给 observeClass/实拦日志路径加 recordLogOrOff 式 try 守卫或套件级 appCtx 注入 |
