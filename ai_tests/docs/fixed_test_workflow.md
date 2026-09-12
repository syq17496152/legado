# 固定测试流程 SOP（禁止从头创建临时脚本）

> **用户反馈（2026-07-11）**："你的测试流程为什么老是来来回回的变动呢？难道就没有一些经验或者是固定流程的脚本可以沉淀到ai_test目录下么？！！！你需要深度分析反省呢！"
>
> 本文档定义固定的测试流程，AI 每次测试**必须遵循此流程**，禁止在 temp/ 目录创建临时脚本。

## 标准测试流水线

```
编译 → 安装 → L1验证 → 导入订阅源 → L2验证 → 日志分析
```

每步对应固定脚本（位于 `ai_tests/scripts/`）：

> **现状注记（2026-09-01，总线 2.12 目录口径核定）**：`.gitignore` 对 `ai_tests/scripts/*.py` 默认忽略、仅白名单固化脚本入库；当前目录**仅存 3 个白名单固化脚本**（步骤 3c `l2_verify_precise_manage.py` / 步骤 15 `verify_no_crash.py` / 步骤 16j `l2_verify_image_enhance_governance.py`）。下表其余条目为历史批次登记，对应脚本已随治理批次移除（备份 `bak/ai-test-refinement-20260830/`），留作场景口径参考；新 L2 脚本落位口径（族命名+白名单入库+双登记）见 [README 脚本族索引](../README.md)。**B2 增补（2026-09-01，compose-migration-status-audit 4.2）**：+7 白名单固化脚本（本表 16l~16r，`l2_verify_compose_*` 系，复用层 `ai_tests/lib/compose_assert.py`；真机执行归冻结验收 4.4-4.7）。

