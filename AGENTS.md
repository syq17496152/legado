# Legado（阅读Sigma）

> Android 开源电子书阅读器，核心为自定义书源规则引擎（CSS/JSONPath/XPath/正则/JS 五种解析），用户编写规则即可将任意网页转化为结构化书籍资源。

## 项目来源

本项目 fork 自原版 [legado-E](https://github.com/Luoyacheng/legado-E)，在此基础上建立了私有化仓库（`https://github.com/syq17496152/legado.git`），并进行了私有化改造。遇到与原版行为不一致的问题时，应优先对比原版代码定位回归原因。

## 延伸版本参考（开源阅读生态）

> **AI 在进行网络层/前端/协程/WebView 等组件优化时，必须主动对比以下延伸版本的实现，学习借鉴优点，不闭门造车。**
> 来源：[阅读·全版本集散地](https://momo-b5a.pages.dev/%E4%B8%8B%E8%BD%BD/xz)（27+ 版本）

### 主线分支（基于原版，网络层与原版基本一致）

| 版本 | git 仓库 | 特色 | 对比优先级 |
|------|----------|------|-----------|
| 原版阅读 | [gedoor/legado](https://github.com/gedoor/legado) | 原版，所有 fork 的源头 | ⭐⭐⭐⭐⭐ |
| 阅读Sigma | [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) | 本项目 fork 源 | ⭐⭐⭐⭐⭐ |
| 喵公子阅读 | [LegadoTeam/legado](https://github.com/LegadoTeam/legado) | 主流分支，活跃度高 | ⭐⭐⭐⭐ |
| 阅读T | [skybbk1001/legadoT](https://github.com/skybbk1001/legadoT) | 主流分支 | ⭐⭐⭐ |
| 阅读Archive | [Rimchars/legado](https://github.com/Rimchars/legado) | 主流分支 | ⭐⭐⭐ |
| 阅读R | [refgd/legado](https://github.com/refgd/legado) | 主流分支 | ⭐⭐ |
| Jingshiro阅读 | [Jingshiro/legado](https://github.com/Jingshiro/legado) | 主流分支 | ⭐⭐ |

### Max 系列（蛋蛋Max 衍生，网络层有 307/308 重定向等优化）

| 版本 | git 仓库 | 特色 | 对比优先级 |
|------|----------|------|-----------|
| 蛋蛋阅读·Max | [DandanLLab/Legado_Max](https://github.com/DandanLLab/Legado_Max) | Max 系列源头，307/308 重定向优化 | ⭐⭐⭐⭐⭐ |
| 怣疯阅读·Max | [youfengknight/Legado_Max](https://github.com/youfengknight/Legado_Max) | 蛋蛋Max 衍生 | ⭐⭐ |
| Suml-1阅读·Max | [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) | 蛋蛋Max 衍生 | ⭐⭐ |

### 独立分支（前端/MD3/跨平台改造）

| 版本 | git 仓库 | 特色 | 对比优先级 |
|------|----------|------|-----------|
| 阅读NG | [joestar817/legado_NG](https://github.com/joestar817/legado_NG) | 网络日志标签等优化 | ⭐⭐⭐⭐ |
| 辞晨阅读·Max | [GEd520/legados](https://github.com/GEd520/legados) | 辞晨系列 | ⭐⭐⭐ |
| MD3阅读 | [HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) | Material3 前端改造 | ⭐⭐⭐⭐（前端） |
| MD3阅读-DIY | [325506/legado-with-MD3-DIY](https://github.com/325506/legado-with-MD3-DIY) | MD3 衍生 | ⭐⭐⭐（前端） |
| 喵公子鸿蒙 | [mgz0227/legado-Harmony](https://github.com/mgz0227/legado-Harmony) | 鸿蒙适配 | ⭐⭐ |
| Legado-Tauri | [LegadoTeam/Legado-Tauri-Release](https://github.com/LegadoTeam/Legado-Tauri-Release) | Tauri 桌面端 | ⭐⭐ |

### 独立项目（非 Legado fork，可参考架构）

| 版本 | git 仓库 | 特色 | 对比优先级 |
|------|----------|------|-----------|
| MoRealm | [keys-cherish/morealm-reader](https://github.com/keys-cherish/morealm-reader) | 独立阅读器 | ⭐⭐ |
| 书享阅读 | [zyl140640/readbook-releases](https://github.com/zyl140640/readbook-releases) | 独立阅读器 | ⭐⭐ |
| 轻悦时光 | [autobcb/qysg](https://github.com/autobcb/qysg) | 独立阅读器 | ⭐⭐ |
| IReader | [IReaderorg/IReader](https://github.com/IReaderorg/IReader) | 独立阅读器 | ⭐⭐ |
| LightNovelReader | [dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) | 轻小说专用 | ⭐⭐ |

### 对比方法论（强制规范）

> **任何网络层/前端/协程/WebView/数据管理组件优化或功能借鉴任务，必须遵循** [延伸版本对比方法论规范](./docs/project-rules/forks_comparison_methodology.md) **执行对比分析，禁止闭门造车。**

#### 对比优先级矩阵

| 优化领域 | 优先对比版本 | 原因 |
|----------|------------|------|
| **网络层** | 蛋蛋Max > 阅读T > 阅读NG | 蛋蛋Max 有 307/308 重定向；阅读T 有 SOCKS5 隧道+Brotli；阅读NG 有网络日志 |
| **协程/多线程** | 蛋蛋Max > 阅读NG > 阅读Archive | 蛋蛋Max 修复了 CancellationException 反模式 |
| **WebView** | 阅读Archive > 蛋蛋Max > 阅读NG | 阅读Archive 有 closed 标志 + isActiveWebView 修复范式 |
| **前端** | 蛋蛋Max > MD3阅读 | 仅蛋蛋Max 有前端实质增量（备份功能） |
| **数据管理** | 蛋蛋Max > 阅读Archive | 蛋蛋Max 有 Web 端备份功能 |

#### 五阶段对比流程

```
Phase 1: 准备阶段 → Phase 2: 分类对比 → Phase 3: 差异识别 → Phase 4: 价值评估 → Phase 5: 借鉴决策
(预检+浅克隆)     (按组件维度)     (逐文件对比)     (收益/风险评分)   (输出决策表)
```

#### 关键踩坑警示

- ⚠️ **GitHub git trees API 有缓存错误**：所有结论以 `git clone --depth 1` 实测为准
- ⚠️ **仓库 404 不等于不存在**：可能是改名/私有/删除，需在 [阅读·全版本集散地](https://momo-b5a.pages.dev/%E4%B8%8B%E8%BD%BD/xz) 查新地址
- ⚠️ **前端源码在 `modules/web/`**：不是 `app/src/main/assets/web/`（后者是构建产物）
- ⚠️ **PowerShell curl 别名冲突**：使用 `curl.exe` 或 `Invoke-WebRequest`

> **完整方法论、对比清单、决策矩阵、踩坑案例**：[docs/project-rules/forks_comparison_methodology.md](./docs/project-rules/forks_comparison_methodology.md)

---

## 🔴🔴🔴 子规范强制加载硬约束（V2.1，解决"缩减后AI不关注子规范"问题）

> **历史教训**：以前尝试过缩减主规范，缩减后 AI 反而不去关注放在子规范文件中的规范。
> **铁证（2026-07-13）**：spec-system-optimization 实施后上下文压缩，AI 执行 OpenSpec 任务时未加载 `openspec-workflow.md` 子规范，仅根据记忆执行，导致子规范强制加载机制未生效。

**硬性约束**：

1. **每个强制规则的"何时必须加载本子规范"触发场景是硬性约束**，不是建议。AI 在触发场景出现时，**必须用 Read 工具实际加载**对应子规范文件，禁止仅根据记忆执行。
2. **加载验证机制**：AI 加载子规范后，必须在输出中引用子规范的具体条款（如"根据 openspec-workflow.md §X.Y"），证明已实际加载。
3. **禁止"只移不引"**：移到子规范的内容，主规范必须有明确的"详见 [子规范]"引用。
4. **上下文压缩恢复后必须重新加载**：压缩恢复后，AI 不仅依赖记忆，必须根据当前任务类型重新用 Read 工具加载相关子规范。

**按任务类型必须加载的子规范**：

| 任务类型 | 必须加载的子规范 | 触发条件 |
|---------|----------------|---------|
| OpenSpec 任务 | openspec-workflow.md | 新功能/优化/Bug修复/重构 |
| 代码变更任务 | logging-during-refactoring.md + version-delivery-sync.md | 代码优化/改造/Bug修复 |
| 复杂任务（50+文件） | complex-task-pipeline.md | 50+源文件分析/多文档验证 |
| 子代理任务 | sub-agent-quality-management.md | 使用 Agent 工具时/规避思考上限 |
| E2E 测试任务 | ai_e2e_testing_workflow.md | 代码变更后步骤5.5 |
| 书源/订阅源任务 | SKILL.md | 书源/订阅源/RSS源/阅读Legado |

---

## 🔴 强制规则：复杂任务处理流程

> **当任务涉及 50+ 源文件分析、多份文档验证/修复、或任何单次上下文无法容纳的复杂任务时，必须严格遵循以下流程。禁止跳过任何阶段。**

### 硬性约束

| 约束 | 值 | 说明 |
|------|-----|------|
| **单子代理上限** | ≤ 12 个源文件 | 超过即拆分，禁止合并 |
| **低风险触发阈值** | ≥ 5 文件或 ≥ 10 工具调用 | 文档/分析任务强制子代理（详见 sub-agent-quality-management.md） |
| **单临时文档上限** | ≤ 1000 行 | 超限说明分组过大 |
| **启动方式** | 同批次全部并行 | 禁止串行逐个启动 |
| **结果验证** | 必须交叉验证 | 禁止信任单一来源 |

### 五阶段流水线

```
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5
扫描分组   并行分析   交叉验证   精准修复   导航同步
```

| 阶段 | 动作 | 产出 |
|------|------|------|
| **Phase 1** | 3 个搜索子代理并行扫描，按 8-12 文件/组划分 | 文件分组清单 |
| **Phase 2** | N 个分析子代理并行分析，生成临时文档到 `docs/temp-analysis/` | 临时分析文档 |
| **Phase 3** | M 个验证子代理交叉对比临时文档 vs 现有文档 | ERROR/WARN/INFO 报告 |
| **Phase 4** | K 个修复子代理基于验证报告精准修复 | 修复后的文档 |
| **Phase 5** | 同步 AGENTS.md / overview.md / README.md 统计数字和索引 | 更新后的导航 |

### 反模式

❌ 子代理塞 30+ 文件 / 串行启动 / 信任单份文档 / 只看报告不看源码 / 只管后端不管前端 / 修完不更新导航
> **完整方法论**：[multi-agent-analysis-spec.md](./docs/project-flow/architecture/multi-agent-analysis-spec.md)

---

## 🔴🔴 输出与工具预算管理（规避思考上限，V1 实验性）

> **GLM-5.2 在 Trae 平台有"思考次数上限"（工具调用轮次+思考 token+上下文累积）。频繁触发导致任务中断，新对话收费增加成本。本规则通过子代理编排和预算管理，在同对话内扩展工作量。**
> **何时必须加载本子规范**：使用 Agent 工具时/执行 OpenSpec 流程时/任务涉及多文件分析时。
> **完整规范**：[sub-agent-quality-management.md](./docs/project-rules/sub-agent-quality-management.md)

### 分级子代理策略

| 风险等级 | 任务类型 | 策略 |
|---------|---------|------|
| 🟢 低风险 | 文档生成/代码分析/大文件读取/OpenSpec 步骤2 | ✅ 强制子代理 |
| 🟡 中风险 | 实施阶段分析/多文件探索/OpenSpec 步骤5 分析 | 🟡 推荐子代理 |
| 🔴 高风险 | 架构决策/源码修改/用户交互/需要用户反馈的深度分析 | ❌ 禁止子代理，主代理直接执行 |

### 单次回复输出预算

- 单次回复正文 ≤ 100 行，超出部分用 Write 写文件
- 长文档（四文档/分析报告）必须用 Write 写文件，正文只给摘要
- 工具调用结果不回显，直接基于结果给结论

### 工具调用预算

- 单次对话工具调用 ≥ 30 次时，主动 /compact 或用子代理分担后续工作
- 批量并行工具调用优先（一次调用多个独立工具）
- 避免重复读取同一文件，已读内容缓存到 memory

### 禁止建议新对话

- **禁止**以"避免触发思考上限"为由建议用户新开对话（新对话收费）
- 触发上限时优先输入"继续"续接
- "继续"后仍频繁触发时，用子代理分担后续工作
- 仅当上下文窗口接近满（>90%）且 /compact 无效时，才向用户说明情况

---

## 🔴🔴 强制规则：AI 自动端到端测试（V3）

> **任何代码变更任务，在 OpenSpec 步骤 5（实施）与步骤 6（检查点 2）之间，必须执行步骤 5.5 AI 自动端到端测试。禁止跳过！**

### 子规范引用

| 子规范 | 路径 | 说明 |
|--------|------|------|
| **S13** | [ai_e2e_testing_workflow.md](./docs/project-rules/ai_e2e_testing_workflow.md) | 5.5.1~5.5.8 八步强制流程 |
| **S14** | [test-case-design-guide.md](./docs/project-rules/test-case-design-guide.md) | 双轨制 + 源码溯源字段 + 步骤语义化 |

### 八步强制流程

```
5.5.1 源码影响分析（run_e2e.py --diff HEAD~1）→ affected_modules.json
5.5.2 APK 自动发现 + MEmu 启动
5.5.3 双轨用例调度（同 TC-ID Python 优先）
5.5.4 8 类证据收集
5.5.5 规则判定（pass/fail/manual/warning）
5.5.6 manual 用例 AI agent 介入（生成 ai-prompt.md + 回填 ai_verdict）
5.5.7 五件套报告生成（report.md/json + manual_cases + affected + feedback）
5.5.8 反馈闭环触发（run_e2e.py --feedback）→ 沉淀规则/陷阱/提示词
```

### 🔴 固化层保护规则（V3）

`ai_tests/lib/` 下 9 个模块文件（M1-M9）为**固化层**，AI 不应直接修改，必须通过 OpenSpec 流程：

| 模块 | 文件 | 职责 |
|------|------|------|
| M1 | memu_controller.py | 模拟器控制 |
| M2 | apk_deployer.py | APK 部署 |
| M3 | case_parser.py | 用例解析（双轨+源码溯源） |
| M4 | ui_executor.py | UI 执行器 |
| M5 | evidence_collector.py | 8 类证据收集 |
| M6 | rule_analyzer.py | 规则判定 |
| M7 | report_generator.py | 五件套报告 |
| M8 | source_impact_analyzer.py | V3 源码影响分析 |
| M9 | source_test_generator.py | V3 B 轨测试生成 |

`config.py`（固化层）含 CRASH_PATTERNS/DB_QUERIES 等常量，扩展需 OpenSpec 流程。

### 🔴🔴 快速验证脚本层（V3.1，2026-07-11 新增）

> **用户批评（2026-07-11）**："你的测试流程为什么老是来来回回的变动呢？难道就没有一些经验或者是固定流程的脚本可以沉淀到ai_test目录下么？！！！"
>
> **用户再次批评（2026-07-11）**："你还要反思为什么有了 ai_test 你为啥不去使用，需不需要在项目规范文件中加强说明"

**根因反思**：`run_e2e.py` 面向"用例驱动全量测试"，需要完整用例解析+8类证据收集，流程太重不适合"快速L2验证某个功能"。`lib/` 模块是底层组件没有组合成快速验证脚本。导致 AI 每次在 `temp/` 目录从头创建临时脚本，用完就丢，下次又从头写。

**解决方案**：`ai_tests/scripts/` 目录下4个固定脚本，覆盖完整测试流水线。

| 脚本 | 步骤 | 用法 |
|------|------|------|
| [quick_build_install.py](./ai_tests/scripts/quick_build_install.py) | 1.编译+安装+L1 | `python ai_tests/scripts/quick_build_install.py` |
| [import_rss_source.py](./ai_tests/scripts/import_rss_source.py) | 2.导入订阅源 | `python ai_tests/scripts/import_rss_source.py <json_path>` |
| [l2_verify_video_player.py](./ai_tests/scripts/l2_verify_video_player.py) | 3.L2验证视频播放器 | `python ai_tests/scripts/l2_verify_video_player.py [--scenario SCENARIO] [--manual]` |
| [swipe_test_log.py](./ai_tests/scripts/swipe_test_log.py) | 4.SwipeTest日志分析 | `python ai_tests/scripts/swipe_test_log.py [clear\|capture\|analyze]` |

**SOP 文档**：[ai_tests/docs/fixed_test_workflow.md](./ai_tests/docs/fixed_test_workflow.md) — 测试前必读！

### 🔴🔴🔴 ai_tests 使用强制规则（V3.1，2026-07-11 新增）

| 规则 | 说明 |
|------|------|
| **测试必须用 ai_tests/scripts/** | 所有测试操作（编译/安装/L1/L2/日志分析）必须使用 `ai_tests/scripts/` 下的固定脚本 |
| **禁止 temp/ 临时脚本** | ❌ 禁止在 `temp/` 目录创建任何测试脚本，违反即返工 |
| **测试前必读 SOP** | 测试前必须先读取 `ai_tests/docs/fixed_test_workflow.md` |
| **扩展不新建** | 新增测试场景时，扩展现有脚本（添加 `--scenario` 参数），禁止创建新脚本 |
| **复用 config.py 常量** | 脚本必须 import config 常量（ADB_PATH/MEMU_ADB_HOST/PACKAGE 等），禁止硬编码路径 |
| **venv Python** | 必须使用 `ai_tests\venv\Scripts\python.exe`，禁止公共 Python |
| **全量测试用 run_e2e.py** | 需要全量用例测试时用 `run_e2e.py --tc all`，快速L2验证用 `scripts/` 下脚本 |

> **反模式（跳过5.5/不执行源码影响分析/不读取manual_cases/不触发反馈闭环/不按S14设计用例/改lib固化层不通过OpenSpec/temp/创建临时脚本/不读SOP）**：详见 [ai_e2e_testing_workflow.md](./docs/project-rules/ai_e2e_testing_workflow.md)
>
> **何时必须加载本子规范**：任何代码变更任务在 OpenSpec 步骤 5（实施）与步骤 6（检查点2）之间，必须执行步骤 5.5 AI 自动端到端测试。测试前必读 SOP：[ai_tests/docs/fixed_test_workflow.md](./ai_tests/docs/fixed_test_workflow.md)

---

## 🔴🔴🔴 强制规则：书源/订阅源自测交付流程

> **任何新生成或优化的书源/订阅源，必须经过自测通过后才能视为任务完成。禁止未经自测直接交付！**

### 核心要求

- **源码验证优先**：每一步规则编写必须先去 Legado 源码核实验证，不能凭经验臆测
- **自测不通过=未完成**：任务状态不得标记为 completed，直到自测全部通过
- **经验必须源码验证**：写入 skill 参考文档的经验教训，必须经过源码深度分析核实

### 五阶段闭环工作流

```
Phase 1: 经验优先 → Phase 2: 构建规则 → Phase 3: 测试驱动 → Phase 4: 源码深挖 → Phase 5: 经验反哺
```

> **何时必须加载本子规范**：新生成/优化书源或订阅源任务。
> **自测三阶段流水线 + 79 条 Rhino 陷阱清单 + 验证脚本**：详见 [SKILL.md](./.trae/skills/legado-source-creator/SKILL.md)

---

## 🔴🔴 强制规则：OpenSpec 工作流程

> **任何新增功能、优化功能、Bug 修复、重构任务，必须先生成 OpenSpec 文档并经用户审核通过后，才能开始实施代码。禁止未经设计审核直接编码！**

### 强制触发条件

所有场景一律生成四文档（README.md + spec.md + design.md + tasks.md），不做级别区分：

| 文档 | 核心内容 |
|------|---------|
| **README.md** | 功能概述、核心能力、文档索引、状态标记 |
| **spec.md** | Intent/Scope/Approach（含 Alternatives Considered + Drawbacks）/Requirements/Scenarios |
| **design.md** | Technical Approach/Architecture Decisions（ADR Y-Statement 模板）/Data Flow/File Changes |
| **tasks.md** | `- [ ] X.Y` 格式任务清单 + AOAdapt 日志（遇问题时必须记录） |

文档位置：`docs/specs/{功能名称}/`

### 工作流程（8 步 + 3 检查点）

```
步骤1: 用户提出需求 → 步骤2: 需求分析 → 步骤3: 生成四文档(🔄设计中)
→ 🛑检查点1: 用户审查设计 → 步骤5: 按tasks.md实施(🔄开发中)
→ 🛑检查点2: 用户审核实施 → 🛑检查点3: 用户最终验收
→ 步骤8: 文档同步(更新docs/project-flow/)
```

### 子代理使用指导（V1 实验性）

> **为规避思考上限，OpenSpec 流程的以下步骤应使用子代理（详见 [sub-agent-quality-management.md](./docs/project-rules/sub-agent-quality-management.md)）：**

| 步骤 | 子代理策略 | 说明 |
|------|-----------|------|
| **步骤2（生成四文档）** | ✅ 强制子代理 | 子代理并行生成四文档，主代理验证关键章节 |
| **步骤5（实施）分析阶段** | 🟡 推荐子代理 | 子代理分析相关代码，主代理基于结果串行修改源码 |
| **步骤5（实施）源码修改** | ❌ 主代理串行 | 遵守并发文件修改规范 |
| **检查点1/2/3** | ❌ 主代理直接 | AskUserQuestion 由主代理发起 |

### 检查点交互规范（强制）

> **OpenSpec 三个检查点（1/2/3）必须使用 AskUserQuestion 工具与用户交互，禁止依赖 ExitPlanMode 的二元确认。**

#### 三选项强制结构

每个检查点的 AskUserQuestion 必须提供以下三个选项，缺一不可：

| 选项 | 含义 | 后续动作 |
|------|------|---------|
| **通过（继续下一阶段）** | 用户认可当前阶段产出 | 进入下一阶段，更新 TaskList 状态 |
| **需调整** | 用户对部分内容有意见 | 用户通过 Other 输入具体意见，AI 据此修订后重新发起检查点确认 |
| **拒绝（回退上一阶段）** | 用户不认可整体方向 | 回退到上一阶段重新分析/实施，更新 TaskList 状态 |

#### Plan 模式 ExitPlanMode 前置确认

Plan 模式下调用 ExitPlanMode 前，必须先通过 AskUserQuestion 获取用户明确确认：
- 用户选"通过" → ExitPlanMode（执行）
- 用户选"需调整" → 据意见修订 → 重新 AskUserQuestion
- 用户选"拒绝回退" → 回退上一阶段

> **何时必须加载本子规范**：任何新增功能/优化功能/Bug修复/重构任务开始前。
> **完整工作流程（检查点交互示例+反模式+文档状态流转+检查清单）**：[openspec-workflow.md](./docs/project-rules/openspec-workflow.md)

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

## 🔴🔴 强制规则：版本交付同步

> **任何涉及代码变更的任务完成后，必须同步更新 `assets/updateLog.md`。禁止只改代码不写更新日志！**

### 同步清单

| 变更类型 | 必须同步的文件 | 说明 |
|----------|--------------|------|
| **任何代码变更** | `app/src/main/assets/updateLog.md` | 顶部追加日期条目，写明用户可感知的变更内容 |
| **文档变更** | `docs/INDEX.md` | 更新 spec 状态标记 |
| **架构变更** | `docs/project-flow/` 相关文档 | 同步架构说明 |
| **Skill 变更** | `.trae/skills/` 相关 SKILL.md | 同步能力说明 |

### updateLog.md 格式（摘要）

```markdown
**YYYY/MM/DD**
- 变更说明1（面向用户，非技术细节）
```

> 条目追加在 `## cronet版本:` 行之后。内容面向用户，用通俗语言描述可感知的变化。
> **更新时机**：编译前更新，不是交付阶段！

> **何时必须加载本子规范**：任何代码变更任务完成后（编译前必须更新）。
> **反模式（改代码不写updateLog/只写"优化代码"无具体内容/新功能用户不知道/tasks.md完成但updateLog未更新/交付阶段才补写）+ 完整格式规范**：[version-delivery-sync.md](./docs/project-rules/version-delivery-sync.md)

---

## 🔴🔴🔴 强制规则：改造过程日志记录

> **任何代码优化、改造、Bug 修复任务，在实施过程中必须适度添加日志，帮助定位分析问题、发现隐藏问题、避免遗漏问题。禁止改造代码不加日志！**

### 核心要求

| 要求 | 说明 |
|------|------|
| **改造必加日志** | 任何代码改造（优化/Bug修复/新功能）必须在关键路径添加日志 |
| **永久+临时双轨** | 永久日志（错误处理/状态切换）用 `AppLog.put` 保留；临时日志（调试验证）用 `Log.d` 验证后移除 |
| **日志覆盖错误分支** | 所有 `catch` 块和错误码处理必须有日志，禁止异常被静默吞掉 |
| **日志内容安全** | 禁止输出完整 URL/视频域名/敏感字段，只保留路径模式（详见输出安全规范） |

### 必须加日志的 10 类场景

1. **播放器状态切换**（降级/播放/暂停/释放）
2. **错误处理路径**（所有 catch 块和错误码）
3. **网络请求关键节点**（发起/成功/失败/重试）
4. **数据库异常**（查询失败/记录跳过/超大记录）
5. **类型转换**（String→List/JSON解析）
6. **加密解密**（解密失败/数据长度异常）
7. **生命周期关键节点**（Fragment/WebView/Player 创建/销毁）
8. **配置变更**（播放器类型切换/设置变更）
9. **触摸事件流转**（事件分发/消费/穿透）
10. **JS 交互**（JS调用Native/Native调用JS）

### 临时日志使用流程（摘要）

```
添加 Log.d（统一Tag）→ 编译安装操作 → logcat抓取分析 → 发现问题修复 → 验证通过Grep移除 → 重新编译确认
```

> **何时必须加载本子规范**：代码优化/改造/Bug修复实施过程（必加日志）。
> **完整规范（10类场景详细说明+日志级别+Tag规范+临时/永久双轨+反模式+验证检查清单）**：[logging-during-refactoring.md](./docs/project-rules/logging-during-refactoring.md)

---

## 项目核心 Skill：legado-source-creator

> **本项目核心工具**：Legado 书源/订阅源智能创建器。79 条陷阱检查、5 阶段闭环工作流、10 大参考目录、16 个验证脚本、JVM 仿真器（legado-jvm.jar，覆盖率 85-90%）。

**触发条件**：用户提到「书源」「订阅源/RSS源」「阅读/Legado」「网站→JSON」「修复/优化源」时，必须使用本 skill。

### 5 阶段闭环工作流（流程骨架）

```
Phase 1: 经验优先 → Phase 2: 构建规则 → Phase 3: 测试驱动 → Phase 4: 源码深挖 → Phase 5: 经验反哺+代码进化
(先查skill文档)    (按规则写源)      (JVM/Python验证)    (失败时深入源码)    (新经验写入skill+JVM/Python代码更新)
```

### 🔴🔴🔴 强制规则：Phase 完成标志与审计

> **使用 legado-source-creator Skill 时，必须遵守以下规则。禁止跳过任何步骤。**

1. **Phase 完成标志**：每个 Phase 完成后必须输出 `[PHASEX_COMPLETE]` 标志
   - Phase 1: `[PHASE1_COMPLETE] basic-memory搜索:命中/未命中/降级, 陷阱检查:已检查/未检查`
   - Phase 3: `[PHASE3_COMPLETE] 测试覆盖率:X%, 高可信:N, 中可信:N, 需真机:N`
   - Phase 5: `[PHASE5_COMPLETE] 双写:完成/部分完成/失败, Schema验证:通过/未通过`
2. **Phase 切换约束**：未输出 `[PHASEX_COMPLETE]` 标志，禁止进入下一 Phase
3. **任务后审计**：书源/订阅源任务完成后，必须调用 `legado-workflow-auditor` Skill 审计
4. **basic-memory 执行证据**：Phase 1/3/5 完成后必须将执行证据写入 basic-memory (project=legado)

### Skill 三件套协作（路由概要）

本项目三个 Skill 形成"审查 skill → 创建源 → 审计执行"闭环：

| Skill | 路径 | 核心能力 |
|-------|------|---------|
| **legado-source-creator** | [.trae/skills/legado-source-creator/SKILL.md](./.trae/skills/legado-source-creator/SKILL.md) | 79 条陷阱检查、5 阶段闭环工作流、10 大参考目录、JVM 仿真器 |
| **legado-workflow-auditor** | [.trae/skills/legado-workflow-auditor/SKILL.md](./.trae/skills/legado-workflow-auditor/SKILL.md) | 8 项执行证据检查、审计报告输出 |
| **legado-skill-auditor** | [.trae/skills/legado-skill-auditor/SKILL.md](./.trae/skills/legado-skill-auditor/SKILL.md) | 8 维度 42 检查点（L1/L2/L3 三层）、精准修复+回归验证 |

**冲突词优先级**：
- **"审计"**：单独使用 → workflow-auditor；带"skill" → skill-auditor
- **"优化"**：带"源" → source-creator；带"skill" → skill-auditor
- **"审查"**：带"skill"或"全面/深度"修饰 → skill-auditor；其他根据语境判断

> **何时必须加载本子规范**：用户提到书源/订阅源/RSS源/阅读Legado/网站→JSON/修复优化源/审计/审查skill等触发词时。
> **完整 Skill 文档（10大参考目录+网络获取地址+验证脚本+经验引擎+触发词表+上下文传递规范+mermaid调用链路图）**：[SKILL.md](./.trae/skills/legado-source-creator/SKILL.md) | [AI_README.md](./.trae/skills/legado-source-creator/AI_README.md)

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

### 并发文件修改规范（全局通用）

> **多 Agent 并行操作时，必须遵循以下规则，防止文件内容被并发覆盖丢失。**
> 踩坑案例：文档同步阶段，多个后台 Agent 并行修改文档时，与源码文件产生时序竞态，导致已添加的代码定义被覆盖丢失，构建失败。

| 规则 | 说明 |
|------|------|
| **源码文件修改串行化** | 同一源码文件的所有 Edit 必须由主 Agent 串行执行，**禁止委托给后台 Agent**触碰同一源码文件 |
| **文档与代码隔离** | 文档同步 Agent 只能修改文档目录，**禁止读取+回写**源码文件（验证时只读不写） |
| **关键节点构建复验** | 每个阶段结束后必须重新执行项目构建验证命令，而非只在最后构建一次；文档同步后也要复验源码完整性 |
| **git diff 校验** | 重要文件修改后用 `git diff` 确认变更范围符合预期，发现异常回退立即排查 |
| **后台 Agent 职责单一** | 后台 Agent 只负责独立的分析/文档任务，**禁止**在后台 Agent 中执行源码文件 Edit |
| **修改前备份上下文** | 对核心配置文件/常量文件执行 Edit 前，先 Read 确认当前内容；多轮修改后再次 Read 防止中间状态丢失 |

### Git 仓库管理

- **远程仓库**：`https://github.com/syq17496152/legado.git`（私有）
- **主分支**：`master`
- **.gitignore 核心排除**：`temp/`（Android SDK/缓存）、`output/`（测试输出）、Skill 运行时产物、`*.log`
- **Commit 规范**：Conventional Commits（`feat:` / `fix:` / `docs:` / `refactor:` / `skill:` 等）

> **完整规范**：[git-repo-management.md](./docs/project-flow/git-repo-management.md)

---

## 快速入口

| 用途 | 文档 |
|------|------|
| **所有文档统一索引** | [docs/INDEX.md](./docs/INDEX.md) |
| **任务导航（14模块代码锚点）** | [docs/project-flow/task-navigation.md](./docs/project-flow/task-navigation.md) |
| **构建/运行/测试命令** | [docs/project-flow/quick-reference.md](./docs/project-flow/quick-reference.md) |
| **项目规范（9个规范文档）** | [docs/project-rules/](./docs/project-rules/)（含 [延伸版本对比方法论](./docs/project-rules/forks_comparison_methodology.md) + [改造过程日志记录规范](./docs/project-rules/logging-during-refactoring.md)） |
| **Git 仓库管理** | [docs/project-flow/git-repo-management.md](./docs/project-flow/git-repo-management.md) |
| **规则引擎详解** | [docs/project-flow/architecture/rule-engine.md](./docs/project-flow/architecture/rule-engine.md) |
| **Skill 参考文档索引** | [.trae/skills/legado-source-creator/references/_INDEX.md](./.trae/skills/legado-source-creator/references/_INDEX.md) |
| **功能设计文档** | [docs/specs/](./docs/specs/) |
| **AI 自动化测试基础设施** | [ai_tests/README.md](./ai_tests/README.md)（E2E 测试编排器 + 8 类证据 + 规则判定 + 七件套报告） |
| **E2E 测试设计文档** | [docs/specs/e2e-automated-testing/](./docs/specs/e2e-automated-testing/)（V3 四文档） |
| **书源网络获取** | `https://www.yckceo.com/yuedu/shuyuans/index.html` |
| **订阅源网络获取** | `https://www.yckceo.com/yuedu/rsss/index.html` |
