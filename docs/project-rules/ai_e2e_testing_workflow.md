# 子规范 S13：AI 自动端到端测试工作流

> **定位**：定义 OpenSpec 工作流中步骤 5.5（AI 自动端到端测试）的强制流程。
> **引用关系**：本规范被 [AGENTS.md](../../AGENTS.md) "🔴🔴 强制规则：AI 自动端到端测试" 引用。
> **配套规范**：[S14 测试用例设计指南](./test-case-design-guide.md)（双轨制 + 源码溯源字段）。
> **V3 版本**：含 5.5.1 源码影响分析 + 5.5.8 反馈闭环（V2 仅 5.5.2~5.5.7）。

---

## 一、步骤 5.5 强制流程（八个子步骤）

任何代码变更任务，在 OpenSpec 步骤 5（实施）与步骤 6（审核检查点）之间，**必须**执行步骤 5.5 AI 自动端到端测试。禁止跳过任何子步骤。

```
步骤 5（实施代码）
  ↓
步骤 5.5.1  源码影响分析（V3 新增）   → affected_modules.json
步骤 5.5.2  APK 自动发现 + MEmu 启动  → 设备就绪
步骤 5.5.3  双轨用例调度              → 选定复测 TC-ID（同 TC-ID Python 优先）
步骤 5.5.4  8 类证据收集               → 截图/UI XML/logcat/db/prefs/web_api/...
步骤 5.5.5  规则判定                  → pass/fail/manual/warning
步骤 5.5.6  manual 用例 AI agent 介入 → ai-prompt.md + ai_verdict 回填
步骤 5.5.7  五件套报告生成            → report.md/json + manual_cases.md + affected + feedback
步骤 5.5.8  反馈闭环触发（V3 新增）   → feedback_suggestions → 沉淀规则/陷阱/提示词
  ↓
🛑 检查点 2（用户审核实施 + 测试报告）
```

### 5.5.1 源码影响分析（V3 新增）⭐

**触发条件**：步骤 5 代码实施完成，提交前。

**执行命令**：

```bash
python ai_tests/run_e2e.py --diff HEAD~1
```

**执行逻辑**（M8 source_impact_analyzer）：
1. 读取 `git diff HEAD~1 --name-only` 获取改动文件列表
2. 对照 `ai_tests/lib/source_map.json`（Activity → TC-ID 映射）
3. 输出 `affected_modules.json`：含 `changed_files` + `affected_activities` + `affected_tc_ids`

**输出消费方式**：
- `affected_tc_ids` → 自动选复测 TC-ID（仅跑受影响的用例，而非全量）
- 若无影响用例 → 输出 "无受影响用例，跳过 E2E 测试" 并允许继续
- 若 `source_map.json` 过期（新增 Activity 未映射）→ 提示 AI 运行 `--update-source-map` 后重试

**source_map.json 维护**：详见 [ai_tests/docs/source_impact_guide.md](../../ai_tests/docs/source_impact_guide.md)

### 5.5.2 APK 自动发现 + MEmu 启动

**执行命令**：

```bash
python ai_tests/run_e2e.py --apk auto
```

**执行逻辑**（M2 apk_deployer + M1 memu_controller）：
1. 从 `config.APK_GLOB_DIR` 自动发现最新 APK
2. 启动 MEmu 模拟器（`config.MEMUC_PATH`）
3. ADB 连接（`config.MEMU_ADB_HOST`）
4. 安装 APK（覆盖安装，`config.PACKAGE`）
5. 启动主入口 Activity（`config.MAIN_ACTIVITY`）

**失败处理**：启动超时（`config.TIMEOUT_MEMU_START`）→ 重试一次 → 仍失败则报告致命错误（退出码 2）

### 5.5.3 双轨用例调度（V3 新增）

**执行逻辑**（M3 case_parser）：
1. 扫描 `docs/tests/*.md` + `ai_tests/cases/*/case.md`（A 轨 MD 用例）
2. 扫描 `ai_tests/cases/auto/auto_*.py`（B 轨 Python 用例）
3. 同 TC-ID 时 Python 优先（`config.DUAL_TRACK_PYTHON_PRIORITY`）
4. Python 失败时降级执行 MD（`config.DUAL_TRACK_FALLBACK_TO_MD`）
5. 若 5.5.1 产出了 `affected_tc_ids`，仅调度这些 TC-ID；否则全量调度

**V3 源码溯源字段强制**：每个用例必须含 `**关联源码**` + `**关联 Activity**`，缺失则报告覆盖率警告（见 `gen_module_matrix.py`）

### 5.5.4 8 类证据收集

**执行逻辑**（M5 evidence_collector）：每个用例执行后收集：
1. `logcat` — 日志切片（按 TC-ID 过滤）
2. `ui_xml` — UI 层级 XML（uiautomator2 dump）
3. `screenshot` — 截图 PNG
4. `activity_stack` — Activity 栈（adb shell dumpsys activity）
5. `db_state` — 数据库状态（adb shell run-as + sqlite3）
6. `prefs_state` — SharedPreferences 状态
7. `web_api` — Web API 调用结果（端口 8080，调试构建）
8. `meminfo` — 内存占用（adb shell dumpsys meminfo）

