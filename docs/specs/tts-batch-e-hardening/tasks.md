# tasks.md — TTS 批次E 加固（tts-batch-e-hardening）

> 状态：🔄 设计中 ｜ 依据：design.md §3 子系统详设 + §5 异常矩阵 ｜ 核心任务标 ★ 验证标准
> 实施前置：本清单为 OpenSpec 步骤 5 输入；实施中逐条勾选，禁止跳步合并。

## 一、准备

- [ ] 1.1 通读 design.md §0 阅读指南与 §1 背景三项核实结论（invalidateSnapshot public / Dao REPLACE / isLastVersion per-key 计数器），确认与当前工作树一致（行号漂移时先校准再动手）
- [ ] 1.2 `git log --oneline -1` 确认基线为 5a10b01 或其后代；`git status` 确认工作树干净
- [ ] 1.3 核对设计假设三个快照点未漂移：`TtsPrebuildManager.reservedKeys`（:64）、`HttpReadAloudService.removeCacheFile`（:628）、`RhinoClassShutter.visibleToScripts`（:240）
- [ ] 1.4 确认 Room v110 冻结红线与 KEY_VERSION=v3 红线在本次任务中零触碰（本任务无 schema/键算法变更）
- [ ] 1.5 基于真实变更预写 updateLog.md 六条目占位（正式定稿在编译前，强制规则 1）

## 二、核心实现

### E1 AI 预热接线（P0-5）

- [ ] 2.1 BaseReadAloudService：新增 `preheatJob` 字段与 `preheatAiRoleCache(book, textChapter, paragraphs)` 方法（design §3.1 签名；**直调 `ensureCache` 传 STAGE_PREHEAT**，非 ensurePlayableCache 等待封装；block 内消费 EnsureResult 按 status 写 TtsTrace——INFO 预热完成/INFO SKIPPED 短路原因/WARN FAILED；catch CancellationException 于 rethrow 前调服务侧清理 RUNNING 行；onError 内 AppLog 留痕、禁止 runCatching 包裹整体）
  ★ 编译通过；AI 关闭时起播链零新增日志噪音；模拟器验证 EnsureResult 三类 status 日志分级正确（INFO/WARN）
- [ ] 2.2 BaseReadAloudService.newReadAloud：`paragraphStartPos = pos`（:397）后插入 `preheatAiRoleCache(ReadBook.book, textChapter, contentList)`
  ★ 模拟器 AI 开启起播：AppLog 出现预热启动证据且 play() 不被阻塞（起播耗时与改前同量级）
- [ ] 2.21 AiReadAloudRoleService：核实是否有现成单条清理 API；无则补 `cancelRunningCacheRow(bookUrl, chapterIndex)` 最小实现（DB 查 RUNNING 行写终态 CANCELLED，供 2.1 取消路径调用）（v1.1 红队新增）
  ★ 模拟器：预热中切换章节→DB 无 RUNNING 残留行（force-stop 后重启查询验证）
- [ ] 2.22 AiReadAloudRoleState 增 `STAGE_PREHEAT` 常量 + AiReadAloudRoleService 的 `AiTaskKeepAlive.retain` 调用点（:658-662）按 stage 分档：STAGE_PREHEAT 跳过 retain（若 retain 调用链拿不到 stage 则将 stage 参数下传）（v1.1 红队新增）
  ★ 模拟器：预热全程无前台服务拉起/通知刷新（notifyService 零触发），朗读消费路径 retain 行为不变

### E2 名单落盘（P1-3）

- [ ] 2.3 TtsPrebuildManager：新增 reservedFile/reservedSaveLock 成员 + `init { scope.launch(IO) { loadReservedKeys() } }`（v1.1 异步回装，首访主线程不读盘）+ loadReservedKeys（TypeToken 反序列化+30 天 prune+损坏容错）
  ★ JVM 单测：空文件/合法 JSON/损坏 JSON 三分支行为符合 design §5-E2 矩阵