| 步骤 | 脚本 | 说明 | 用法 |
|------|------|------|------|
| 1. 编译+安装+L1 | `quick_build_install.py` | 编译APK+启动MEmu+安装+L1验证 | `python ai_tests/scripts/quick_build_install.py` |
| 2. 导入订阅源 | `import_rss_source.py` | 从JSON导入订阅源到legado.db（含WAL模式处理） | `python ai_tests/scripts/import_rss_source.py <json_path>` |
| 2b. 导入书源 | `import_book_source.py` | 从JSON导入书源到legado.db book_sources表（WAL安全三件套同通道+动态列适配；B2 冻结验收增补 2026-09-01） | `python ai_tests/scripts/import_book_source.py <json_path>`（`--count` 仅查计数） |
| 2c. B2书架播种 | `seed_b2_bookshelf.py` | B2冻结验收数据播种：合成书站(8本×4章)+合成书源JSON+UI循环入架+就绪断言(书架≥6)；前置=外部`python -m http.server 18092 -d ai_tests/testdata/b2_shelf_srv`+adb reverse（B2 增补 2026-09-01） | `python ai_tests/scripts/seed_b2_bookshelf.py` |
| 3. L2验证视频播放器 | `l2_verify_video_player.py` | 视频播放器L2功能验证（导航+错误模式分析） | `python ai_tests/scripts/l2_verify_video_player.py` |
| 3b. L2验证订阅源搜索 | `l2_verify_rss_search.py` | 订阅源统一搜索L2功能验证（rss-unified-search新增） | `python ai_tests/scripts/l2_verify_rss_search.py [--keyword 关键词] [--scenario all]` |
| 3c. L2验证精准管理 | `l2_verify_precise_manage.py` | 精准管理L2验证（precise-manage新增：聚合入口/网址记录/存储管理/下载管理/文件管理/crash_check） | `python ai_tests/scripts/l2_verify_precise_manage.py [--scenario all]` |
| 4. SwipeTest日志分析 | `swipe_test_log.py` | SwipeTest日志抓取分析（仅临时日志验证时用） | `python ai_tests/scripts/swipe_test_log.py [clear\|capture\|analyze]` |
| 5. 导航辅助 | `nav_helper.py` | 脱敏导航到视频播放器（只输出编号不输出名称） | `python ai_tests/scripts/nav_helper.py [源编号]` |
| 6. 订阅形态切换验证 | `verify_rss_mode_switch.py` | 6.8 新版/经典订阅切换真机验证（打开配置页→切换→查prefs→回订阅页验形态） | `python ai_tests/scripts/verify_rss_mode_switch.py --full`（`--discovery` 验发现-订阅配置页） |
| 7. 多媒体书检查 | `check_video_books.py` | 检查 MyFeatureBooksActivity 是否有视频/图片书（VideoPagerAdapter 回归前置） | `python ai_tests/scripts/check_video_books.py` |
| 8. 详情页无 null 弹框 | `verify_book_info_no_null.py` | 书架长按进详情页，验证无 "book is null" toast + 更多菜单正常（含拷贝书籍URL） | `python ai_tests/scripts/verify_book_info_no_null.py` |
| 9. 播放器会话复用重置 | `verify_player_session_reset.py` | singleTask 旧会话驻留后台后新播放请求是否正确重置（下载视频→新播放意图切换；验证 onNewIntent 重置日志+UI标题切换+无崩溃） | `python ai_tests/scripts/verify_player_session_reset.py` |
| 10. 订阅源嗅探回归 | `verify_rss_sniff_after_download.py` | 下载视频→订阅源嗅探播放完整用户场景回归（嗅探链路 AppLog 标记+onNewIntent 重置+无旧会话残留；自动从 DB 选启用源视频文章） | `python ai_tests/scripts/verify_rss_sniff_after_download.py <legado.db>` |
| 11. 订阅源/DB 脱敏查询 | `query_rss_video_sources.py` | 脱敏查询模拟器 DB（只输出 id/计数/类型技术字段，不输出源名称/URL） | `python ai_tests/scripts/query_rss_video_sources.py <db_path>` |
| 15. 双包无崩溃验证 | `verify_no_crash.py` | 安装指定包→启动→进发现页→二轮重启复现缓存读取→logcat 崩溃模式分析（no-crash 2026-08-29 新增） | `python ai_tests/scripts/verify_no_crash.py --type debug\|release` |
| 16. 图片订阅源浏览链路 L2 | `l2_verify_image_gallery.py` | 自建最小图片源（本地 HTTP+合成 PNG+adb reverse）→RssSortActivity 确定性入口→图集页断言（进入/内容解析+图片下载/滑动 FATAL=0 前台存活）；sniff-regression-rss-image-crash Phase C 新增 | `python ai_tests/scripts/l2_verify_image_gallery.py` |
| 17. 测试辅助：local.xml 操作 | `repair_local_prefs.py` / `set_flag_appcrash.py` | 重建损坏的 shared_prefs/local.xml（privacyPolicyOk/appCrash 等必需键）/ 切换 appCrash 标记（回灌链路验证用）；均走 base64 安全通道 | `python ai_tests/scripts/repair_local_prefs.py [--crash]` / `set_flag_appcrash.py true\|false` |
| 16+. 订阅/主题头部联动验证 | `l2_verify_theme_rss_header_sync.py` | 订阅模式切换/发现布局即时生效/头部截图验证（theme-rss-header-layout-sync 新增） | `python ai_tests/scripts/l2_verify_theme_rss_header_sync.py --rounds 3` |
| 16b. 头部日夜亮度判定 | `l2_verify_header_brightness.py` | 截图像素亮度差判定主题跟随（状态无关断言） | `python ai_tests/scripts/l2_verify_header_brightness.py` |
| 16c. VL 兜底视觉判定 | `l2_vl_header_analysis.py` | 本地 Qwen3VL 对截图做目标化视觉判定（截图审查拦截兜底通道） | `python ai_tests/scripts/l2_vl_header_analysis.py` |
| 16d. 高亮规则切换验证 | `l2_verify_highlight_toggle.py` | 高亮规则复选框切换即时刷新四项断言 | `python ai_tests/scripts/l2_verify_highlight_toggle.py` |
| 16e. 文件夹封面弹框验证 | `l2_verify_rss_folder_cover_dialog.py` | 订阅文件夹封面弹框 L2 验证 | `python ai_tests/scripts/l2_verify_rss_folder_cover_dialog.py` |
| 16f. 文件夹间距验证 | `l2_verify_rss_folder_margin.py` | 订阅文件夹间距/列数滑条实时生效验证 | `python ai_tests/scripts/l2_verify_rss_folder_margin.py` |
| 16g. 播放器 UX 验证 | `l2_verify_video_ux_fixes.py` | 视频播放器五项 UX 修复 L2 | `python ai_tests/scripts/l2_verify_video_ux_fixes.py` |
| 16h. 阅读页三个点弹层验证 | `l2_verify_read_menu_overflow.py` | 三点弹层 7 项动作（高亮规则/字符集/目录规则/EPUB×3/段落规则修正）存在性+位置+互斥不变量+点击进管理页（read-menu-highlight-entry-restore 新增）；辅助探针 `probe_shelf.py` 输出书架层级锚点 | `python ai_tests/scripts/l2_verify_read_menu_overflow.py` |
| 16i. 书架层级探针 | `probe_shelf.py` | dump 书架文本/可点击节点（L2 脚本开书锚点修正前置探针） | `python ai_tests/scripts/probe_shelf.py` |
| 16j. 画质增强治理 L2 | `l2_verify_image_enhance_governance.py` | T1 效果链正向 / T3 守卫反向自动断言（prefs 置数+重启+真实播放+logcat 断言 buildEffects/applyImageEnhanceEffects），T2/T4/T5/T7 输出真机手动清单（enhance-switch-governance-fix v2 新增，白名单固化入库） | `python ai_tests/scripts/l2_verify_image_enhance_governance.py` |
| 16k. P0 沙箱/缓存命名空间 L2 | `l2_verify_p0_sandbox_cache.py` | P0 书源安全 S1 文件沙箱/S2 缓存命名空间环境就绪断言（T11-T14+T22 可验证子集）：prefs 写开关（bookSourceCacheScoped/bookSourceFileSandbox）+重启回读、caches 表 `book_source_cache_%` 前缀基线计数（三件套拉库本地查）、externalCache/source/ hex64 目录基线校验、FATAL=0；T11-T14/T22 触发项与 T14 删源清理输出手动清单（ng-p0-source-security-impl 新增，白名单固化入库，总线 2.12 落位口径） | `python ai_tests/scripts/l2_verify_p0_sandbox_cache.py [--scenario cache\|sandbox\|both]` |
| 16l. Compose S1 主框架 L2 | `l2_verify_compose_s1_main.py` | 底栏接线/双击回顶/顶栏压缩/书架配置即时生效（S1-1/2/3/5；compose-migration-status-audit B2 模板，复用层 `lib/compose_assert.py`，logcat -T 时间戳起点防历史污染） | `python ai_tests/scripts/l2_verify_compose_s1_main.py [--scenario all]` |
| 16m. Compose S2 管理列表 L2 | `l2_verify_compose_s2_source.py` | 滑选多选/拖拽排序/批量操作/三视图/排序选项/搜索筛选/菜单族/返回层级（S2-1~8；全程脱敏只输出计数/布尔/hash） | `python ai_tests/scripts/l2_verify_compose_s2_source.py [--scenario all]` |
| 16n. Compose S3 表单编辑器 L2 | `l2_verify_compose_s3_source_edit.py` | 6 Tab 遍历/未保存拦截/CodeView 全屏（CodeEditActivity 类名断言）/KeyboardToolPop/规则补全/保存校验（S3-1~6；⚠️ 依赖 tasks 4.3 C2 接线完成） | `python ai_tests/scripts/l2_verify_compose_s3_source_edit.py [--scenario all]` |
| 16o. Compose S4 详情双栈 L2 | `l2_verify_compose_s4_book_info.py` | 双栈分派栈顶类名断言/新栈核心锚点/旧栈 book is null 回归/菜单下沉（S4-1~4；verify_book_info_no_null 口径） | `python ai_tests/scripts/l2_verify_compose_s4_book_info.py [--scenario all]` |
| 16p. Compose S5 阅读浮层 L2 | `l2_verify_compose_s5_read_float.py` | 3s 自动隐藏/单一 activeSheet/BackHandler 优先级链/手势 R0-R4/磨砂 API31 分支（S5-1~5；R3 双指缩放=手动清单） | `python ai_tests/scripts/l2_verify_compose_s5_read_float.py [--scenario all]` |
| 16q. Compose S6 弹窗族 L2 | `l2_verify_compose_s6_dialog_tiers.py` | L1 Confirm/L2 Form/L3 Management 三宽度档断言+弹框独立窗口不变量（S6-1~4；dp=px/density cap 容差 8dp） | `python ai_tests/scripts/l2_verify_compose_s6_dialog_tiers.py [--scenario all]` |
| 16r. Compose 缓存清理页 L2 | `l2_verify_compose_cache.py` | CacheScreen 可达（CacheActivity 类名）/Compose 渲染锚点/无崩溃退出（registry 7.11be 销项） | `python ai_tests/scripts/l2_verify_compose_cache.py [--scenario all]` |
| 16s. 批量 UI 修复 0905 L2 | `l2_verify_ui_batch_fix_0905.py` | T1 崩溃弹框回归/T2 订阅头部收口/T3 三点菜单 6 项/T4 发现页分组反馈（像素差）/T5 死菜单清理/T6 无卷视频书沉浸式集数/T7 有卷回归/T8 布局切换+选集切换帧差判定（ui-batch-fix-0905 新增）；辅助 `diag_video_books.py` 查视频书库存 | `python ai_tests/scripts/l2_verify_ui_batch_fix_0905.py [--scenario all\|t1..t8]` |
| 16t. 真机反馈五项修复 0908 L2 | `l2_verify_bugfix_0908.py` | t1 替换净化单搜索框/t2 订阅文件夹视图切换无残留（全 UI 复刻用户路径）/t3 我的页书架媒体入口移除/t4 弹框 dim 补偿（alpha=60 标志级断言）/t5 万级书源域名分组稳定性（`--seed N` 造数 `--cleanup` 清理）；含 MEmu 陷阱沉淀：su 单字符串通道、u2.connect 破坏 su、prefs 直写被外观套件快照覆盖、screencap 黑屏 GPU 故障（real-device-bugfix-0908 新增） | `python ai_tests/scripts/l2_verify_bugfix_0908.py [--scenario all\|t1..t5] [--seed N] [--cleanup]` |
| 16u. 内置替换净化规则 L2 | `l2_verify_replace_edit.py` | T1 覆盖安装触发内置规则 id 迁移 DB 断言（负 id=0、正 id 1~12 齐全）/T2 点击内置规则编辑回显非空（hint 白名单区分真回显）/T3 新建空表单回归；⚠️ 沉淀两陷阱：①uiautomator dump 属性顺序 text 在 class 之前，须按整 node 解析 ②空 EditText 的 accessibility text 兜底显示 hint，须用 hint 白名单区分真回显；Compose 界面冷启动 dump 需带重试（builtin-replace-id-fix 新增） | `python ai_tests/scripts/l2_verify_replace_edit.py [--scenario all\|t1\|t2\|t3] [--skip-install]` |
| 16v. 应用内检查更新 L2 | `l2_verify_update_check.py` | T1 新包检查更新静默"暂无更新"（防"获取新版本出错"误报回归）/T2 低版本包（`-PappVersion=3.26.090520` 构建）弹更新框且 tagName=仓库 latest；直达 AboutActivity 入口，结束后自动恢复最新包（app-update-variant-fix 新增） | `python ai_tests/scripts/l2_verify_update_check.py [--scenario all\|t1\|t2] --low-apk <低版本apk>` |