**失败处理**：单类证据收集失败不阻断（`config.EVIDENCE_TYPES` 逐类 try/except）

### 5.5.5 规则判定

**执行逻辑**（M6 rule_analyzer）：
1. 检查 `config.CRASH_PATTERNS`（FATAL/ANR/OOM/ClassNotFound/Other）
2. 匹配预期类型（8 种：`no_crash`/`log_clean`/`db_state`/`prefs_state`/`web_api`/`page_jump`/`element_visible`/`manual`，**权威源 = `ai_tests/lib/case_parser.py` 的 `EXPECT_TYPE_KEYWORDS` 映射表**；`no_crash` 最优先匹配，无关键词命中则落 `manual`。原文档此处列的 display/result_contains/rule_match/activity_state/process_state 均不存在，2026-09-11 已修正）
3. 输出 verdict：`pass` / `fail` / `manual` / `warning`
4. manual/fail 时输出 `feedback_signal`（含 failure_pattern/suggested_rule/suggested_prompt）

### 5.5.6 manual 用例 AI agent 介入

**触发条件**：verdict == manual（AI 无法自动判定，需人工/AI agent 介入）

**处理流程**：
1. 生成 `manual_cases.md`（含 manual 用例清单 + ai-prompt.md 路径 + AI agent 接入流程）
2. 为每个 manual 用例生成 `ai-prompt.md`（含证据摘要 + 预期结果 + 判定指引）
3. AI agent 读取 `ai-prompt.md` → 基于证据给出 `ai_verdict`（pass/fail/manual）
4. 回填 `ai_verdict` 到 `report.json` 的 `cases[].ai_verdict` 字段

**AI agent 接入接口**：详见 [第四章](#四ai-agent-接入接口)

### 5.5.7 五件套报告生成（V3 扩展为七件套）

**执行逻辑**（M7 report_generator）：

| 件 | 文件 | 说明 |
|----|------|------|
| 1 | `report.md` | Markdown 报告（失败置顶 + manual 置顶 + 全部用例表 + 执行环境 + affected + feedback） |
| 2 | `report.json` | JSON 报告（含 evidence_collected/ai_prompt_path/track_source/feedback_signal） |
| 3 | `manual_cases.md` | manual 用例清单 + AI agent 接入流程 |
| 4 | `affected_modules.json` | V3 受影响模块（5.5.1 产出） |
| 5 | `feedback_suggestions.md` | V3 反馈建议（人读，供 AI agent 审阅） |
| 6 | `feedback_suggestions.json` | V3 反馈建议（机器读，供 M16 回填） |
| 7 | `summary.txt` | 一行摘要（pass:N/M fail:N manual:N pass_rate:X%） |

**退出码**：0=全过，1=部分失败，2=致命错误

### 5.5.8 反馈闭环触发（V3 新增）⭐

**触发条件**：5.5.7 报告生成完成，存在 fail/manual 用例时。

**执行命令**：

```bash
python ai_tests/run_e2e.py --feedback
```

**执行逻辑**（M16 feedback_loop）：
1. 读取 `report.json`
2. 对每个 fail/manual 用例提取根因（`_extract_root_cause`：精确匹配 CRASH_PATTERNS + 通用异常正则）
3. 输出 4 类反馈建议：
   - `rule_suggestions` — 规则库扩展建议（新异常模式 → 扩展 `config.CRASH_PATTERNS`，需 AI 审核）
   - `prompt_suggestions` — 提示词调优建议（manual 用例 → 调优 `ai_prompt_template.j2`）
   - `known_issue_suggestions` — 陷阱库沉淀（直接追加到 `ai_tests/docs/known_issues.md`）
   - `regression_history_entry` — 回归历史记录（直接追加到 `ai_tests/docs/regression_history.md`）
4. 写入 `feedback_suggestions.md` + `feedback_suggestions.json`

**输出消费方式（AI 审阅闭环）**：
- `rule_suggestions` → AI 审阅 → 写入 `config.CRASH_PATTERNS`（固化层，需 OpenSpec 流程）
- `prompt_suggestions` → AI 审阅 → 调优 `ai_tests/templates/ai_prompt_template.j2`
- `known_issue_suggestions` → 已自动追加到 `known_issues.md`，AI 补充规避方式
- `regression_history_entry` → 已自动追加到 `regression_history.md`

---

## 二、5.5.1 源码影响分析详解（V3）

详见 [ai_tests/docs/source_impact_guide.md](../../ai_tests/docs/source_impact_guide.md)。要点：

- **输入**：`git diff HEAD~1 --name-only`
- **映射**：`source_map.json`（Activity 完整类名 → [TC-ID]）
- **输出**：`affected_modules.json`（changed_files + affected_activities + affected_tc_ids）
- **维护**：新增 Activity 后必须运行 `--update-source-map`，否则影响分析遗漏

---

## 三、5.5.8 反馈闭环详解（V3）

详见 [ai_tests/lib/feedback_loop.py](../../ai_tests/lib/feedback_loop.py) 的 `FeedbackLoop.process` 方法。要点：

- **根因提取两阶段**：1) 精确匹配 CRASH_PATTERNS（covered=True，不建议扩展） 2) 通用异常正则 `\w+(Exception|Error)` 捕获新异常（covered=False，建议扩展）
- **规则建议仅对未覆盖的新异常**：避免重复建议已入库的模式
- **陷阱库/回归历史自动追加**：文件不存在时自动创建带表头初始模板
- **AI 审阅闭环**：rule/prompt suggestions 需 AI 审阅后写入固化层/模板（known_issues 已自动追加）

