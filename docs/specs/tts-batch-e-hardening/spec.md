# spec.md — TTS 批次E 加固（tts-batch-e-hardening）

> 版本：v1.1 ｜ 更新时间：2026-09-10 ｜ 状态：🔄 设计中（v1.1 红队审查 21 项修正，详见 design §8）
> 上游：`docs/specs/optimize-tts-engine-phase2/spec.md`（期2）+ `code-review-20260910.md` 批次E 六项

## Intent

修复 TTS 全量审查遗留的六项收尾问题：AI 预生成缓存从未接线（P0）、保留名单进程重启失效导致预合成资产被驱逐（P1）、选角模板不在备份/首装链（F-1/F-2）、缓存键三件套散落两端（方向①）、TTS 第三方脚本可访问宿主类（方向⑤）。全部为存量加固，不改任何用户可见交互流。

## Scope

### In Scope（做什么）

- E1：`BaseReadAloudService.newReadAloud` 起播点接线 AI 预热（fire-and-forget，作业替换制，直调 `ensureCache`）。
- E2：`TtsPrebuildManager.reservedKeys` 落盘/回装/清除联动（cacheDir JSON，禁 Room）。
- E3：`ttsCastingTemplates.json` 进备份选择配置（`BackupSelectorConfig.allItems`）+ 导出 + 恢复（App 三通道全生效）。
- E4：`DefaultData.upVersion()` 接入内置模板导入 + `importBuiltinTemplates` builtin 内容变更覆盖刷新（E4b）。
- E5：键因子三件套（engineKey/speedKey/voiceKey）收编 `TtsCacheKeys` 门面；批量端标题解析抽帮助函数。
- E6：`RhinoClassShutter` 新增 TTS 脚本档，`io.legado.app.*` 实拦；`TtsScriptEngineClient` 切档。

### Out of Scope（不做什么）

- **段落级 seek 断点续读**：审查中相关条目不做，保持现有段落定位语义。
- **web 端 BackupController 模板同步**：本期不做（决策依据见 Approach/Alternatives 主题 C），App 端三通道（WebDav/云存储/本地）全生效即可。
- **Room v110 bump**：E2 名单禁用 Room 持久化，零 schema 变更。
- **KEY_VERSION 变更**：E5 纯收编，键算法/代际不动（v3 现行）。
- **书源档沙箱行为调整**：E6 对书源档零变化，仅新增 TTS 档。
- **P0-5 播放侧消费链**：单独立项，本期仅预热落库（现状播放链无 ensurePlayableCache 调用，预热是缓存表唯一写入方）。
- **内置模板规则内容修订**：E4b 只建"内容变更可刷新"的机制，不在本期改模板内容（assets JSON 零修改）。

## Approach

### Selected Approach（分主题）

| 主题 | 选定方案 |
|------|----------|
| E1 接线形态 | `newReadAloud` 的 `execute(IO)` 块内 `paragraphStartPos = pos`（:397）后新增独立预热方法，内部 `Coroutine.async{}.onError{}` fire-and-forget + 实例级 `preheatJob` 替换制（旧预热随章节切换取消）；**直调 `ensureCache`**（:411，v1.1 修正——不经 ensurePlayableCache 等待封装，其对 RUNNING 的等待语义不适配 fire-and-forget）；前置短路复用 `ensureCache` 内置短路（:417-424），调用侧零条件判断；取消 rethrow 前清理本次 RUNNING 行 + 预热路径 stage=STAGE_PREHEAT 跳过 keepAlive retain（均 v1.1） |
| E2 落盘形态 | `cacheDir/tts_prebuild/reserved_keys.json`（Map&lt;String,Long&gt; GSON），object `init` 走 scope.launch(IO) 异步回装（v1.1）；登记/移除/清空全部变更后立即同步落盘（v1.1 取消防抖，单文件小 IO 直写）；独立子目录避开 `removeCacheFile` 对 `cacheDir/httpTTS` 的清扫循环 |
| E3 备份恢复 | `BackupSelectorConfig.allItems` 追加模板项（v1.1 修正——`Backup.backupFileNames` 实测为全工程无消费方死列表，不接入）+ 导出 `writeListToJson(dao.all)` + 恢复 `fileToListT` 后 `TtsCastingStore.invalidateSnapshot()`（public 已核实 :134）；Dao insert=REPLACE（:30-31），追加式幂等，与 httpTTS 同构 |
| E4 首装链 | `LocalConfig` 新增 `needUpTtsCastingTemplates`（isLastVersion(1, "ttsCastingTemplatesVersion")）→ `DefaultData.upVersion()` 分支调 `importBuiltinTemplates`；E4b：已存在 builtin=true 记录且全字段（rulesJson+fallbackSourceJson+name+enabled+sortOrder，v1.1）与 assets 不同才 REPLACE 覆盖；`TTSReadAloudService.onCreate` 现有调用保留为兜底（双保险） |
| E5 收编形态 | `TtsCacheKeys` 新增门面 `speakFileName(engineId, speechRate, voiceId, chapterIndex, chapterTitle, unitText)`（因子字符串化收进单源），底座 `ttsSpeakFileName` 原样冻结；两端调用点改传显式因子；批量端标题解析抽 `resolveChapterTitle` 帮助函数（留 TtsPrebuildManager，保持 TtsCacheKeys 零实体依赖） |
| E6 档位实现 | `RhinoClassShutter` 新增独立 `withTtsScriptClassPolicy`（ThreadLocal 深度，复用 label 位）；判定链在 D11 实拦集之后、按 TTS 档优先分支：`io.legado.app.*` → `onBlockClass` + return false；书源档分支逐字节保留；`TtsScriptEngineClient.evalFunction`（:115）切至 TTS 档 |