### ⚠️ uiautomator 环境陷阱（2026-08-30 实锤，read-menu 任务沉淀）

1. **残留进程占锁**：前会话遗留 `com.github.uiautomator` 进程 → 新连接报 `AccessibilityServiceAlreadyRegisteredError`。处置：`adb shell "ps -A | grep uiautomator"` 定位 PID 后 `kill -9 <pid>`；**禁止 `pkill -f uiautomator`**（命令串自匹配误杀 shell 自身，exit 143）
2. **弹层手势敏感**：ModernActionPopup 弹层（Compose overlay 加在 decor view）上做纵向滑动手势会直接 dismiss 弹层（遮罩吞手势），脚本勿在弹层上滚动；长列表底部项断言改用可靠可见锚点 + 截图佐证
3. **书架卡片二跳**：书架卡片形态下文本节点 clickable=False（容器可点），点击后先进书籍详情页，需二跳"阅读/继续阅读"才进阅读页
4. **center tap 是切换语义**：阅读页点中央为菜单显隐切换，脚本判"菜单已开"后不可再盲点中央

> 2026-08-30 文档规整：原步骤 5/6/8（`fix_coverage_check.py`/`batch_source_test.py`/`collect_app_log.py`）所引脚本已不存在于 `ai_tests/`，删除对应步骤；修复点覆盖验证按本文"L2 验证场景清单"（`error_patterns` 场景）与"L2 观测通道"章节执行。