---

## 四、AI agent 接入接口

AI agent（如 Claude/GPT）介入 manual 用例判定时，按以下流程：

### 4.1 读取输入

```
1. 读取 {report_dir}/report.json           → 获取 cases 列表
2. 读取 {report_dir}/manual_cases.md        → 获取 manual 用例清单 + ai-prompt 路径
3. 读取 {report_dir}/affected_modules.json  → 了解受影响模块（V3）
4. 对每个 manual 用例：
   a. 读取 {report_dir}/cases/{tc_id}/ai-prompt.md  → 判定指引 + 证据摘要
   b. 读取 {report_dir}/cases/{tc_id}/step-*.png    → 截图
   c. 读取 {report_dir}/cases/{tc_id}/log-slice.txt → 日志切片
```

### 4.2 给出判定

基于证据，对每个 manual 用例输出：

```json
{
  "tc_id": "TC-XXX",
  "ai_verdict": "pass" | "fail" | "manual",
  "ai_reason": "判定依据（引用具体证据）",
  "feedback_signal": {
    "failure_pattern": "若 fail，提取失败模式",
    "suggested_rule": "建议扩展的规则",
    "suggested_prompt": "建议调优的提示词"
  }
}
```

### 4.3 回填结果

将 `ai_verdict` 回填到 `report.json` 的 `cases[].ai_verdict` 字段。

### 4.4 触发反馈闭环

manual 判定完成后，若存在 fail：

```bash
python ai_tests/run_e2e.py --feedback
```

读取 `feedback_suggestions.md`，审阅后沉淀到 `known_issues.md` / `config.CRASH_PATTERNS` / `ai_prompt_template.j2`。

---

## 五、manual 用例处理流程

```
verdict == manual
  ↓
生成 ai-prompt.md（含证据摘要 + 预期结果 + 判定指引）
  ↓
AI agent 介入（读 ai-prompt.md + 证据目录）
  ↓
给出 ai_verdict（pass/fail/manual）
  ↓
回填到 report.json
  ↓
若 fail → 触发反馈闭环（5.5.8）
若 manual（仍无法判定）→ 标记需用户人工介入
```

---

## 六、失败用例处理流程

```
verdict == fail
  ↓
置顶到 report.md 失败用例区
  ↓
提取 feedback_signal（failure_pattern）
  ↓
触发反馈闭环（5.5.8）
  ↓
rule_suggestions → AI 审阅 → 扩展 CRASH_PATTERNS（固化层，需 OpenSpec）
known_issue_suggestions → 自动追加到 known_issues.md → AI 补充规避方式
  ↓
复测：修复后重新运行 run_e2e.py --tc {失败TC-ID}
```

---

## 七、反模式

❌ 跳过步骤 5.5 直接审核（必须执行自动测试）
❌ 不执行 5.5.1 源码影响分析（V3 强制）
❌ 不读取 manual_cases.md 就标记任务完成
❌ manual 用例不生成 ai-prompt.md
❌ V3 不触发 5.5.8 反馈闭环（存在 fail/manual 时强制）
❌ V3 不沉淀失败案例到反馈闭环
❌ 直接修改固化层 lib/（必须通过 OpenSpec 流程）
❌ 不按 S14 子规范设计测试用例（缺源码溯源字段）

---

## 八、相关文档

| 文档 | 说明 |
|------|------|
| [S14 测试用例设计指南](./test-case-design-guide.md) | 双轨制 + 源码溯源字段 + 步骤语义化 |
| [ai_tests/docs/source_impact_guide.md](../../ai_tests/docs/source_impact_guide.md) | M8 source_map.json 维护指南 |
| [ai_tests/docs/source_test_guide.md](../../ai_tests/docs/source_test_guide.md) | M9 B 轨 Python 用例生成指南 |
| [ai_tests/docs/known_issues.md](../../ai_tests/docs/known_issues.md) | 陷阱库（M16 自动追加） |
| [ai_tests/docs/regression_history.md](../../ai_tests/docs/regression_history.md) | 回归历史（M16 自动追加） |
| [ai_tests/docs/ai_collaboration_guide.md](../../ai_tests/docs/ai_collaboration_guide.md) | AI agent 协作指南 |
| [V3 设计文档](../specs/e2e-automated-testing/design.md) | 1.3.11 反馈闭环 + 1.3.12 编排层 |