- [ ] 2.4 TtsPrebuildManager：writeReservedKeys（synchronized 快照+GSON+temp+rename 原子写），登记/移除/清空变更后经 scope.launch(IO) 立即派发直写（v1.1 取消防抖，无合并窗口）
  ★ JVM 单测：登记后即触发写（无 delay）；temp+rename 失败回退 copyTo(overwrite) 路径；锁内快照取最新状态
- [ ] 2.5 联动接线：:365 登记后立即落盘、:169 cancelAllAndClearReserved 立即落盘、:175 removeReserved 落盘（注：removeReserved 现为零调用死 API，本联动为防御性接线；验证以 cancelAllAndClearReserved 的 CacheManageViewModel.kt:76 实调点+runTask :365 登记点为准）；runTask :341 幂等判定改 `reservedKeys.containsKey(...) && hasTargetFile(...)` 联合判定（名单在但文件被外部删除时重新合成，防 DONE 虚报）（v1.1）
  ★ 模拟器：预合成后 force-stop 重启→切非当前章→产物保留（AppLog"保留名单防护"证据）；手动删产物文件→runTask 重新合成（DONE 不虚报）
- [ ] 2.6 确认 `cacheDir/tts_prebuild/` 独立于 `cacheDir/httpTTS` 清扫循环（目录名与 removeCacheFile 遍历根无交集）

### E3 模板备份/恢复（F-1）

- [ ] 2.7 BackupSelectorConfig.kt：allItems 在 httpTTS 项（:41）后追加 `BackupItem("ttsCastingTemplates", "ttsCastingTemplates.json", "选角模板", "数据库")`（v1.1：`Backup.backupFileNames` 实测为全工程无消费方死列表，**不接入**）；Backup.kt 导出分支 `writeListToJson(appDb.ttsCastingTemplateDao.all, ...)`（:472-474 httpTTS 块后同构，selectedFiles 走 getSelectedFileNames() 选择集）
  ★ 验证注明：`writeListToJson` 空列表不写文件（Backup.kt:690），零模板用户备份包无该文件属预期行为（v1.1）
- [ ] 2.8 Restore.kt：httpTTS 恢复块（:163-165）后追加模板恢复分支（try/catch 异常隔离——单文件损坏仅留痕跳过不中断 restoreLocked 后续项，catch 显式重抛 CancellationException）+ `TtsCastingStore.invalidateSnapshot()`
  ★ 模拟器回环：备份→清数据→恢复→模板管理页列表还原、激活模板朗读正常、恢复后热生效（无需重启）；损坏 JSON 注入→恢复其余文件不受影响

### E4 模板首装链 + 升级刷新（F-2/E4b）

- [ ] 2.9 LocalConfig：新增 `needUpTtsCastingTemplates`（isLastVersion(1, "ttsCastingTemplatesVersion")），注释注明 per-key 计数器语义
- [ ] 2.10 DefaultData：upVersion 分支 + `importDefaultTtsCastingTemplates()`（assets `defaultData/tts/castingTemplates.json`，runBlocking(IO) 对齐 importDefaultHttpTTS 风格）
  ★ 模拟器卸载重装：首启不进朗读页，模板管理页即见内置 4 模板
- [ ] 2.11 TtsCastingStore.importBuiltinTemplates 覆盖增强（E4b）：existing.builtin==true 且全字段（rulesJson/fallbackSourceJson/name/enabled/sortOrder，实体字段集 TtsCastingTemplate.kt:17-23，v1.1）有差异才 REPLACE；builtin=false 撞 id 维持 skip
  ★ JVM 单测：未变记录零写库（skipped 计数）；变更 builtin 记录被刷新（imported 计数）；自定义记录不被覆盖
- [ ] 2.12 确认 TTSReadAloudService.onCreate（:87-98）兜底调用保留未动

### E5 键因子三件套收编（方向①）

- [ ] 2.13 TtsCacheKeys：新增 `speakFileName(engineId, speechRate, voiceId, ...)` 门面（底座 ttsSpeakFileName 与 KEY_VERSION 冻结不动）
- [ ] 2.14 HttpReadAloudService.md5SpeakFileName（:534）改走门面；补"textChapter.title 已是 displayTitle 口径"注释锚点
- [ ] 2.15 TtsPrebuildManager：:296 键组装改走门面；:264-268 displayTitle 解析抽 `resolveChapterTitle` 帮助函数
  ★ TtsCacheKeysTest：同输入双端逐字节一致 + 门面/底座等价断言（含 engineId=null→空串边界）