### Alternatives Considered

**主题 A：E1 预热挂点与形态**

| 备选 | 结论 | 理由 |
|------|------|------|
| A1 起播主链内 await `ensurePlayableCache` | ❌ 拒绝 | `requestBatchedUnitAssignments`（:1350）走 LLM 网络分配，秒级耗时，阻塞起播违背 P0 初衷 |
| A2 ReadBook/Activity 层挂点 | ❌ 拒绝 | 章节切换多入口（翻页/跳章/自动续播），服务层 `newReadAloud` 是唯一必经收口；且 contentList/textChapter 同生命周期在此 |
| A3 `execute` 块内 fire-and-forget + 作业替换制（选定） | ✅ | 起播零阻塞；替换制天然实现"章节切换取消旧预热"；`Coroutine` 封装 :182-183 已放行取消异常，onError 不误报 |
| A4 全书预取（预热 N 章） | ❌ 本期不做 | 超出 P0-5 范围（当前章预热），成本/收益未验证，留后续迭代 |

**主题 B：E2 名单持久化载体与写入时机**

| 备选 | 结论 | 理由 |
|------|------|------|
| B1 Room 新表 | ❌ 禁止 | Room v110 冻结（上游 AD-13 硬约束），名单可重建不配入库 |
| B2 SharedPreferences | ❌ 拒绝 | 数百键的 Map 语义不适配 SP；项目先例为 cacheDir 单 JSON 文件 |
| B3 cacheDir/tts_prebuild/ 单 JSON（选定） | ✅ | 先例 ThemePackageManager/EpubCoreDiskCache；独立子目录避开 httpTTS 清扫循环（列表里出现 .json 会被 >10min 驱逐误删） |
| B3a 写入时机：变更后立即同步落盘（选定，v1.1 翻转） | ✅ | 单文件小（数百 KB 上限）IO 直写可行；登记频率=合成完成频率（每单元一次 rename 本身伴随文件 IO）不构成新瓶颈；立即写消除"名单已更新但未落盘"的脱节窗口 |
| B3b 写入时机：登记防抖 500ms 合并（v1.0 原选定） | ❌ 推翻（v1.1 红队 R3-1/R5-1） | 防抖窗口是名单-文件脱节面，叠加 runTask :341 按名单 containsKey 的幂等判定→文件被外部删除后 DONE 虚报（名单命中跳过合成但播放时文件缺失） |

**主题 C：E3 web 端 BackupController 是否同步**

| 备选 | 结论 | 理由 |
|------|------|------|
| C1 本期同步加 web 端 stage* | ❌ 明示不做 | web 端独立实现（api/controller/BackupController.kt:271-274，不走 backupFileNames）；模板管理为 App 端功能，web 端无消费场景；强行同步徒增双端对齐成本 |
| C2 本期只改 App 端两文件（选定） | ✅ | Backup.kt+Restore.kt 两处改动，WebDav/云存储/本地三通道经 `Restore.restoreLocked` 全部生效（AppWebDav.kt:124-131/AppCloudStorage.kt:98/BackupConfigFragment.kt:108 已核实汇入点）；遗留明示：经 web 端发起的备份不含模板文件，恢复侧 `fileToListT` 对缺失文件静默跳过，无报错面 |
| C3 恢复采用"先删后插" | ❌ 拒绝 | 会清掉设备端已有自定义模板；追加式 REPLACE 与 httpTTS 同模式，不丢数据 |