### ⚠️ 重要：签名配置（步骤1前置条件）

步骤1的 `quick_build_install.py` 调用 `./gradlew assembleAppDebug` 编译APK。**打包前必须配置签名**，否则生成的APK使用debug签名（`CN=Android Debug`），无法覆盖升级正式版。

**签名配置流程**（详见 [build-apk-guide.md](../../docs/project-flow/build-apk-guide.md) 第三章）：
1. 项目根目录必须有签名证书 `legado_release.jks`（RSA 2048位，有效期100年）
2. `local.properties` 必须配置签名参数（不入git）：
   ```properties
   RELEASE_STORE_FILE=legado_release.jks
   RELEASE_STORE_PASSWORD=<密码>
   RELEASE_KEY_ALIAS=legado
   RELEASE_KEY_PASSWORD=<密码>
   ```
3. 配置后 debug/release/coexist 三个包均使用同一正式签名（`signingConfigs.myConfig`），确保签名一致性

> **不变签名铁律**：发布后不能更换签名，否则用户无法覆盖升级。证书丢失不可恢复，务必妥善备份。

### ⚠️ 重要：Room WAL 模式（2026-07-13 新增）

`import_rss_source.py` 已更新支持 Room WAL 模式：
- **问题**：Room 使用 WAL 模式，如果只 pull/push 主 `.db` 文件，WAL 中的旧状态会在 App 启动时覆盖新导入的数据
- **修复**：脚本现在同时 pull `.db-wal`/`.db-shm` 文件，用 `PRAGMA wal_checkpoint(TRUNCATE)` 合并 WAL 到主 DB，push 后删除设备端 WAL/SHM
- **注意**：导入前必须 `am force-stop` App，否则 App 可能覆盖 DB

