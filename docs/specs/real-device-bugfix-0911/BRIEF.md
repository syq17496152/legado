# 批次F 设计简报（real-device-bugfix-0911）

> 本文件是四文档（README/spec/design/tasks）的统一事实源。所有根因均经代码级实证（文件:行号）+ 用户真机日志实锤双重验证。
> 用户裁决：八项全量一批处理（一个 openspec 立项，分批次 A/B 实施，一次测试包验收）。
> 用户总要求：优化 bug 必须带脑子了解根因，正向优化（保证功能越来越好用越来越稳定，不是规避日志）。

## 用户原始八项反馈

1. 下载视频 mp4 文件无后缀→软件内无法播放；手动改后缀后其他播放器正常
2. 书源管理/订阅源管理列表底部新增"全选/反选"底部操作区，与长按弹出的全选反选重叠，要求去掉
3. 内置对话高亮：短对话能匹配、长对话匹配不到；编辑正则后好像不生效（不应该即时生效吗）
4. 内置高亮规则只有 12 条，要求对标开源+自研扩充，配置合理（默认开关要想好）
5. 真机日志深度分析：列表解密图片时好时坏（疑图片绘制缓存设置影响）；Cronet 开关效果不一；疑 DoH 的锅；要求正向优化
6. 其它设置"清除缓存/清除 WebView 数据"与精准管理缓存管理重复→整合或删除
7. TXT 目录规则/替换净化/字典规则测试情况+扩充内置规则+默认开关策略
8. TTS 多角色开启后仍单角色；内置 CloneTTS 不知道怎么用角色模板配置

## 逐项根因与修复方向

### F1 视频下载后缀（P0）
- 根因：`DownloadService.kt:660-671 resolveFileName()` 标题非空时直接返回不补扩展名（仅标题为空走 URL 推断才补 .mp4）；DIRECT 直链路径（:374-409 + ChunkDownloader.kt:365）全程无 Content-Type 纠正；HLS 路径反而有强制补后缀（:416-417）
- 软件内播放失败卡点：`DownloadManageActivity.kt:225 isVideoFile()` 按文件名后缀白名单判定（DownloadManageScreen.kt:80 DOWNLOAD_VIDEO_EXTS），无后缀→走系统 MIME 解析失败
- 修复：①resolveFileName 非空标题分支补 .mp4 ②DIRECT 下载完成按响应 Content-Type renameTo 纠正 ③openFile 对无扩展名旧文件兜底（默认按视频处理或文件头嗅探）④标题含 `.` 伪后缀防护（uniqueFile :673-686 会把"xx.4K"的点后当扩展名）
- 关联崩溃：logcat 09-10 09:51 MPEG4Writer 视频轨写线程 UBSan mul-overflow SIGABRT（Android16/MIUI libstagefright 平台库，进程死亡）——排查 HlsDownloader ts→mp4 重封装（MediaMuxer→MPEG4Writer）路径，应用侧规避（封装异常保护+阅读状态保存），平台缺陷无法根治须登记

### F2 管理列表底部全选条（P1）
- 实现：`AppManagementScaffold.kt:286-345 AppManagementSelectionBottomBar`（选中数>0 常驻；左"已选x/y"点击=全选 :316，右反选 :321，其余 13 项批量操作收进 SelectionMoreMenu :336-342）
- 接线：BookSourceActivity.kt:277-278 / RssSourceActivity.kt:185-186；引入提交 3c8aa5c（Compose 化批量迁移）+4bf20fc
- ⚠️ 双栈疑点：旧 View 体系 `SelectActionBar.kt:57-60` 仍声明于 activity_book_source.xml:30 / activity_rss_source.xml:30——可能双重显示（实施时真机复核，实施期确认哪一个是用户看到的"重叠"来源）
- 影响面：13 项批量操作（启用/停用/加组/置顶/校验/导出/删除）不能丢入口；选择计数显示依赖 :307
- 方案：去掉 Compose 常驻底部条；多选态操作收口到长按进入多选后触发的单一入口（顶栏 MoreVert 溢出或选择态菜单，设计定稿）；全选/反选并入该入口；旧 View SelectActionBar 不整类删（其他 4 页仍引用）