**主题 D：E4 builtin 记录升级覆盖策略**

| 备选 | 结论 | 理由 |
|------|------|------|
| D1 维持 skip-only（现状） | ❌ 拒绝 | 内置模板规则修正无法随版本到达存量设备，只能等卸载重装 |
| D2 无条件覆盖 builtin | ⚠️ 部分 | 方向正确但缺变更判定，每次启动无谓写库 |
| D3 内容 hash 比对（rulesJson+fallbackSourceJson 逐字节比对）不同才 REPLACE，仅限 existing.builtin==true（选定） | ✅ | builtin 记录对用户只读（编辑器禁改），覆盖零用户数据损失；内容相同零写入；用户自建记录（builtin=false）撞内置 id 时维持 skip 保护 |

**主题 E：E5 收编落点**

| 备选 | 结论 | 理由 |
|------|------|------|
| E5-1 新建 PrebuildKeyFactory 独立对象 | ❌ 拒绝 | 制造第二权威源，违背"键单源"上游契约 |
| E5-2 `TtsCacheKeys.speakFileName` 门面 + `ttsSpeakFileName` 底座冻结（选定） | ✅ | 因子字符串化（Long→String/Int→String）与 md5 组装全收单对象；两端产出与现行逐字节一致（KEY_VERSION v3 不动） |
| E5-3 resolveChapterTitle 放 TtsCacheKeys | ❌ 拒绝 | 需引入 Book/BookChapter/ContentProcessor 依赖，污染纯函数键对象；留 TtsPrebuildManager 私有帮助函数 |

**主题 F：E6 TTS 档实现方式**

| 备选 | 结论 | 理由 |
|------|------|------|
| F1 `withBookSourceClassPolicy` 加 mode 参数 | ⚠️ 备选 | 改动小但污染既有签名语义，全部书源调用点需回读参数含义 |
| F2 独立 `withTtsScriptClassPolicy`（选定） | ✅ | 语义清晰；书源路径零触碰；两档 ThreadLocal 独立，嵌套时"deny 取并集"由判定链顺序天然保证 |
| F3 app 前缀白名单化（放行必要类） | ❌ 拒绝 | 内置 4 模板宿主能力审计零命中（纯 JS 无 Packages/java.lang 访问），脚本协议网络由宿主代理（synthesize 返回请求对象）；app 前缀全拦最安全且免白名单维护（非 app 前缀 Java 类维持放行现状，v1.1 措辞修正） |
| F4 TTS 档不拦 app 前缀（维持现状） | ❌ 拒绝 | 即审查确认的风险面：第三方脚本可触达 AppConfig/ReadBook 等宿主单例 |

### Drawbacks（选定方案代价）

- E1：预热与起播链并发启动，AI 关闭场景零成本，但 AI 开启场景预热失败无用户可见反馈（有意静默，起播链自身兜底不变）；取消时需 rethrow 前清理 RUNNING 行（v1.1，直调 ensureCache 已消除等待空转面）。
- E2：init 异步回装（scope.launch(IO)，回装完成前名单为空=与现状一致）；登记逐次直写（单文件小，登记频率=合成完成频率）；名单条目带 30 天 prune，超龄条目回装时丢弃（产物已大概率被时长策略清理）；删书后名单残留使孤儿产物保护期延长至 prune（v1.1 登记，与上游 P2-5 叠加）。
- E3：经 web 端发起的备份不含模板文件（C2 遗留）；恢复为追加式，备份里的旧版本模板会覆盖同名 id 的新版本（与 httpTTS 同语义，可接受）。
- E4：同版本覆盖安装（prefs 保留）不触发 upVersion；卸载重装（versionCode=0）必触发；onCreate 兜底双保险（v1.1 修正表述）；isLastVersion"检查即写"→assets 导入失败后门控被消耗，靠 onCreate 兜底。
- E5：门面与底座双签名并存，新增键维度时需同步两处注释锚点（单文件内，可控）。
- E6：第三方 TTS 脚本若实际依赖宿主类将开始失败（内置模板零命中，生态内尚无此类脚本；失败经 onError 明示）。