## 环境要求

```bash
# 必须使用 ai_tests venv Python（禁止公共 Python）
ai_tests\venv\Scripts\python.exe ai_tests/scripts/xxx.py

# 或激活虚拟环境后执行
ai_tests\venv\Scripts\activate
python ai_tests/scripts/xxx.py
```

## 禁止行为

- ❌ 在 `temp/` 目录创建临时测试脚本（本会话已创建4个临时脚本，这是反模式）
- ❌ 每次测试从头编写 Python 脚本
- ❌ 手动执行 ADB 命令（应通过脚本执行，路径常量在 config.py）
- ❌ 硬编码路径（必须复用 `ai_tests/config.py` 中的常量）
- ❌ 不读取本 SOP 就开始测试

## 允许行为

- ✅ 扩展现有脚本的功能（修改 `ai_tests/scripts/` 下的脚本）
- ✅ 新增脚本到 `ai_tests/scripts/` 目录（当现有脚本无法覆盖新场景时）
- ✅ 修改脚本参数适配不同测试场景
- ✅ 复用 `ai_tests/lib/` 中的模块（memu_controller/apk_deployer/ui_executor）
- ✅ 复用 `ai_tests/config.py` 中的常量（ADB_PATH/MEMUC_PATH/MEMU_ADB_HOST 等）

## L2 验证场景清单

视频播放器相关功能验证场景（每个场景对应 l2_verify_video_player.py 的一个 `--scenario` 参数）：

| 场景 | 说明 | 关键验证点 |
|------|------|-----------|
| `swipe_article` | 上下滑动切换文章 | ⚠️依赖已移除的SwipeTest临时日志，会显示"未触发" |
| `pagination` | 分页加载 | ⚠️同上 |
| `preload` | 预缓冲 | ⚠️同上 |
| `position_memory` | 位置记忆 | ⚠️同上 |
| `backward_compat` | 向后兼容 | 无SwipeTest日志触发=通过 |
| `buffer_progress` | 缓冲进度条更新 | ⚠️依赖已移除的F1临时日志 |
| `control_visibility` | 控件自动隐藏 | ⚠️依赖已移除的F2临时日志 |
| `error_patterns` | ★推荐★ 错误模式验证 | P2 Malformed URL / P1-C destroy failed / P1-A ClassCastException / P2-A IllegalBlockSize 四种错误模式0出现=通过 |
| `all` | 全部场景 | 含error_patterns |

### SwipeTest 临时日志状态说明

> **2026-07-13 更新**：SwipeTest/F1/F2 临时日志已在任务 #69/#77/#109 中移除（验证通过后清理）。
>
> 依赖这些日志的场景（swipe_article/pagination/preload/position_memory/buffer_progress/control_visibility）会显示"未触发"，这是**预期行为**，非代码问题。
>
> **验证修复点请使用 `error_patterns` 场景**：通过 logcat 直接分析 4 种错误模式（Malformed URL/destroy failed/ClassCastException/IllegalBlockSizeException）是否为 0，永久有效。

### TTS 功能级联调测试（批次F F10，2026-09-11 新增）

脚本：`ai_tests/scripts/l2_verify_tts_engine.py`（L2-a 引擎/音色枚举+模板绑定）、`ai_tests/scripts/l2_verify_tts_read.py`（L2-b 朗读推进）。

覆盖度矩阵：

| 层 | 覆盖项 | 断言依据 | 备注 |
|----|--------|---------|------|
| 单测（JVM） | HostAccessStrategy 退避/黑名单、AppLog 频控、下载命名、高亮长对话+ReDoS | app/src/test 对应用例 | 已实施 |
| 模拟器 L1 | 装机启动零 FATAL | quick_build_install.py | |
| 模拟器 L2-a | 引擎枚举（dumpsys texttospeech）/音色枚举（TtsTrace"音色枚举 engine=... voices=N"）/模板绑定 UI | l2_verify_tts_engine.py | CloneTTS 需先 adb install+首启初始化 |
| 模拟器 L2-b | 朗读启动（play 路径判定）/multiRole 分段（tag=narration|dialogue）/推进/无 Error | l2_verify_tts_read.py | 本地书免网络；未激活模板时 multiRole=skip |
| 真机 L3 | 听感/音色自然度/后台保活/功耗/CloneTTS benchmark | 仅用户验收 | 模拟器 x86 RTF 可能>1 |
| 边界 | CloneTTS 未注册系统 voices → 走 HTTP 通道 L2-c（HttpReadAloudService 路径） | TtsTrace 请求行 | 降级预案 |

