# Legado（阅读M）

> Android 开源电子书阅读器（fork 自 [legado-E](https://github.com/Luoyacheng/legado-E)，私有仓 `github.com/syq17496152/legado.git`）。核心为自定义书源规则引擎（CSS/JSONPath/XPath/正则/JS 五种解析），含内置视频播放器/订阅源/高亮规则/自动任务等扩展功能。遇到与原版行为不一致的问题，先对比原版代码定位回归原因。

## 构建与测试（快速命令）

| 操作 | 命令 |
|------|------|
| 一键打包（测试包） | `build-legado.bat` |
| 正式包 | `build-legado.bat release` |
| 共存包 | `build-legado.bat debug io.legado.app` |
| 底层 Gradle 任务 | `./gradlew assembleAppDebug` / `assembleAppRelease`（productFlavors 仅 `app`，App 首字母大写，**不是** `assembleDebug`） |
| 改签名/strings.xml 后强制重打 | `./gradlew assembleAppRelease --rerun-tasks` |
| 单元测试 / Lint | `./gradlew test` / `./gradlew lint` |
| Vue3 Web 前端 | `npm run dev` / `npm run build`（在 `modules/web/` 下；build 含 type-check + vite build + `sync.js`，本地即可完成） |

> `build-legado.bat` **硬编码本机环境**（`JAVA_HOME=C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot`、`ANDROID_HOME=C:\Android\Sdk`、`GRADLE_USER_HOME=F:\gh`、`PROJECT_DIR`），换机器需先改头部。完整打包流程见 `docs/project-flow/build-apk-guide.md`。

## 关键文件速查

| 用途 | 路径 |
|------|------|
| 应用入口 | `app/src/main/java/io/legado/app/App.kt` |
| 规则引擎 | `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt` |
| 阅读核心（全局单例） | `app/src/main/java/io/legado/app/model/ReadBook.kt` |
| 网络书核心 | `app/src/main/java/io/legado/app/model/webBook/WebBook.kt` |
| 数据库 | `app/src/main/java/io/legado/app/data/AppDatabase.kt`（Room，legado.db，当前 v108，schema 在 `app/schemas/`；**版本号以 AppDatabase.kt `version` 字段为准，文档禁止硬编码快照**） |
| 依赖版本 | `gradle/libs.versions.toml` |

## 代码约束

### Code Style 核心
- 协程用自定义 `Coroutine.async{}...onError{}.onSuccess{}` 链式封装（非标准 launch+try/catch）
- 异步双版本：`xxx()` 返回 `Coroutine<T>` + `xxxAwait()` 挂起函数
- 核心业务用 `object` 单例（`ReadBook`, `WebBook`, `AppConfig`），不引入 DI 框架
- Room 实体：`data class` + `@Parcelize` + `@Entity`，字段全部有默认值
- 错误处理用 `kotlin.runCatching`（带 `kotlin.` 前缀），字符串判空用 `isNullOrBlank()`
- ❌ 禁用 Timber / `CoroutineExceptionHandler`；日志用 `AppLog.put()`，异常用 `Coroutine.onError`

> 完整规范：`docs/project-rules/naming_rules.md` | `checkstyle_rules.md`

### Landmines（版本锁定，勿升级）
| 依赖 | 版本 | 原因（详见 `gradle/libs.versions.toml` 注释） |
|------|------|------|
| jsoup | 1.16.2 | 新版破坏性变更 jsoup#2017，涉及 AnalyzeByJSoup/JsoupXpath |
| rhino | 1.8.1 | 新版用 API 33 以下的 VarHandle.compareAndExchange（desugaring 不覆盖） |
| commons-text | 1.13.1 | 新版用 API 24 以下的 Arrays.setAll（desugaring 不覆盖），minSdk=23 会崩 |
| hutool | 5.8.22 | 书源加解密依赖 |
| protobuf | 4.26.1 | 兼容性锁定 |

- **ReadBook 全局单例**：多 Activity 共享，改状态需 `@Synchronized` 或 `Mutex` 保护
- **NoStackTraceException**：所有业务异常继承此类，覆写 `fillInStackTrace()`

> 完整陷阱：`docs/project-rules/exception_rules.md` | `logging_rules.md` | `architecture_rules.md`

## 强制规则（任务完成门禁）

> 上下文压缩恢复与 AskUserQuestion 响应由全局规范 `~/.trae-cn/user_rules/core-spec.md` 统一管理，恢复时强制加载。

### 1. 版本交付同步（updateLog）
任何代码变更编译前，必须基于 `git diff` 分析真实变更更新 `app/src/main/assets/updateLog.md`（追加在 `## cronet版本:` 之后、已有条目之前；面向用户语言；禁止文字合并旧条目、禁止交付阶段才补写）。逐文件对照变更列表审计，不漏项。完整规范：`docs/project-rules/version-delivery-sync.md`。

### 2. AI 自动端到端测试（步骤 5.5）
OpenSpec 步骤 5→6 之间必须真机/模拟器验证，禁止只改代码不测试。
- 测试前必读 SOP：`ai_tests/docs/fixed_test_workflow.md`
- 必须用 `ai_tests\venv\Scripts\python.exe`（禁止公共 Python）
- 全量用例：`python ai_tests/run_e2e.py --tc all`；快速 L2 验证用 `ai_tests/scripts/` 下脚本
- 固定脚本入口：`quick_build_install.py`（编译+安装+L1）、`import_rss_source.py`、`l2_verify_video_player.py`、`swipe_test_log.py`
- 禁止在 `temp/` 创建临时测试脚本

> 完整规范（八步流程+固化层+反模式）：`docs/project-rules/ai_e2e_testing_workflow.md`

### 3. 真机测试包选择（禁止混用）
| 任务类型 | 包 | 包名 |
|---------|-----|------|
| 项目代码优化/开发 | 测试包 | `io.legado.miss.app.debug` |
| 书源/订阅源 Skill 真机测试 | 正式包 | `io.legado.miss.app.release` |
| 与原版共存 | 共存包 | `io.legado.app.debug` |

- ❌ 同一模拟器实例同时操作多个包（Activity 抢占，铁证 2026-07-25）
- ❌ 代码优化用正式包 / Skill 测试用测试包

> 完整规范：`docs/project-rules/package-naming.md`

### 4. 任务完成前检查清单
1. 工具输出第一动作扫描敏感词，替换为代号（output-safety.md）
2. Grep `android.util.Log.d|android.util.Log.e` 确认无残留调试日志（logging-during-refactoring.md）
3. updateLog 已更新（编译前）
4. 文档同步已检查：issues-found/tasks/INDEX/ai_memory_main 是否最新（version-delivery-sync.md）
5. 大型任务结束自觉沉淀（spec-sedimentation-mechanism.md）
6. issues-found.md 记录所有真机问题（real-device-test-reuse.md）
7. **回复最后一个工具调用必须是 AskUserQuestion（确认完成），禁止文字总结后结束**（core-spec.md）

### 5. 书源/订阅源自测交付
新生成或优化的书源/订阅源必须自测通过才算完成：每一步规则先到 Legado 源码核实（禁凭经验臆测）；自测不通过=未完成。完整规范（5阶段闭环+陷阱清单+JVM仿真器）：`.trae/skills/legado-source-creator/SKILL.md`

### 6. 打包/构建后必须清理构建 daemon（强制门禁）
构建会产生残留 `Gradle daemon` + `Kotlin daemon`（实测各 2.6~4.2GB），`--no-daemon` 管不住 Kotlin daemon，空闲默认 2~3 小时才自退，频繁打包必打爆 32G 内存。
- ✅ 走 `build-legado.bat` → 已内置 `:STOP_DAEMON` 自动清场
- ⚠️ 走 **直接 `gradlew assembleAppDebug/assembleAppRelease`（无 `--no-daemon`）或 IDE/Run** → 构建结束后**必须**执行 `stop-daemons.bat` 补一次清场，禁止直接结束不管
- 命令必须带 App 前缀（`assembleAppDebug`/`assembleAppRelease`），禁止 `assembleDebug`
> 完整规范：`docs/project-flow/build-apk-guide.md` §4.10；配套 `gradle.properties` 已限制 `-Xmx3g` + `kotlin.daemon.jvmargs` + `daemon.idletimeout=600000`

## 记忆系统（memory-mechanism-redesign，AD-11 已启用）
| 配置项 | 值 |
|--------|-----|
| 项目记忆权威源 | `.trae/memory/ai_memory_main.md`（Edit/Write 可编辑） |
| C盘 project_memory.md | Deprecated，不读不写（权限受限） |
| conv_id 机制 | 已废弃（2026-07-27） |
| 多任务并发 | 压缩恢复后读 ai_memory_main → 活跃任务>1 时 AskUserQuestion 询问当前窗口处理哪个 |
| 时间戳工具 | `date '+%Y-%m-%d %H:%M:%S'`（gitbash）或 `Get-Date`（PowerShell），24H 制 |
| 归档规则 | 用户反馈超 7 天 → `.trae/memory/archived/feedback/YYYYMM.md`；主文件 >50KB → `archived/main_history_{YYYYMMDD}.md` |

## 子规范加载表（按任务类型必须加载）

| 任务类型/场景 | 必须加载的子规范 |
|---------|----------------|
| 新功能/优化/Bug修复/重构 | `openspec-workflow.md` |
| 代码变更任务 | `logging-during-refactoring.md` + `version-delivery-sync.md` + `ai_e2e_testing_workflow.md` + `real-device-test-reuse.md` |
| 复杂任务（50+文件） | `complex-task-pipeline.md` |
| 使用 Agent 子代理 | `sub-agent-quality-management.md` |
| 书源/订阅源/RSS源 | `legado-source-creator/SKILL.md` |
| 网络层/前端/协程/WebView 优化 | `forks-reference.md` + `forks_comparison_methodology.md`（对比方法论） |
| 前端 UI 改造/样式统一/页面迁移 | `ui-standards/architecture.md`（UI 设计架构体系总纲，**必读**：四组件族基线+取色唯一基线+开发门禁，防私自拉组件/硬编码色）+ `frontend-ui-standards.md` + `compose-ui-engineering` |
| 打包构建/包名/APK发布 | `package-naming.md` + `apk-publish-workflow.md` |
| 改动功能前（门禁） | `global-thinking-checklist.md`（前端入口+后端接口+数据库+覆盖安装+使用场景+回填点 6 维盘点） |
| 数据库变更 | `database-migration-safety.md` |
| 大型任务（10+文件/多Issue） | `work-methodology.md` |
| 错误发生后 | `spec-sedimentation-mechanism.md` |

## 快速入口
- **文档索引**：[docs/INDEX.md](./docs/INDEX.md)｜**任务导航（14模块代码锚点）**：[docs/project-flow/task-navigation.md](./docs/project-flow/task-navigation.md)
- **命令/文件/版本速查**：[docs/project-flow/quick-reference.md](./docs/project-flow/quick-reference.md)｜**项目规范目录**：[docs/project-rules/](./docs/project-rules/)
- **规则引擎详解**：[docs/project-flow/architecture/rule-engine.md](./docs/project-flow/architecture/rule-engine.md)
- **AI 自动化测试**：[ai_tests/README.md](./ai_tests/README.md)
- **Git 规范**：[docs/project-flow/git-repo-management.md](./docs/project-flow/git-repo-management.md)（master 分支，Conventional Commits，`temp/`/`output/`/`*.jks`/`*.log` 不入库）