## Requirements

> 级别沿用审查报告（P0/P1/P2/P3）。存量迭代场景用 Delta 描述（相对 5a10b01 现状）。

### R1（P0-5）AI 预热接线（E1）

Delta：`newReadAloud` 起播准备完成后新增一次 fire-and-forget 的 `AiReadAloudRoleService.ensureCache(book, textChapter, contentList, STAGE_PREHEAT)` 调用（v1.1：直调 ensureCache，非 ensurePlayableCache 等待封装）；不阻塞起播、不改变任何现有起播/播放行为；前置短路全部由 `ensureCache` 内部承担（AI 开关/模型配置/baseUrl/空书/空章/空段落）。

#### Scenario

- WHEN AI 多角色已开启且模型可用，用户起播第 N 章 THEN 预热作业在起播链外启动，`play()` 不等待预热；AI 分配缓存（aiReadAloudRoleCache 表）在朗读推进到该章前已可命中（STATUS_SKIPPED+segmentCount>0 或 SUCCESS）。
- WHEN AI 多角色未开启（或模型未配置/baseUrl 空） THEN 预热调用在 `ensureCache` 首个短路即返回 STATUS_SKIPPED，无网络请求、无 DB 写入，起播链零感知。

### R2（P0-5）AI 预热取消安全与幂等（E1）

Delta：预热作业持有于服务实例级 `preheatJob`；章节切换触发 `newReadAloud` 时取消旧作业再发起新作业；取消 rethrow 前清理本次已写入的 RUNNING 行（v1.1）；失败仅 TtsTrace/AppLog 留痕（EnsureResult 按 status 记 INFO/WARN，v1.1）。

#### Scenario

- WHEN 章节切换（旧预热作业仍在网络分配中）THEN 旧作业经 `Job.cancel()` 取消；取消异常被 `Coroutine` 封装守卫（:182-183）放行，不进入 onError、不落错误日志、不崩溃；rethrow 前本次已写入的 RUNNING 行被置终态 CANCELLED（v1.1），DB 无残留。
- WHEN 预热网络失败（超时/限流）THEN 结果仅写入 TtsTrace（INFO/WARN 按 status），不弹 toast、不打断朗读；同章再次起播时预热重试（`ensureCache` 幂等语义不变）。

### R3（P1-3）保留名单落盘与回装（E2）

Delta：`reservedKeys` 增加持久化副本 `cacheDir/tts_prebuild/reserved_keys.json`；object init 走 scope.launch(IO) 异步回装（v1.1）；登记/移除后立即同步落盘（v1.1 取消防抖）；runTask 幂等判定改"名单命中且目标文件存在"联合判定（v1.1）。

#### Scenario

- WHEN 批量预合成任务产出 N 个文件（逐单元 rename 成功登记）THEN 名单变更后立即同步落盘（无合并窗口），JSON 内容为 key=文件名（不带 .mp3）、value=登记时间戳。
- WHEN 目标文件已被外部删除（系统清理/用户手动删）但名单仍命中 THEN runTask 联合判定不通过→重新合成，DONE 不虚报（v1.1）。
- WHEN 进程被杀后重启，用户切到非当前章触发 `removeCacheFile` 清理 THEN 回装后的名单命中跳过清理，>10min 的预合成产物不被驱逐（与进程内行为一致）。

### R4（P1-3）名单清除联动与损坏容错（E2）

Delta：`cancelAllAndClearReserved`/`removeReserved` 与落盘同步（均立即写）；JSON 损坏静默重建；`removeReserved` 现为零调用死 API，其落盘联动为防御性接线（v1.1 注记）。

#### Scenario

- WHEN 用户在缓存管理页整目录清理（触发 `cancelAllAndClearReserved`）THEN 内存名单清空且 JSON 立即同步写为空对象；重启后无残留名单。
- WHEN JSON 文件损坏（截断/非法内容）THEN 解析失败仅 AppLog 留痕，回装为空名单重建文件，后续登记正常落盘，不崩溃不阻塞预合成。

### R5（F-1）选角模板备份与恢复（E3）

Delta：`BackupSelectorConfig.allItems` 追加模板选择项（v1.1：`backupFileNames` 实测为死列表不接入）；导出与恢复各增一分支（恢复分支异常隔离，单文件损坏不中断后续项，v1.1），恢复后失效 TtsCastingStore 快照。