模拟器可覆盖约 80% 功能面；下载 CloneTTS APK 渠道=官方 GitHub Releases（sipeter/CloneTTS）；MultiTTS 无官方渠道默认不装。

## SwipeTest 临时日志规范

> **P0 规则23（用户表扬）**：复杂功能实施必须添加临时日志验证

1. **添加日志**：在关键路径添加 `Log.d("SwipeTest", "xxx: param=value")`
2. **抓取日志**：`python ai_tests/scripts/swipe_test_log.py capture`
3. **分析日志**：`python ai_tests/scripts/swipe_test_log.py analyze`
4. **验证通过后移除**：所有 SwipeTest 日志必须在验证通过后移除

## UI 全量用例复测协议（F-UI-THEME，2026-08-26 新增）

> 用户强制：复测必须按脚本跑、按修复点精准选用例，**禁止手动 adb 导航猜页面**。

1. **用例筛选**：`run_e2e.py --tc TC-xxx`（单值）按修复面挑相关用例；修复面=订阅链 → TC-113/052/051；视频 → TC-060/114；图片 → TC-115；主题设置 → TC-001~011。不允许全量跑兜底。
2. **verdict=manual 是正常现象**：F-UI-THEME 多为 VL 判定型预期，analyzer 无规则可判自动标 manual，但**步骤已如实执行、证据（screenshot/xml/logcat/activity_stack/meminfo）完整落盘**，最终判定走 `ui_visual_verify.py --evidence <report根> --v0 issue-list-v0.md` 的 VL 聚合（observations + vl_new_candidates）。
3. **logcat 针对性计数**：修复点相关异常关键词计数=0 才算过（例：CursorWindow/SQLiteBlobTooBig 溢出修复 → `logcat -d | Select-String CursorWindow|SQLiteBlobTooBig|获取数据失败` = 0）。
4. **报告**：三要素齐全（证据落盘 + VL 观察 + logcat 计数）后，问题结论登记 issue-list-v1；无新问题则记录"验证过"。
5. **禁忌**：不要用 `uiautomator dump` 猜订阅入口往返；确定性入口（如收藏页 `am start ...RssFavoritesActivity`）仅用于补充覆盖，仍要结论 + 计数。

## 脚本维护规则

- 脚本修改后必须更新本 SOP 的脚本表格
- 新增脚本必须在"L2验证场景清单"或新表格中记录用法
- 脚本必须包含 `if __name__ == "__main__":` 入口和 argparse 参数解析
- 脚本必须 import config 常量，禁止硬编码路径

## Cronet 库预下载检查（2026-07-18 v5 反哺新增）

> **背景**：真机测试发现部分 HTTPS 源加载失败，logcat 显示 `libcronet.so FileNotFoundException`。
> legado 使用 Cronet 库（基于 Chromium 网络栈）处理 HTTPS 请求，Cronet 库需要从网络下载或随App打包。
> 模拟器首次安装 App 时未自动下载 Cronet 库，导致 HTTPS 源全部加载失败（HTTP 源不受影响）。

### 触发条件

真机测试前必须执行 Cronet 库预下载检查，特别是：
- 首次安装 App 后的第一次测试
- 模拟器重置/重装后的第一次测试
- HTTPS 源加载失败时（优先检查 Cronet 库可用性）

### 诊断方法

**症状识别（logcat 关键词）**：
- `libcronet.so FileNotFoundException` - Cronet 库文件缺失
- `UnsatisfiedLinkError` + `cronet` - Cronet 库链接失败
- `Failed to load native library` + `cronet` - Cronet 库加载失败

**诊断脚本**（用 venv Python 执行）：