- [ ] 2.16 模拟器回归：改前预合成的产物改后仍可命中播放（存量缓存零失配）

### E6 Rhino 沙箱 TTS 脚本档（方向⑤）

- [ ] 2.17 RhinoClassShutter：新增 ttsScriptPolicyDepth ThreadLocal + `withTtsScriptClassPolicy`（finally 恢复，复用 label 位）
- [ ] 2.18 RhinoClassShutter.visibleToScripts：档位段按 design §3.6 重写（前置 matcher 与 D11 语义保持；书源档分支逐字节零变化）
  ★ TtsClassShutterPolicyTest 四象限：TTS 档 app 前缀=false / 书源档 app 前缀=true / 两档 CookieManager=false / 无档 String=true
- [ ] 2.19 TtsScriptEngineClient.evalFunction（:115）切 `withTtsScriptClassPolicy`；:113 既有注释同步更新（withBookSourceClassPolicy→TTS 档函数名，v1.1 红队 R4-03）；文件头注增补契约"禁止向 bindings 注入 Java 对象"（RhinoWrapFactory visibleToScripts 重载 :56/:68 不经档位判定链，v1.1 红队 R5-3）
  ★ 模拟器：内置 4 模板朗读回归正常；含宿主类访问的测试脚本执行被拒（onBlockClass 日志）
- [ ] 2.20 逐行 diff 复核 :240-259 重写前后书源档路径等价性（评审自查项，记录到 issues-found.md）

## 三、验证

- [ ] 3.1 L1：`./gradlew assembleAppDebug` 编译通过 + `./gradlew test` 全量单测通过（含 2.3/2.4/2.11/2.15/2.18 新增断言）
- [ ] 3.2 Lint 自查：Grep `android.util.Log.d|android.util.Log.e` 确认零残留调试日志（logging-during-refactoring.md）
- [ ] 3.3 L2 模拟器六项场景巡检（测试包 `io.legado.miss.app.debug`，按 ai_tests SOP）：
  - [ ] 3.3.1 E1：AI 开启起播预热命中证据 / AI 关闭零感知
  - [ ] 3.3.2 E2：force-stop 重启后名单防护证据（2.5 复验）
  - [ ] 3.3.3 E3：备份/恢复回环（2.8 复验）+ 三通道之一（WebDav 或本地）实测
  - [ ] 3.3.4 E4：首装即见模板 + 模拟升级场景覆盖刷新
  - [ ] 3.3.5 E5：播放端/批量端键一致、存量缓存命中（2.16 复验）
  - [ ] 3.3.6 E6：TTS 档实拦日志 + 书源 JS 回归（书源解析正常，沙箱零行为漂移）
- [ ] 3.4 真机听感抽验（听感项不在本期验收口径，仅记录不阻断）
- [ ] 3.5 issues-found.md 记录全部真机/模拟器问题（real-device-test-reuse.md）

## 四、收尾

- [ ] 4.1 updateLog.md 定稿：六项逐条面向用户语言，追加在 `## cronet版本:` 之后（编译前完成）
- [ ] 4.2 文档同步：本目录 README/spec/design 状态 🔄 设计中→✅ 已实施（附验证证据链接）；INDEX.md 条目状态同步
- [ ] 4.3 经验沉淀：E1 取消守卫复用（Coroutine.kt:182-183）与 E2 独立目录免疫清扫循环两条写入经验索引（spec-sedimentation-mechanism.md）
- [ ] 4.4 `git diff` 全量审计：变更范围与 File Changes 清单（design §6）逐文件对照，无越界改动
- [ ] 4.5 按项目流程提交（用户确认后执行，禁止自行 commit）
- [ ] 4.6 removeReserved 调用点是否随删书联动补齐=另立项核实（本期仅防御性接线，v1.1 红队 R2-3+R1-05 裁决）；孤儿产物保护期延长至 prune 场景与上游 P2-5 叠加，登记后续专项（v1.1 红队 R1-04）