### F3 高亮规则（P0）
- 长对话匹配不到（确定级）：内置对话正则（HighlightRuleStore.kt:172）`“[^”\n]{1,120}”|"[^"\n]{1,120}"|「[^」\n]{1,120}」|『[^』\n]{1,120}』` 双重限制：①{1,120} 硬上限（超长对话整段不命中）②[^”\n] 排除换行（跨段落对话断开，HighlightTextBuilder.kt:33 段末插 \n）
- 匹配器用 find 非 matches（HighlightRuleMatcher.kt:94），用户正则写法语义没问题；超时保护 3000ms（:91）长章多命中可截断
- 编辑不生效候选根因（日志实锤高亮链本身无异常：规则12 命中0~45 耗时0-2ms，无非法正则）：
  ① isRegex=false 时按字面量整串匹配（:45→matchLiteral:64），填正则但开关没开=零命中且无提示
  ② 运行期非法正则静默跳过（:83-84）
  ③ 内置愈合 shouldRefreshBuiltin（HighlightRuleStore.kt:431-443）pattern 与 legacyBuiltinPatterns（:461）相同时用内置覆盖（用户改过则保留 :322）
- 生效链本身即时（save→ReadBook.upHighlightRules→highlightRulesVersion++→upContent 重绘，ReadBook.kt:395-416）
- 修复：①内置对话正则放宽（上限提到 {1,400} 级别+评估跨段支持，防 ReDoS 保留限长）②isRegex=false 且内容含正则元字符时保存/匹配时提示 ③匹配器诊断日志（AppLog.HighlightStyle 链路已有，补 isRegex/命中统计）④评估"长度上限"规则级配置

### F4 内置高亮规则扩充（P2）
- 现状：12 条硬编码（HighlightRuleStore.kt:167-306），4 开 8 关；SP 存储非 Room
- 关键机制缺口：高亮规则无版本旗标，愈合只能刷新已有 12 个 id，**新增第 13+ 条推不到老用户**——需新增 highlightRuleVersion 旗标（对齐 LocalConfig.isLastVersion 模式）触发 restoreDefaults(MERGE) 追加缺失 id
- 候选清单（调研产出 16 条，选优内置）：说话人标签对话、分段引文（段首前引号无后引号长对白）、破折号对白行、系统面板文本、卷标题行、心理关键词宽版/旁白省略宽版/诗词题头/金额宽版/时间宽版（旧代 legacyBuiltinPatterns 可复用 :460-473）、网址邮箱灰显、拟声词、Markdown 强调、全角竖排引号变体
- 默认开关策略：确定性/修复性默认开；泛化匹配（易误伤）默认关；对齐现现状 4 开 8 关逻辑

### F5 稳定性（P0，日志实锤）
- 日志实锤（37 分片 37119 行，09-09 19:46~09-11 06:51）：
  - DoH：UnknownHostException×156（全挂 DohDns tag）、负缓存 211、fallback system DNS 81（两种原因：all DoH servers failed / **DoH returned only loopback/reserved addresses 污染分支**）、73 主机解析失败、Top1 主机 175 次
  - 图片加载失败根因=DNS 解析失败（Glide OkHttpStreamFetcher 帧全挂 DohDns UHE），非解密失败；解密缓存命中 1211 次正常
  - TTS 音频下载失败→静默无声音频替代 ×11
  - MemoryPressure 节流日志 5521 条（15% 洪水，每 3 秒主线程 I+E 双条）
- 代码机制（探索实证）：
  - DohDns（help/http/DohDns.kt）：成功缓存 5min/负缓存 10s/熔断 3 连败停 5min/永远兜底 Dns.SYSTEM（:83,96,240-258）
  - DoH 返回回环/保留地址：当前有 fallback 分支但坏 IP 命中前已消耗请求（连接层超时打满 callTimeout 60s）
  - failUrl 黑名单（OkHttpStreamFetcher.kt:61,174-179,244-249）：LruCache 200 无 TTL，一次瞬时失败→同 URL 全部秒失败直到 LRU 淘汰
  - Cronet：降级-恢复震荡循环（CronetInterceptor.kt:170-208,379-405），3min~15min 探测切回；两栈 DNS/Header/协议差异（Cronet 不走 DoH 用自己的解析器 CronetHelper.kt:166-186）
  - 图片绘制缓存设置（bitmapCacheSize→ImageProvider LruCache）只作用正文位图缓存，与封面无关（探索排除）
- 修复（正向优化）：
  ① DoH 回环/保留地址结果立即判失败并走系统 DNS（缩短该分支的请求浪费+负缓存）
  ② per-host 自适应：单主机 DoH 连续失败 N 次→该主机临时直走系统 DNS（TTL 后恢复 DoH），降低 73 主机失败面的重复消耗
  ③ failUrl 黑名单加 TTL（5min 过期可重试）+容量不变
  ④ MemoryPressure 日志降频：状态变化才打+降 D 级+移出高频路径（5521→可观测下限）
  ⑤ TTS 音频下载失败静默替代：补一条用户可见提示（对齐正向优化：不静默吞错误）
  - 不动 Cronet 降级恢复机制本身（已有自愈）；"图片绘制缓存"设置项加说明文案澄清作用域