```python
# 检查 Cronet 库可用性
import subprocess
ADB = "adb"  # 从 config.py 导入
HOST = "127.0.0.1:21503"  # 从 config.py 导入
PKG = "io.legado.app"  # 从 config.py 导入

# 1. 检查 Cronet 库文件是否存在
r = subprocess.run([ADB, '-s', HOST, 'shell', 'su', '-c',
                    f'ls /data/data/{PKG}/files/cronet/ 2>/dev/null'],
                   capture_output=True, timeout=10)
files = r.stdout.decode('utf-8', errors='ignore').strip()
has_cronet_so = 'libcronet' in files
print(f'Cronet 库文件存在: {has_cronet_so}')

# 2. 检查 logcat 是否有 Cronet 相关错误
r = subprocess.run([ADB, '-s', HOST, 'logcat', '-d', '-t', '500'],
                   capture_output=True, timeout=15)
log = r.stdout.decode('utf-8', errors='ignore')
has_cronet_error = ('FileNotFoundException' in log and 'cronet' in log.lower()) or \
                   ('UnsatisfiedLinkError' in log and 'cronet' in log.lower())
print(f'Cronet 库错误: {has_cronet_error}')
```

### 修复流程

| 步骤 | 操作 | 命令/说明 |
|------|------|---------|
| 1. 诊断 | 检查文件存在性 + logcat错误 | 见上方诊断脚本 |
| 2. 触发下载 | 启动 App 等待60秒自动下载 | `adb shell am start -n {PKG}/.ui.MainActivity` 后 sleep 60 |
| 3. 复检 | 再次检查文件存在性 | 确认 `libcronet.so` 已下载 |
| 4. 重测 | 重新跑 scenario 验证 | HTTPS 源应能正常加载 |

### 集成到标准测试流水线

**更新后的标准测试流水线**：

```
编译 → 安装 → 启动App等待Cronet下载(60秒) → L1验证 → 导入订阅源 → L2验证 → 日志分析
                              ↑ 新增步骤
```

**新增检查清单**（L1验证前必做）：

```python
# 在 quick_build_install.py 后增加 Cronet 检查
def ensure_cronet_ready():
    """确保 Cronet 库可用（首次安装后必须执行）"""
    # 1. 启动 App 触发自动下载
    subprocess.run([ADB, '-s', HOST, 'shell', 'am', 'start',
                    f'-n {PKG}/.ui.MainActivity'], timeout=10)
    print('等待60秒让 App 自动下载 Cronet 库...')
    time.sleep(60)

    # 2. 检查是否下载成功
    r = subprocess.run([ADB, '-s', HOST, 'shell', 'su', '-c',
                        f'ls /data/data/{PKG}/files/cronet/'],
                       capture_output=True, timeout=10)
    if 'libcronet' in r.stdout.decode('utf-8', errors='ignore'):
        print('✅ Cronet 库下载成功')
        return True
    else:
        print('❌ Cronet 库下载失败，HTTPS 源将无法加载')
        return False
```

### 实战数据（2026-07-18）

| 指标 | 数据 |
|------|------|
| HTTPS 源加载失败数 | 7个 |
| 诊断结果 | 全部命中 `libcronet.so FileNotFoundException` |
| 触发下载后 | Cronet 库成功下载 |
| 重测结果 | 7个 HTTPS 源全部加载成功 |
| HTTP 源影响 | 无（只有 HTTPS 依赖 Cronet） |

### 教训

1. **真机测试前必须预下载 Cronet 库**（首次安装App后等待60秒）
2. HTTPS 源加载失败时，优先检查 Cronet 库可用性（而非 DNS 或网络问题）
3. logcat 关键词：`libcronet.so FileNotFoundException` / `UnsatisfiedLinkError` / `Failed to load native library`
4. Cronet 库位置：`/data/data/{PKG}/files/cronet/libcronet.so`
5. HTTP 源不受影响（只有 HTTPS 依赖 Cronet），可用于区分诊断

### L2 观测通道与 adb 数据传输铁律（2026-08-30 sniff-regression-rss-image-crash 沉淀）

