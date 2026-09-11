# tasks.md（批次F：八项真机问题全量修复）

> 事实源：BRIEF.md。验证级别：L1=编译过 / L2=单测或模拟器功能验证 / L3=用户真机场景验证。
> 铁律：bak 备份先行；同文件 Edit 串行；诊断日志（TtsTrace/AppLog）保留；updateLog 基于实际 git diff 编译前更新；禁止 temp/ 建临时脚本。

## 1 准备工作

- [x] 1.1 bak 备份批次A 涉及文件到 `bak\bugfix-0911-a\`（DownloadService/ChunkDownloader/DownloadManageActivity+Screen/DohDns/OkHttpStreamFetcher/CronetInterceptor/HttpHelper/AppLog/MemoryPressure（v1.1 修订：CronetInterceptor 由只读改为迁移委托+上报；新增 F9 涉及文件）/HighlightRuleStore/HighlightRuleMatcher/HighlightRuleEditDialog/TTSReadAloudService/SpeechVoiceCatalogRepository/SpeechVoiceRoutePicker/TtsCastingEditorScreen 相关/ReadAloudConfigDialog/HlsDownloader/castingTemplates.json/strings.xml）(L1)
- [x] 1.2 关键文件当前状态复核：Read 确认 BRIEF 引用行号未漂移（DownloadService.kt:660-671/416-417、HighlightRuleStore.kt:172/431-443、DohDns.kt:83/96/240-258、OkHttpStreamFetcher.kt:61/174-179/244-249、TTSReadAloudService.kt:256-271/398/400-406、SpeechVoiceCatalogRepository.kt:119-148、TtsVoiceSource.kt:161-171），并补四处：ReadBook.kt:395-416 即时生效链复核（用户疑问③闭环证据）、HighlightRuleEditDialog.kt:365-390、TtsCastingStore.kt:243-271（importBuiltinTemplates 全字段比对刷新语义）、ChunkDownloader.kt:111-125 probe() 与 DownloadService executeDirect:394-408 (L1)——11 处行号复核通过，无漂移
- [x] 1.3 F2 双栈复核：真机/代码确认书源/订阅源管理页旧 View SelectActionBar（activity_book_source.xml:30 / activity_rss_source.xml:30）与 Compose 底部条是否双重显示，确定用户所见"重叠"来源，结论写入 design 3.2 (L2)——结论：无双重显示，重叠来源=Compose 底部条常驻
- [x] 1.4 替换净化内置导入前置核实：Read ReplaceRule 实体/Dao/ReplaceRuleStore 现有结构与 DefaultData 导入模式（deleteDefault 负 id 约定是否适用），结论落 design 3.4（Room v110 冻结：不新增表不新增列）(L1)——结论：负 id 可行+需新增 DAO insertIfAbsent
- [x] 1.5 MPEG4Writer 崩溃路径排查：确认 HlsDownloader ts→mp4 重封装是否走 MediaMuxer/MPEG4Writer（Grep MediaMuxer/MPEG4Writer 调用点），确定规避方案落 design 3.1 (L1)——结论：PTS 钳制定稿

## 2 批次A 实施（P0 bug + 稳定性）

### F1 视频下载后缀
- [x] 2.1 resolveFileName 非空标题分支补默认 `.mp4`（标题含 `.` 伪后缀防护：点后段不在视频扩展白名单时仍补）——DownloadService.kt:660-671 (L1)——resolveFileName 提取为纯函数 resolveVideoFileName+单测
- [x] 2.2 DIRECT 下载后缀纠正：mime 唯一可靠取点=ChunkDownloader.probe()（:111-125，ChunkResult 扩展 mime 字段）；纠正动作放 DownloadService.executeDirect（:394-408）内 rename+uniqueSibling 防覆盖；rename 后同步 DB downloadRecords localPath/fileName 与通知栏/IntentType 口径（三处一致性，防"文件丢失"误报）(L2)
- [x] 2.3 openFile 无扩展名旧文件兼容：无扩展名时按视频处理（或文件头嗅探），白名单判定与 IntentType 双点兜底——DownloadManageActivity.kt:225/243-244 (L2)
- [x] 2.4 MPEG4Writer 崩溃应用侧规避（按 1.5 结论：封装异常保护/失败产物清理+提示；阅读状态保存已有则确认覆盖该场景）(L2)

### F3 高亮规则
- [x] 2.5 内置对话正则放宽：上限 {1,120}→{1,400}（防 ReDoS 保留限长；保留 \n 排除首版不跨段，跨段登记后续）+演进覆盖机制（现役 120 对话正则登记 legacyBuiltinPatterns+normalizeRules 增加"用户 pattern 等于 legacy 登记值→允许 builtin 新版覆盖"分支）+存量升级单测（存量 120 用户升级后应得 400）——HighlightRuleStore.kt:172 (L2)
- [x] 2.6 isRegex 陷阱可观测：isRegex=false 且内容含正则元字符（\ { [ | 等）时保存时提示"当前按字面量匹配"，匹配时对字面量命中也提示一次（与保存时提示双口径）——HighlightRuleEditDialog.kt:365-390 (L2)
- [x] 2.7 匹配器诊断日志补强：HighlightRuleMatcher 按规则记录 isRegex/命中数/耗时（对齐 TAG_HIGHLIGHT_STYLE 链路），非法正则静默跳过处补 WARN——HighlightRuleMatcher.kt:82-84 (L1)
- [x] 2.8 愈合覆盖防护复核：shouldRefreshBuiltin 确认用户修改 pattern 后不被覆盖（:322 已保留），补一条"愈合跳过原因"日志供真机诊断 (L1)

### F5 网络稳定性（v1.1 HostAccessStrategy，检查点 1 意见③）
- [x] 2.9a 新建 HostAccessStrategy.kt 结构+并发模型：host 键 ConcurrentHashMap 健康表上限 400 兜底（LRU 淘汰+退避期条目优先保留；stackVerdict{CRONET_OK/BAD,OKHTTP_OK/BAD，阶段1 仅记录不上报选路}+dnsVerdict{DOH_OK/QUERY_FAIL/BAD_IP/SYS_OK}+failCount（新增 per-host 字段，全新行为，BRIEF F5②落地）+指数退避 30s→5min→15min；可变字段 compute() 原子读改写防退避丢失更新）+pickChannel/report/probe 接口定义+plainImportClient/videoStreamClient 请求不上报健康表（防导入链污染信誉）(L2)——阶段2 确认项无需实施
  - 实施序：2.9a 单测过→2.10 DohDns 接线编译→2.11/2.12 上报挂接编译，每步复验
- [x] 2.9b fail-open 铁律：pickChannel/report/probe 全路径 try-catch，任何异常回落默认通道（=Cronet 开关原语义+系统 DNS），禁止健康表故障熔断全 App 网络 (L2)——阶段2 确认项无需实施
- [x] 2.9c 坏 IP 短 TTL(2min) 黑名单：键=host+IP 对（防共享 CDN 段投毒）+上限 50 兜底（LRU 淘汰+未过期条目优先保留，与健康表统一策略）+主动清扫过期 (L2)——阶段2 确认项无需实施
- [x] 2.9d 原 failUrl TTL 任务并入：OkHttpStreamFetcher.kt:61 条目带时间戳，连续失败 TTL 5min→10min→20min 封顶，容量 200 不变，过期可重试 (L2)——阶段2 确认项无需实施
- [x] 2.10 【阶段1 DNS 层】DohDns.lookup 接入选路：开头查 dnsVerdict 过退避期直接走系统 DNS+坏 IP 黑名单过滤+失败统计上报（DohDns negativeCache 并入健康表；回环/保留地址判败分支 DohDns.kt:214-224 复核+统计口径并入；不用 isSiteLocalAddress 大范围过滤）(L2)——阶段2 确认项无需实施
- [x] 2.11 【阶段1 DNS 层口径】CronetInterceptor 仅错误分类处调 report() 失败上报（CONN_REFUSED/timeout 上报 DOH_BAD_IP 嫌疑+该 IP 进黑名单）；certErrorCache/degradedForSession/lastFailedHostHint 检查改查健康表的 per-host 迁移属阶段2（见 2.15 登记后续，本批不动拦截流程）(L2)——阶段2 确认项无需实施
- [x] 2.12 OkHttp 失败上报挂接：现有异常拦截器处 report() 补全景记账+HttpHelper 接线 (L2)——阶段2 确认项无需实施
- [x] 2.13 【阶段1 DNS 层】单调度器 HEAD 轻探测收编 DohDns preheat：探测仅定义=清 dnsVerdict（DNS 层验证），成功清记录失败退避翻倍；验收口径=探测请求旁路健康表（专用 client 直连 DoH 查询）；单测点=探测成功清 dnsVerdict/失败退避翻倍/探测自身不查健康表；Cronet half-open in-band 探测（CronetInterceptor.kt:37-48）保留不动（防探测栈错位，HEAD 走 OkHttp 无法证明 Cronet 可用；两套归一重构属阶段2）(L2)——阶段2 确认项无需实施
- [x] 2.14 TTS 音频下载失败静默替代补提示（原 2.12 顺延）：无声音频替代时面板/通知用户可见提示一条——对齐 BRIEF F5⑤ (L2)——阶段2 确认项无需实施
- [x] 2.15 【阶段2 登记后续】CronetInterceptor per-host 迁移（certErrorCache/degradedForSession/lastFailedHostHint+DohDns negativeCache 并入健康表，healthy host 防全局降级误伤；牵连入口全局门移除+RECOVERY_PROBE_INTERVAL 4 处+in-band 探测重构，≈重写规模）——独立立项，确认项非本批任务 (L1)——阶段2 确认项无需实施
- [x] 2.16 图片绘制缓存设置项作用域澄清文案（strings.xml）：说明只作用于正文位图缓存、不影响封面加载 (L1)

### F9 日志治理（检查点 1 意见②，独立子任务）
- [x] 2.17 AppLog putThrottled/putSampled 双机制：putThrottled 同 key 60s 去重+末条合并"(累计N次)"（节流状态 Map 加 LRU 上限清理，防 key 无界增长）；putSampled 每 n 条出 1 条汇总；诊断 tag 白名单豁免按"调用类名 tag"对齐 putEntry stackTrace[3] 机制——AppLog.kt (L2)
- [x] 2.18 MemoryPressure 级别跃迁改造：治理源头=:57/:62 skip 高频打点降频（:112 是出口勿只改出口）+:112 出口改压力级别跃迁打点+移出 3 秒轮询高频路径（5521 条→<10/会话）；统一 App.kt:176 与 OtherConfigFragment.kt:203 双 enableLogger 开关为单源（防互相覆盖）(L2)——enableLogger 单源=AppLog.syncEventBusLogger
- [x] 2.19 噪音源逐项治理：LiveEventBus（降 DEBUG 或删）/CryptoScope（采样 1/50 或仅 miss 打点）/预连接（按批次汇总）/MIUI SettingTrigger（首条+计数合并）/PageDebug（降 putDebug+采样）——打点文件以实施期 Grep 实际为准 (L2)——PageDebug 临时 tag 已删/CryptoScope 1/50 采样/预连接采样/SettingTrigger=MIUI 框架日志登记 issues-found
- [x] 2.20 logging_rules.md 三条款增补：周期日志禁令/级别语义表（E=用户可感知失败/W=降级兜底/I=仅状态迁移/D=过程细节）/putThrottled putSampled 使用指南 (L1)
- [x] 2.21 诊断 tag 白名单登记与豁免机制：TtsTrace/PageDebug 等正式诊断日志登记白名单，只降频不删除（AGENTS.md 诊断日志保留铁律）(L1)
- [ ] 2.22 治理前后日志量对比验证：同场景导出统计噪音占比（目标：MemoryPressure <10/会话、噪音占比显著下降、诊断链完整）(L2)

### F8 TTS 多角色可用性（前置链顺序执行）
- [ ] 2.23 系统引擎音色枚举：SpeechVoiceCatalogRepository.systemGroups 接 TextToSpeech.getVoices() 枚举真实音色（每引擎多 option、toneID=voice name）；异步枚举（suspend+协程+结果缓存，禁主线程同步等 init，调用方 produceState 消费）；三态处理（引擎未就绪→提示重试 / 就绪但空集→明示"该引擎无可枚举音色" / 正常→枚举，禁止静默回落）——SpeechVoiceCatalogRepository.kt:119-148（含枚举路径补 TtsTrace 埋点——7.2 断言前置）(L2)
- [ ] 2.24 编辑器/选择器消费音色目录：SpeechVoiceRoutePicker 支持选择具体音色；TtsCastingEditorScreen 系统 engineType 可产出带 toneID 的声源（前置：2.23；异步枚举（禁主线程同步等 init），调用方 produceState）；AD-09 克隆语义实施（builtin 模板编辑保存强制克隆为自定义模板并激活克隆）(L2)
- [ ] 2.25 内置模板对白规则扩充：castingTemplates.json 对白规则补说话人标签模式（提高 dialogue 命中率，针对日志 32/33 narration）；模板升级走既有 importBuiltinTemplates 全字段比对刷新 (L2)
- [ ] 2.26 静默降级→面板可见提示：模板激活但 voiceId 未命中/切分全 narration 时，播放面板显示提示条+引导去绑定音色（TtsVoiceSource.kt:161-171 / TTSReadAloudService.kt:400-406 降级点上抛状态）(L2)
- [ ] 2.27 切分统计显示：面板显示 narration/dialogue 切分计数；区分"本章无对话"与"规则未命中"（防误标）(L2)
- [ ] 2.28 入口正名：ReadAloudConfigDialog "多角色"开关改名"AI 分镜选角"+描述区分两套体系；模板激活状态在朗读设置页直接可见（前置：1.2 复核 ReadAloudConfigDialog.kt:499-506/480-491）(L2)
- [ ] 2.29 模板管理页首次引导文案：CloneTTS 使用路径说明（系统引擎音色直选）；脚本/HTTP 声源逐段路径限制明示（门禁处 toast+编辑器文案）(L2)

## 3 批次A 验证

- [ ] 3.1 编译通过 compileAppDebugKotlin (L1)
- [ ] 3.2 单测：高亮正则放宽后确定性+匹配耗时预算（长文本×多规则）+高亮总耗时上限断言（长章×24 规则：per-rule deadline 是 24 条独立预算，须断言总量防失控）；HostAccessStrategy 健康表记账/指数退避/坏 IP TTL 过期判定纯函数；failUrl TTL 过期判定；resolveFileName 后缀逻辑（含伪后缀防护）；音色枚举映射纯函数；putThrottled/putSampled 节流采样机制；MIME_TO_EXT 映射+rename 后 DB localPath/fileName/通知栏/IntentType 三处同步一致性；探测调度语义（成功清记录/失败退避翻倍/旁路验证） (L2)
- [ ] 3.3 Grep 检查：无 android.util.Log 残留；本次新增诊断日志全部走 AppLog（保留铁律）(L1)
- [ ] 3.4 模拟器 L1 装机启动零 FATAL (L1)

## 4 批次B 实施（体验 + 扩充）

### F2 选择操作区收口
- [x] 4.1 底栏移除范围收窄（实施修订 2026-09-11）：AppManagementSelectionBottomBar 为管理族共用组件（字典/TXT目录/替换净化/书源/订阅源等 6+ 页在用，全族移除=变更放大且用户仅反馈书源/订阅源两页）——组件与其余页面行为不变；仅 ①Scaffold 增加渲染条件（bottomActions 空且无全选回调时不渲染底栏）②书源/订阅源两页调用点停止传 bottomActions/onSelectAll/onInvertSelection (L2)——范围收窄已实施
- [x] 4.2 两页多选态操作收口顶栏：menuActions 改动态 lambda（selectedUrls 非空时返回 全选+反选+批量操作列表映射，未选中返回原 pageMenuActions）；选择计数并入顶栏标题（选中态 title="已选 N 项"）——BookSourceActivity.kt:218/221-278、RssSourceActivity.kt:137/141-186 (L2)
- [x] 4.3 双栈清理：书源/订阅源两页布局 XML 死声明摘除（activity_book_source.xml:30-33 / activity_rss_source.xml:30-33，运行时已 removeView 无双重显示），SelectActionBar 类保留（其他 4 页引用）(L2)

### F4 高亮规则扩充
- [x] 4.4 高亮内置版本旗标机制：LocalConfig 增 highlightRuleVersion 旗标 + HighlightRuleStore load 时 MERGE 追加缺失内置 id（不覆盖用户已有修改；旗标触发时传本次新增 id 的 delta 清单——SP 链无删除墓碑，禁全量 defaults）；新增规则 id 同步扩 builtinIds/legacyBuiltinPatterns（否则无愈合保护）(L2)——LocalConfig.needUpHighlightRules(1)+load MERGE delta
- [x] 4.5 新增内置高亮规则 12 条（总量 12→24；16 条候选选优+跨段变体，跨段变体默认关；默认开关策略：确定性开/泛化关）+真机长章多命中性能验证（耗时受控）(L2)——12 条新规则+builtinIds 扩容

### F6 缓存清理整合
- [x] 4.6 cacheDir 杂项盘点：盘点 cacheDir 一级子目录归属，HLS 分片等未覆盖残留并入视频分项或补兜底说明（结论落 design 3.6）(L1)——cacheDir 一级子目录仅 httpTTS（已被音频分项覆盖），HLS 分片在下载目录 m3u8/ 非缓存——无清理缺口，关闭
- [x] 4.7 其它设置删"清除缓存""清除 WebView 数据"两入口+ConfigViewModel 废弃函数清理（clearCache 全量删 cacheDir 无保护方案一并废弃）——OtherConfigFragment.kt:447-458 (L2)——两入口+ConfigViewModel.clearCache/clearWebViewData 已删
- [x] 4.8 缓存管理新增 WebView 数据第 4 分项（保留删除后需重启提示语义且重启提示前置；播放中保护适用；分项排序置底）——CacheManageViewModel.kt:29-57 结构容纳 (L2)——WebView 第 4 分项+needRestart 重启提示

### F7 内置规则扩充（TXT 目录/替换净化/字典）
- [x] 4.9 txtTocRule.json 追加 4 条（总量 26→30；MD3 独有 2 条直接采纳：中文顶格标题默认关/数字可选分隔符默认开+社区候选选优 2 条——双编号轻小说/英文序词/韩式番外/井号标题中选优；新负 id，不动老正则）+ needUpTxtTocRule 3→4 推送老用户（只追加缺失 id 不重置用户开关状态：Insert IGNORE 模式，禁 deleteDefault+全量重插）(L2)——txtTocRule.json +4 条(-26~-29)+needUpTxtTocRule 3→4+insertIfAbsent 追加
- [x] 4.10 替换净化内置导入链（按 1.4 核实结论）：assets 新 json/或 DefaultData 代码内置 12 条（0→12，全源空白自建：章末推广块/HTML 残留/script 块/错字修正等），默认开关策略（修复性开/泛化关），老用户旗标推送（只追加缺失 id 不重置用户开关状态：Insert IGNORE 模式，禁 deleteDefault+全量重插）(L2)——replaceRules.json 12 条+ReplaceRuleDao.insertIfAbsent+DefaultData+needUpReplaceRules
- [x] 4.11 字典规则不动（BRIEF 结论：现 5 条即上游通用集）——确认项非任务

## 5 批次B 验证

- [ ] 5.1 编译通过 (L1)
- [ ] 5.2 单测：高亮版本旗标 MERGE 逻辑（老用户已改 pattern 不被覆盖/缺失 id 追加）；替换净化导入幂等；TXT 目录新规则匹配样例 (L2)
- [ ] 5.3 模拟器 L2：管理页多选态批量操作可达+全选反选功能等价；缓存管理 WebView 分项可见可清；其它设置入口已消失；补验证点：多选态下无双套全选/反选控件同时显示、WebView 分项清理受播放中保护、新增高亮/替换净化规则默认开关状态符合策略（确定性开/泛化关）(L2)
- [ ] 5.4 Grep 检查同 3.3 (L1)

## 6 收尾

- [ ] 6.1 updateLog 第二十五批：基于 git diff 逐文件对照分析真实变更，面向用户语言（编译前更新）(L1)
- [ ] 6.2 文档同步：docs/INDEX.md 登记、task-navigation 如涉及、issues-found.md 登记待复测项（视频嗅探解析失败/BufferSpeed SLOW/MPEG4Writer 平台缺陷）+登记后续三项（高亮跨段对话支持、高亮"长度上限"规则级配置评估结论输出、Cronet 降级-恢复机制观察结论）(L1)
- [ ] 6.3 构建前清场校验（Get-Process 无构建进程）→ build-legado.bat 打测试包 → 装机 L1 (L1)
- [ ] 6.4 提交推送（Conventional Commits，分两个 commit：批次A 一个、批次B 一个；HostAccessStrategy 相关改动再独立一个 commit，保证回滚粒度）(L1)
- [ ] 6.5 用户真机验收清单（L3，用户自测）：①下载视频带后缀软件内可播 ②长对话高亮命中 ③编辑正则即时生效+字面量提示 ④朗读设置绑定 CloneTTS 具体音色后多角色真实双声 ⑤面板提示条在未绑音色时可见 ⑥底部条消失批量操作可用 ⑦新内置规则出现（高亮/替换净化/TXT 目录）⑧其它设置入口删除后缓存管理 WebView 清理可用 ⑨导出日志复核 HostAccessStrategy 成功率统计提升与 MemoryPressure 洪水消失 ⑩朗读设置入口正名（AI 分镜选角）可见 ⑪脚本/HTTP 声源限制明示文案可见 ⑫模板管理页首次引导文案可见 ⑬导出日志噪音占比下降对比（同场景治理前后）⑭Cronet/DoH 场景访问成功率体感（弱网/切换网络）

## 7 F10 TTS 联调测试（批次B，脚本先行；检查点 1 意见④）

- [ ] 7.1 环境准备：下载 CloneTTS APK（官方 GitHub Releases 渠道）→模拟器 adb install→`settings put secure tts_default_synth` 设默认引擎+`settings put secure tts_default_lang`/`tts_default_country`/`tts_default_variant` 三键→CloneTTS 首启手动初始化（模型下载/授权）一次→`dumpsys texttospeech` 确认注册成功 (L2)
- [x] 7.2 新建 `ai_tests/scripts/l2_verify_tts_engine.py`：L2-a 引擎枚举/音色枚举/模板绑定断言（dumpsys texttospeech+UI 节点+TtsTrace 断言；前置=2.23 枚举路径 TtsTrace 埋点完成后断言方可执行）(L2)——脚本已建 l2_verify_tts_engine.py
- [x] 7.3 朗读推进 L2-b 脚本：本地书免网络场景+TtsTrace 全链断言（模板激活→切分→合成推进）(L2)——脚本已建 l2_verify_tts_read.py
- [ ] 7.4 覆盖度矩阵落 design 3.9 与 ai_tests/docs 登记（单测/模拟器 L2/真机 L3 各覆盖项，模拟器约 80% 功能面）(L1)
- [ ] 7.5 CloneTTS benchmark 失败降级路径验证：模拟器 x86 RTF>1 时降级系统 TTS 接口模式完成功能链验证并记录结论 (L2)