- 待复测登记（不实锤不修）：视频嗅探 window.__videoUrls__ 解析失败×2+sniff UNKNOWN×56（疑站点改版）、BufferSpeed SLOW×245

### F6 缓存清理整合（P2）
- 实锤：其它设置"清除缓存"（OtherConfigFragment.kt:447-452→ConfigViewModel.kt:31-38）删 book_cache+整个 internal cacheDir，与缓存管理（CacheManageViewModel.kt:29-57 三分项）100% 重复且**无播放中保护**（缓存管理有 :64-77）；缓存管理独有 exoplayer 外部缓存
- WebView 数据删除只在其它设置有（ConfigViewModel.kt:40-47，删后延迟 3s 强制重启），缓存管理无
- 方案：删其它设置两个入口；WebView 数据迁移为缓存管理第 4 分项（保留"删除后需重启"提示语义）；ConfigViewModel 废弃函数清理

### F7 内置规则扩充：TXT 目录/替换净化/字典（P2）
- 现状：TXT 目录 26 条（assets/defaultData/txtTocRule.json，12 开）；**替换净化 0 条（完全靠用户自建，最大缺口）**；字典 5 条全开（不建议扩）
- 机制：Room 类走 DefaultData.upVersion（App.kt:178→DefaultData.kt:27-50）+ LocalConfig 版本旗标（LocalConfig.kt:62-76 isLastVersion）；txtToc needUpTxtTocRule=3 升 4 推新增；deleteDefault 只删负 id，新增只追加新负 id 不动老正则
- 替换净化内置需新建导入链（核实 ReplaceRule 实体与 DefaultData 导入模式；内置负 id 约定）
- 候选：TXT 目录 6 条（双编号轻小说/英文序词扩展/韩式番外/井号标题等）；替换净化 12 条（章末推广块/推广文本/HTML 残留/script 块/广告角标/引流整行删/章节格式统一/错字修正等）
- 默认开关策略：错字修正/HTML 清理类默认开（不删正文）；泛化关键词整行删默认关（可能误杀）；TXT 目录增量默认开（vreader 案例：默认开更多收益大于误伤风险）

### F8 TTS 多角色可用性（P0，UX 断链最严重）
- 日志实锤双分支：早期会话 `ruleSet=false rules=0 → legacy`×12（模板未激活）；激活后 33 段 32 段 narration 仅 1 dialogue（09-10 07:40/16:42 两会话）——对话切分几乎全不命中
- 探索七断点：
  ① 入口语义混淆：ReadAloudConfigDialog.kt:499-506 "多角色"开关=AI 分镜功能，与选角模板路由完全解耦（TTSReadAloudService.kt:256-271 路径判定只看模板 ruleSet），开了它对声音零作用
  ② 激活≠生效：内置 4 模板声源全是 `current` 哨兵+toneID 全空（castingTemplates.json:8,18,28）→TtsVoiceSource.kt:161-162 voiceId 空不 setVoice→每段引擎默认音=必然单角色
  ③ 配置不可达：编辑器系统引擎组只列引擎不列音色（SpeechVoiceCatalogRepository.kt:119-148 每引擎 1 option、toneID 恒空），选择器无手填 voiceId 入口——CloneTTS 克隆音色无处绑定
  ④ 脚本声源死路：clonetts.js 属 HTTP 通道，逐段路径被 TTSReadAloudService.kt:400-406 门禁强制降级默认音
  ⑤ 静默降级无感：全链降级只写 AppLog（ReadAloudConfigDialog.kt:99 明示静默设计）
  ⑥ 配置 ≥5 步无引导
  ⑦ AI 角色绑定链 TTSReadAloudService.kt:398 resolveSourceForTag 未传 characterId
- 修复设计方向：
  ① 入口正名：开关改名"AI 分镜选角"+描述区分；模板激活状态在朗读设置直接可见
  ② **系统引擎音色枚举（本仓独有能力）**：SpeechVoiceCatalogRepository.systemGroups 接 TextToSpeech.getVoices() 枚举真实音色（CloneTTS 注册的系统 voices 即可被选），编辑器可选具体 voice→写 toneID→TtsVoiceRef 命中 setVoice——此为打通"多角色真的多声音"的核心
  ③ 对话切分命中率：内置模板对白规则扩充（引号+说话人标签模式）；面板显示切分统计（narration/dialogue 计数）辅助诊断
  ④ 静默降级→面板提示条（模板激活但 voiceId 未命中/全部 narration 时明示"去绑定音色"引导）
  ⑤ 模板管理页首次引导文案（CloneTTS 使用路径说明：系统引擎音色直选）
  ⑥ 脚本/HTTP 声源逐段路径限制明示（门禁 toast+文案），深度支持登记后续