1. **文件通道才是确定性观测面**：AppLog 内容在 recordLog 关闭时不落盘（仅 ERROR 级走 logcat），且 logcat 主缓冲在真机上约 10~15 秒即可被刷滚驱逐早期条目。验证"某日志是否产生"必须以**拉取 appLog 文件 grep** 为准，`logcat -d` 抓取窗口越早越好（必要时启动后 3~5 秒即 dump）
2. **adb 输出落盘禁止 PowerShell `>` 重定向**：`adb shell cat xxx > local` 会产出损坏/截断文件（铁证：shared_prefs 拉取仅剩 51 字节，回写后损坏设备端默认 prefs）。必须走 **base64 通道**：设备端 `base64 <file>` → 本地 `[Convert]::FromBase64String` 解码；写回反向同理。与既有教训"git show > file 毁文件"同源（PowerShell 管道编码问题）
3. **shared_prefs 直改风险分级**：`local.xml`（LocalConfig）与 `<pkg>_preferences.xml`（defaultSharedPreferences）是不同文件，改前必须先核实目标 key 所在文件（源码 object 声明处 `getSharedPreferences("name", ...)`）；回写损坏会造成该模拟器设置丢失（SharedPreferences 解析失败静默回退空表）
4. **adb shell 多参数列表传参会拆散 su -c 命令**：subprocess 列表形式 `["su","-c","base64 <path>"]` 传给 adb shell 后，su 只吃到 "base64" 一个 token（-c 后内容被按空格拆散）；必须把整条命令作为**单个字符串**传给 adb shell（`sh("su -c 'base64 <path>'")`）。铁证：l2_verify_theme_rss_header_sync.py 修复前后 prefs 读取从全失败变全成功
5. **shared_prefs 值在 value="..." 属性而非文本节点**：解析 `<pkg>_preferences.xml` 时布尔/整型是 `<boolean name="k" value="true"/>` 自闭合属性形式，字符串才是 `<string name="k">v</string>` 文本节点；正则需双模式匹配。铁证：仅匹配文本节点时 modernRssPage 读到空白

## u2 交互陷阱（2026-08-30 theme-rss-header-layout-sync 沉淀）

> uiautomator2（u2）在 Legado Compose 界面上的高频踩坑点，元素定位失败先对照本清单。

1. **StaleObjectException**：u2 selector 持有的节点在 Activity 重建/重组后失效 → 改用 dump_hierarchy+正则取 bounds+input tap 坐标点击（dump→点击→重试闭环，参考 `l2_verify_theme_rss_header_sync.py` 的 `click_by`）
2. **Compose Dialog 是独立窗口**：弹框开启期间 dump 拿不到主界面节点，锚点判定须放弹框关闭后
3. **底部导航为 Compose 绘制**：dump 无文本节点 → 用 content-desc 或 resource-id（menu_rss）取坐标 tap，先 dump 真实 tab 顺序（顺序可配置）
4. **toybox sed 经 su 多层 shell 传参**：表达式必须内层双引号包裹，否则被空格截断报 bad pattern
5. **模拟器 screencap 陈旧帧**：截图前 sleep 或双帧对比；截图审查拦截时改用像素亮度分析（`l2_verify_header_brightness.py`）或 VL 判定（`l2_vl_header_analysis.py`）
6. **包名带构建类型后缀**：debug 包=io.legado.miss.app.debug，am start/pm 命令须用实际安装包名（config.PACKAGE 动态拼接）
7. **screencap 恒定黑帧（2026-09-08 实锤+已修复）**：MEmu `graphics_render_mode=0`（DirectX）下 GPU readback 通路故障——UI 显示正常但 screencap 输出恒定 3669B 黑 PNG（特征值：非黑帧最小 >17KB），`stop;start` 重启 framework、重启实例、微 swipe 唤醒、冷启动首帧均无效。**修复**：改 VM 配置 `MemuHyperv VMs\MEmu\MEmu.memu` 的 `graphics_render_mode` 0→1（OpenGL）+ `memuc stop/start -i 0`，一次性修复。排查口诀：3669B=黑帧特征值；宿主 CopyFromScreen/PrintWindow 均不可靠（PrintWindow 对动态重绘区返回底色，勿据其判定"图标消失"类问题）
8. **adb shell 多参数拼接（2026-09-08 实锤）**：`subprocess` 传 `["shell","sh","-c",cmd]` 会被设备端按空格拼接成 `sh -c cat ...`（cat 读 stdin 输出空）→ `> file` 重定向把目标文件截断为 0 字节（prefs 写回实测铁证）。整条命令必须**单参数**传递：`["shell", "cat a > b && cat b"]`

## 像素亮度差判定方法论（2026-08-30 沉淀）

对日/夜或主题 A/B 两态截图，裁剪目标区域（如顶栏 6%~14% 高度）计算灰度均值，**差值 ≥60 判定分化成立**；初始主题态未知时标签可能互换，用差值绝对值做状态无关断言；脚本模板 `l2_verify_header_brightness.py`。

## L1/L2/L3 完成级别权威定义

> 本节为**全项目唯一权威定义**，其他文档引用此处，禁止另行定义。

| 级别 | 定义 | 判定标记 |
|------|------|---------|
| **L1 = 代码完成** | 文件存在 + 编译通过 | ⚠️ 不代表可用 |
| **L2 = 功能验证** | 关键功能可运行 + 输出正确 | ⚠️ 不代表真实数据场景通过 |
| **L3 = 场景验证** | 真机真实数据回测通过 | ✅ 交付级 |
