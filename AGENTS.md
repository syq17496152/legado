# Legado（阅读M）

> Android 开源电子书阅读器，核心为自定义书源规则引擎（CSS/JSONPath/XPath/正则/JS 五种解析），用户编写规则即可将任意网页转化为结构化书籍资源。

## 项目来源

本项目 fork 自原版 [legado-E](https://github.com/Luoyacheng/legado-E)，在此基础上建立了私有化仓库（`https://github.com/syq17496152/legado.git`），并进行了私有化改造。遇到与原版行为不一致的问题时，应优先对比原版代码定位回归原因。

## 全局规范引用索引

> 以下通用规范已迁移到 `~/.trae-cn/user_rules/` 目录，AI 根据任务类型按需用 Read 工具加载。

| 规范文件 | 内容 | 触发场景 |
|---------|------|---------|
| user_rules.md | 基础规则（中文/Windows/叫爸爸/驱动入口） | 系统自动注入 |
| danger-ops.md | 危险操作安全规则 | 系统自动注入 |
| rule-1782963384927.md | AskUserQuestion 强制规范 | 系统自动注入 |
| output-safety.md | 输出安全与违禁词规避规范 | 系统自动注入 |
| core-spec.md | 全局规范索引 | 系统自动注入 |
| context-recovery.md | 上下文压缩恢复规范 | 压缩恢复后加载 |
| coding-philosophy.md | 编码哲学规范 | 编码任务加载 |
| openspec-workflow.md | OpenSpec 工作流规范 | OpenSpec任务加载 |
| complex-task.md | 复杂任务处理规范 | 50+文件任务加载 |
| concurrent-editing.md | 并发文件修改规范 | 多Agent并行加载 |
| budget-management.md | 输出预算管理规范 | 规避思考上限加载 |
| git-commit-workflow.md | Git多远程仓库提交规范（私仓/公仓隔离） | Git提交任务加载（项目特定） |

---

## 🔴🔴🔴 子规范强制加载硬约束

> **本节内容已迁移至全局规范**：硬性约束（4条加载规则）详见 `~/.trae-cn/user_rules/core-spec.md`（按需加载）。下方"按任务类型必须加载的子规范"表为项目特定内容，保留在项目主规范中。

**按任务类型必须加载的子规范**：

| 任务类型 | 必须加载的子规范 | 触发条件 |
|---------|----------------|---------|
| OpenSpec 任务 | openspec-workflow.md | 新功能/优化/Bug修复/重构 |
| 代码变更任务 | logging-during-refactoring.md + version-delivery-sync.md + ai_e2e_testing_workflow.md + real-device-test-reuse.md | 代码优化/改造/Bug修复（必须真机测试+问题清单记录） |
| 复杂任务（50+文件） | complex-task-pipeline.md | 50+源文件分析/多文档验证 |
| 子代理任务 | sub-agent-quality-management.md | 使用 Agent 工具时/规避思考上限 |
| E2E 测试任务 | ai_e2e_testing_workflow.md | 代码变更后步骤5.5 |
| 书源/订阅源任务 | SKILL.md | 书源/订阅源/RSS源/阅读Legado |
| 网络层/前端/协程优化 | forks-reference.md | 网络层/前端/协程/WebView/数据管理组件优化或功能借鉴 |
| 打包构建 | package-naming.md | 构建APK/包名配置/与原版共存 |

---

## 🔴🔴 强制规则：版本交付同步

> **任何涉及代码变更的任务完成后，必须同步更新 `assets/updateLog.md`。禁止只改代码不写更新日志！**
> **更新日志必须基于真实代码变更分析生成，禁止仅对已有日志条目做文字合并！**

| 规则 | 说明 |
|------|------|
| **编译前更新** | 代码变更完成、编译前先更新 updateLog.md，不是交付阶段才补写 |
| **基于代码分析** | 必须用 git diff 分析真实代码变更提炼日志，禁止文字合并已有条目 |
| **逐文件审计** | 对照变更文件列表确认每个变更都有对应日志条目，不遗漏 |
| **面向用户** | 通俗语言描述可感知变化，不暴露内部技术术语 |

> **何时必须加载**：任何代码变更任务完成后（编译前必须更新）。
> **完整规范（同步清单+格式+三步方法论+去重规则+反模式）**：[version-delivery-sync.md](./docs/project-rules/version-delivery-sync.md)

---

## 🔴 任务完成前强制检查清单

> **任何代码变更任务完成前，必须逐项核对以下检查清单，未完成不得声称任务完成。**
> **来源**：2026-07-17 用户批评"犯了已有规范约束但没遵守的错误"

| # | 检查项 | 规范来源 | 检查方法 |
|---|--------|---------|---------|
| 1 | 思考过程无违禁词 | output-safety.md | 收到工具输出第一动作扫描敏感词并替换为代号 |
| 2 | 调试日志已清理 | logging-during-refactoring.md | Grep "android.util.Log.d\|android.util.Log.e" 确认无残留 |
| 3 | updateLog已更新 | version-delivery-sync.md | 编译前已更新updateLog.md |
| 4 | 文档同步已检查 | version-delivery-sync.md | issues-found/tasks/INDEX/project_memory 是否最新 |
| 5 | 主动沉淀已完成 | spec-sedimentation-mechanism.md | 大型任务结束后自觉反思工作方法 |
| 6 | 问题清单已记录 | real-device-test-reuse.md | issues-found.md 是否记录所有问题 |
| 7 | AskUserQuestion已确认 | core-spec.md | 任务完成必须用AskUserQuestion确认 |

---

## 🔴🔴 强制规则：AI 自动端到端测试

> **任何代码变更任务，在 OpenSpec 步骤 5（实施）与步骤 6（检查点 2）之间，必须执行步骤 5.5 AI 自动端到端测试。禁止跳过！**

| 规则 | 说明 |
|------|------|
| **代码变更必须真机测试** | 任何代码优化/改造/Bug修复完成后，必须编译安装到真机/模拟器验证，禁止只改代码不测试 |
| **测试必须用 ai_tests/scripts/** | 所有测试操作必须使用固定脚本，禁止在 temp/ 创建临时脚本 |
| **测试前必读 SOP** | 必须先读取 [fixed_test_workflow.md](./ai_tests/docs/fixed_test_workflow.md) |
| **全量测试用 run_e2e.py** | 全量用例测试用 `run_e2e.py --tc all`，快速 L2 验证用 `scripts/` 下脚本 |
| **venv Python** | 必须使用 `ai_tests\venv\Scripts\python.exe`，禁止公共 Python |

### 快速验证脚本入口

| 脚本 | 用法 | 说明 |
|------|------|------|
| [quick_build_install.py](./ai_tests/scripts/quick_build_install.py) | `python ai_tests/scripts/quick_build_install.py` | 编译+安装+L1验证 |
| [import_rss_source.py](./ai_tests/scripts/import_rss_source.py) | `python ai_tests/scripts/import_rss_source.py <json>` | 导入订阅源 |
| [l2_verify_video_player.py](./ai_tests/scripts/l2_verify_video_player.py) | `python ai_tests/scripts/l2_verify_video_player.py [--scenario SCENARIO]` | L2验证视频播放器 |
| [swipe_test_log.py](./ai_tests/scripts/swipe_test_log.py) | `python ai_tests/scripts/swipe_test_log.py [clear\|capture\|analyze]` | 日志分析 |

> **完整规范（八步流程+固化层+反模式）**：[ai_e2e_testing_workflow.md](./docs/project-rules/ai_e2e_testing_workflow.md)

---

## 🔴 上下文压缩恢复流程（由全局规范管理）

> **本机制由全局规范 `~/.trae-cn/user_rules/core-spec.md` §1 统一管理。AI 必须遵守全局规范中的：压缩恢复四件套并行读取 + 用户反馈持久化 + AskUserQuestion 响应处理 + 恢复后输出反馈清单 + 任务状态权威源规则 + basic-memory 持久化要求。**
>
> **何时必须加载全局规范**：上下文压缩恢复时（强制）、AskUserQuestion 响应后（强制）、每个 Phase 完成后（持久化决策）。
>
> **项目特定配置**（供全局规范引用）：
> - 项目主规范：`./AGENTS.md`
> - 项目记忆：`c:\Users\shiyq\.trae-cn\memory\projects\-f-myself-github-WeAgentChat-temp-legado\project_memory.md`
> - 经验索引：basic-memory（project=legado）

---

## 🔴🔴🔴 强制规则：书源/订阅源自测交付

> **任何新生成或优化的书源/订阅源，必须经过自测通过后才能视为任务完成。禁止未经自测直接交付！**

| 规则 | 说明 |
|------|------|
| **源码验证优先** | 每一步规则编写必须先去 Legado 源码核实验证，不能凭经验臆测 |
| **自测不通过=未完成** | 任务状态不得标记为 completed，直到自测全部通过 |
| **经验必须源码验证** | 写入 skill 参考文档的经验教训，必须经过源码深度分析核实 |

> **完整规范（5阶段闭环+79条陷阱+JVM仿真器）**：[SKILL.md](./.trae/skills/legado-source-creator/SKILL.md)

---

## 代码约束（摘要）

### Code Style 核心

- ✅ 协程用自定义 `Coroutine.async{}...onError{}.onSuccess{}` 链式封装（非标准 launch+try/catch）
- ✅ 异步双版本：`xxx()` 返回 `Coroutine<T>` + `xxxAwait()` 挂起函数
- ✅ 核心业务用 `object` 单例（`ReadBook`, `WebBook`, `AppConfig`），不引入 DI 框架
- ✅ Room 实体：`data class` + `@Parcelize` + `@Entity`，字段全部有默认值
- ✅ 错误处理用 `kotlin.runCatching`（带 `kotlin.` 前缀），字符串判空用 `isNullOrBlank()`
- ❌ 不要使用 Timber / `CoroutineExceptionHandler`，日志用 `AppLog.put()`，异常用 `Coroutine.onError`

> **完整规范**：[naming_rules.md](./docs/project-rules/naming_rules.md) | [checkstyle_rules.md](./docs/project-rules/checkstyle_rules.md)

### Landmines 核心

- **jsoup 1.16.2 锁定**：破坏性变更 jsoup#2017，不可升级
- **rhino 1.8.1 锁定**：API 24 以下缺少 Arrays.setAll，不可升级（minSdk 已提升至 23 但仍低于 24）
- **hutool 5.8.22 锁定**：书源加解密依赖，不可升级
- **ReadBook 全局单例**：多 Activity 共享，改状态需 `@Synchronized` 或 `Mutex` 保护
- **Vue3 构建**：vite build 后 sync.js 仅在 GitHub Actions 执行，本地需手动复制
- **NoStackTraceException**：所有业务异常继承此类，覆写 `fillInStackTrace()`

> **完整陷阱**：[exception_rules.md](./docs/project-rules/exception_rules.md) | [logging_rules.md](./docs/project-rules/logging_rules.md) | [architecture_rules.md](./docs/project-rules/architecture_rules.md)

### Git 仓库管理

- **远程仓库**：`https://github.com/syq17496152/legado.git`（私有）
- **主分支**：`master`
- **.gitignore 核心排除**：`temp/`（Android SDK/缓存）、`output/`（测试输出）、Skill 运行时产物、`*.log`
- **Commit 规范**：Conventional Commits（`feat:` / `fix:` / `docs:` / `refactor:` / `skill:` 等）

> **完整规范**：[git-repo-management.md](./docs/project-flow/git-repo-management.md)

---

## 低频子规范引用（按需加载）

> **以下子规范仅在特定任务场景下必须加载，非每次对话都需要。AI 遇到对应触发场景时必须先 Read 子规范再执行任务。**

| 子规范 | 触发场景（必须加载） |
|--------|---------------------|
| [全局思考检查清单](./docs/project-rules/global-thinking-checklist.md) | 改动功能前的强制门禁（前端入口+后端接口+数据库+覆盖安装+使用场景+回填点6维度盘点） |
| [错误沉淀机制](./docs/project-rules/spec-sedimentation-mechanism.md) | 错误发生后的沉淀流程（MaterialButton/校验路径/字段回填/双TitleBar/复杂需求3次验证5条规则） |
| [数据库升级安全规范](./docs/project-rules/database-migration-safety.md) | 数据库 version 变更/@DatabaseView 修改/实体字段修改/新增 migration 任务 |
| [真机测试流程复用规范](./docs/project-rules/real-device-test-reuse.md) | 代码变更任务完成后的真机验证（可用脚本清单+测试流程模板+问题闭环） |
| [延伸版本参考与对比方法论](./docs/project-rules/forks-reference.md) | 网络层/前端/协程/WebView/数据管理组件优化或功能借鉴任务 |
| [工作方法论](./docs/project-rules/work-methodology.md) | 大型任务（10+文件/多Issue）开始时/新对话开始时检查工作方法 |
| [AI 自动端到端测试](./docs/project-rules/ai_e2e_testing_workflow.md) | 代码变更后 OpenSpec 步骤 5.5；测试前必读 SOP：[fixed_test_workflow.md](./ai_tests/docs/fixed_test_workflow.md) |
| [书源/订阅源自测交付](./.trae/skills/legado-source-creator/SKILL.md) | 新生成/优化书源或订阅源任务（79条陷阱+5阶段闭环+JVM仿真器） |
| [改造过程日志记录](./docs/project-rules/logging-during-refactoring.md) | 代码优化/改造/Bug修复实施过程（10类场景必加日志） |
| [包名规范](./docs/project-rules/package-naming.md) | 构建APK/包名配置/与原版共存 |

---

## 快速入口

| 用途 | 文档 |
|------|------|
| **所有文档统一索引** | [docs/INDEX.md](./docs/INDEX.md) |
| **任务导航（14模块代码锚点）** | [docs/project-flow/task-navigation.md](./docs/project-flow/task-navigation.md) |
| **构建/运行/测试命令** | [docs/project-flow/quick-reference.md](./docs/project-flow/quick-reference.md) |
| **项目规范目录** | [docs/project-rules/](./docs/project-rules/) |
| **Git 仓库管理** | [docs/project-flow/git-repo-management.md](./docs/project-flow/git-repo-management.md) |
| **规则引擎详解** | [docs/project-flow/architecture/rule-engine.md](./docs/project-flow/architecture/rule-engine.md) |
| **功能设计文档** | [docs/specs/](./docs/specs/) |
| **AI 自动化测试** | [ai_tests/README.md](./ai_tests/README.md) |