- 单测：音色枚举映射/目录产出/toneID 写入链纯函数部分

## 批次划分（一次 openspec、两波实施、一个测试包验收）

- 批次A（P0）：F1/F3/F5(v1.1)/F8/F9
- 批次B（体验+扩充）：F2/F4/F6/F7/F10
- 每批次：编译+单测；最终打一个测试包交用户真机验收（用户自测，提供 TtsTrace/网络日志复核 DoH fallback 对比与多角色 dialogue 命中率）

## 验证口径

- 单测：高亮正则放宽后确定性/性能（ReDoS 预算）、DoH 回环过滤纯函数、failUrl TTL、resolveFileName 后缀逻辑、音色枚举映射
- L1 装机零 FATAL；关键修复点 TtsTrace/AppLog 诊断日志保留（AGENTS.md 诊断日志保留铁律）
- 真机（用户）：①下载视频带后缀可播 ②长对话高亮命中 ③编辑正则即时生效 ④多角色绑定 CloneTTS 音色后双声 ⑤DoH 失败统计下降 ⑥底部条消失批量操作可达 ⑦新内置规则出现

## 明确不做（本期边界）

- Cronet 降级恢复机制重写（已有自愈，仅观察）
- 脚本/HTTP 声源进逐段 multiRole 合成链（架构级，登记后续）
- DoH 上游服务器可配置化（保持内置两家）
- 字典规则扩充（现 5 条即上游通用集）
- MPEG4Writer 平台库缺陷根治（应用侧规避+登记）
- AI 角色绑定链 characterId 透传（依赖段落坐标系验证，登记后续）

## 补充调研结论（v1.1，2026-09-11）

> 应用户检查点 1 四点意见（规则调研广度/日志降噪独立子任务/网络稳定性系统性方案/TTS 测试方案）补充调研后的修订结论，已同步 spec/design/tasks（v1.2）。批次划分同步：批次A=F1/F3/F5(v1.1)/F8/F9；批次B=F2/F4/F6/F7/F10。

### 一、同源 fork 规则调研结论（意见①）

- 调研范围：8 家活跃同源 fork（MD3/MD3-DIY/阅读T/NG/阅读C/Archive/Jingshiro/Legado_Max），对照四类规则矩阵
- TXT 目录：8 家全部 25-26 条同一基线——本仓 26 条不落后；字典：本仓 5 条已是调研最高档
- 替换净化：8 家内置全部 0 条（全源空白）；高亮：仅 Legado_Max 有内置且与本仓逐字符一致
- 跨段/超长对话高亮：全源无先例
- **结论：无现成更多规则可抄，扩充走"社区候选方案+自研"路径；数量目标上调如下**

### 二、规则数量目标上调（意见①落地）

- 高亮 12→24：新增 12 条（16 条候选选优+跨段变体，跨段变体默认关）
- 替换净化 0→12：全源空白，自建（章末推广块/HTML 残留/script 块/错字修正等候选选优）
- TXT 目录 26→30：MD3 独有 2 条直接采纳（中文顶格标题默认关/数字可选分隔符默认开）+社区候选选优 2 条
- 字典 5 条维持（调研确认本仓已最高档）

### 三、F5 升级为 HostAccessStrategy 系统性方案（意见③）

- 原"分散修补（DoH 回环过滤/per-host 自适应/failUrl TTL）"升级为单源化健康表方案（要点见 design 3.5 v1.1 + AD-10）
- 核心动机：坏 IP 无记忆=日志实锤第一痛点（60s 超时循环浪费）；三栈三通道各自记账割裂；全局降级误伤健康 host（震荡根因）
- Out-of-Scope 口径调整：Cronet 机制"不重写，收编进 HostAccessStrategy"

### 四、新增 F9 日志降噪与规范优化（意见②：独立子任务）

- AppLog 新增 putThrottled/putSampled 双机制；周期性日志禁令；噪音源逐项治理（要点见 design 3.8 + AD-11）
- MemoryPressure 5521 条/会话（15% 洪水）为治理首要目标；TtsTrace 等诊断日志白名单保留只降频

### 五、新增 F10 TTS 功能级联调测试方案（意见④）

- ai_tests 新增 TTS L2 联调脚本：引擎枚举→音色枚举→模板绑定→朗读推进（TtsTrace 断言）
- 模拟器环境：CloneTTS APK（官方 GitHub Releases 渠道）；MultiTTS 无官方渠道，仅真机用户提供
- 覆盖度矩阵落 design 3.9（模拟器约 80% 功能面）；L3 真机仅用户（听感/后台保活/功耗）