#### Scenario

- WHEN 用户执行备份（三通道任一）且勾选模板文件 THEN 备份包内出现 `ttsCastingTemplates.json`，内容为 `ttsCastingTemplateDao.all()` 全量（含 builtin 与自定义）。
- WHEN 用户恢复含该文件的备份 THEN 模板按 id REPLACE 追加（不删除设备已有记录），激活 id 悬空时走既有 null 兜底；恢复完成后下一次 `resolveActiveRuleSet` 强制重读 Room（快照失效生效）。

### R6（F-2/E4b）内置模板首装链与升级刷新（E4）

Delta：`LocalConfig.needUpTtsCastingTemplates` 旗标 + `DefaultData.upVersion()` 分支导入 + `importBuiltinTemplates` 覆盖策略增强；`TTSReadAloudService.onCreate` 现有导入保留为兜底。

#### Scenario

- WHEN 全新安装首次启动（upVersion 门控命中）THEN 无需进入朗读页，模板管理页即可见 4 个内置模板（assets `defaultData/tts/castingTemplates.json` 导入）。
- WHEN 版本升级后 assets 模板内容发生变化（全字段 rulesJson/fallbackSourceJson/name/enabled/sortOrder 任一与已存 builtin 记录不同，v1.1）THEN 对应 builtin 记录被 REPLACE 刷新；内容未变的记录零写入；用户自建记录不受影响。

### R7（方向①）键因子三件套收编（E5）

Delta：`TtsCacheKeys` 新增 `speakFileName(engineId: Long?, speechRate: Int, voiceId: String, ...)` 门面；播放端 `md5SpeakFileName`（:534）与批量端键组装（:296）改走门面；产出与现行逐字节一致。

#### Scenario

- WHEN 同一（引擎 id，语速，音色，章索引，章标题，文本）输入分别从播放端与批量端生成键 THEN 两端文件名完全一致（存量缓存命中率不变），且 `KEY_VERSION` 保持 "v3"。
- WHEN 批量端组装键 THEN 章节标题经 `resolveChapterTitle` 帮助函数解析（与原 :264-268 内联逻辑同口径），播放端保留 `textChapter.title` 已是 displayTitle 口径的注释锚点。

### R8（方向⑤）TTS 脚本档收紧（E6）

Delta：`RhinoClassShutter` 新增 TTS 档包裹函数；TTS 档内 `io.legado.app.*` 从"观察放行"改"实拦"；书源档判定行为逐字节不变；`TtsScriptEngineClient.evalFunction` 切换 TTS 档。

#### Scenario

- WHEN 第三方 TTS 脚本执行中访问 `io.legado.app.help.config.AppConfig` 等宿主类 THEN 访问被拒绝（ClassShutter return false），`classAccessObserver.onBlockClass` 回调留痕；脚本该次求值失败走既有 onError 明示。
- WHEN 书源 JS 在书源档下访问同一宿主类 THEN 行为与现状完全一致（观察放行 + onObserveClass 计数），D11 实拦集（CookieManager/CookieSyncManager）两档内均实拦不变。

## 验收标准

1. **L1 编译与单测**：`./gradlew assembleAppDebug` 通过；`./gradlew test` 通过，含新增/扩充的 `TtsCacheKeysTest`（三件套门面逐字节断言）、`TtsClassShutterPolicyTest`（JVM 判定链四象限断言）、名单落盘纯逻辑测试（立即写/prune/损坏容错）。
2. **L2 模拟器场景**（按 tasks.md 3.x 逐项留 TtsTrace 证据）：
   - E1：AI 开启起播后 AppLog 出现预热启动与缓存命中证据；AI 关闭起播无异常日志。
   - E2：预合成→`adb shell am force-stop` 重启→切换非当前章→产物保留（AppLog"保留名单防护"证据）。
   - E3：备份→清数据→恢复→模板管理页列表还原、激活模板朗读行为正常。
   - E4：卸载重装首启即模板管理页可见内置 4 模板；升级场景覆盖刷新仅命中变更记录。
   - E5：播放端与批量端同参数键一致（既有缓存命中证据）。
   - E6：导入含宿主类访问的测试脚本执行被拒（onBlockClass 日志），内置 4 模板朗读回归无异常。
3. **回归红线**：书源 JS 沙箱行为零变化；`updateLog.md` 按六项逐条追加（编译前完成）；Room schema 快照无 diff。
