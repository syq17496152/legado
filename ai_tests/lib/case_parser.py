"""ai_tests/lib/case_parser.py — M3 用例解析器（V3 双轨调度）

职责：
- 解析 docs/tests/*.md 和 ai_tests/cases/*/case.md 为结构化 TestCase
- V3 双轨调度：同 TC-ID 时 Python 优先于 MD
- V3 源码溯源：related_source / related_activity 字段
- 步骤语义化：关键词映射到 Action（click/input/wait/scroll/back/assert）
- 预期类型识别：8 种预期类型

支持格式（容错）：
1. ## TC-F-P0-1-01：编码转换工具（正常用例）
2. ### TC-P0-1-01：协程正常取消不触发错误回调（正常用例）
3. ### TC-01: Vite 构建成功

解析段：
- **测试步骤**： + 1. xxx 列表
- **预期结果**： + - ✅ xxx 列表
- **前置条件**：/ **前置资源**：[AI自备]/[用户必供]/[共享]
- **关联源码**：xxx.kt
- **关联 Activity**：xxxActivity
"""
import re
import logging
from pathlib import Path
from typing import Optional, List
from dataclasses import dataclass, field

from ai_tests.config import DOCS_TESTS_DIR, AI_TESTS_CASES_DIR

logger = logging.getLogger(__name__)


# === 任务 5.2：正则定义 ===

# TC 头部：## TC-XXX：标题 或 ### TC-XXX：标题（支持中英文冒号）
# 兼容：TC-F-P0-1-01 / TC-P0-1-01 / TC-01 / TC-XX-001
TC_HEADER_RE = re.compile(
    r'^#{2,3}\s+(TC-[A-Za-z0-9\-]+)\s*[:：]\s*(.+?)\s*$'
)

# 段落标题：**测试步骤**： / **预期结果**：等（支持中英文冒号 + 行尾内容）
SECTION_RE = re.compile(
    r'^\*\*(.+?)\*\*\s*[:：]\s*(.*)$'
)

# 步骤行：1. xxx / 2. xxx（数字 + 点 + 空格）
STEP_RE = re.compile(r'^\s*\d+\.\s+(.+?)\s*$')

# 预期行：- ✅ xxx / - ❌ xxx / - ✓ xxx / - xxx（支持 emoji 和无 emoji）
EXPECT_RE = re.compile(r'^\s*-\s*[✅❌✓✗]?\s*(.+?)\s*$')

# 前置资源标记：[AI自备] xxx / [用户必供] xxx / [共享] xxx
PRECOND_RE = re.compile(r'^\s*\[(AI自备|用户必供|共享)\]\s*(.+?)\s*$')


# === 任务 5.1：数据模型（V3 扩展字段）===

@dataclass
class Step:
    """测试步骤"""
    action: str = "assert"  # click/input/wait_element/scroll/back/sleep/assert
    target: str = ""  # 元素描述
    value: str = ""  # 输入值（input 动作）
    raw: str = ""  # 原始文本


@dataclass
class Expect:
    """预期结果"""
    expect_type: str = "manual"  # 8 种：page_jump/element_visible/no_crash/log_clean/db_state/prefs_state/web_api/manual
    description: str = ""
    raw: str = ""


@dataclass
class Precondition:
    """前置资源"""
    resource_type: str = "共享"  # AI自备/用户必供/共享
    description: str = ""
    raw: str = ""


@dataclass
class TestCase:
    """测试用例（V3 扩展双轨调度字段）"""
    tc_id: str  # TC-F-P0-1-01 等
    title: str
    case_type: str = ""  # 正常用例/边界用例/异常用例/Level N
    module: str = ""  # F-P0-1 等
    steps: List[Step] = field(default_factory=list)
    expects: List[Expect] = field(default_factory=list)
    preconditions: List[Precondition] = field(default_factory=list)
    related_source: List[str] = field(default_factory=list)  # V3 关联源码
    related_activity: List[str] = field(default_factory=list)  # V3 关联 Activity
    source_file: str = ""  # 来源文件路径
    # V3 双轨调度字段
    python_track_path: Optional[str] = None  # B 轨 Python 用例路径
    track_source: str = "md"  # 实际执行轨道：md/python
    # 容错
    parse_warnings: List[str] = field(default_factory=list)
    missing_precondition: bool = False  # 用户必供资源缺失时标记


class CaseParser:
    """用例解析器（V3 双轨调度）"""

    # === 任务 5.5：步骤关键词→动作映射 ===
    ACTION_KEYWORDS = {
        "输入": "input",
        "填写": "input",
        "选择目标": "click",  # "选择目标编码" → click
        "选择": "click",
        "点击": "click",
        "按下": "click",
        "tap": "click",
        "打开": "click",
        "进入": "click",
        "找到": "click",
        "复制": "click",
        "触发": "click",
        "切换": "click",
        "安装": "click",
        "运行": "click",
        "观察": "assert",
        "查看": "assert",
        "验证": "assert",
        "等待": "wait_element",
        "滑动": "scroll",
        "滚动": "scroll",
        "返回": "back",
        "press_back": "back",
    }

    # === 任务 5.6：预期类型关键词映射（8 种预期类型）===
    EXPECT_TYPE_KEYWORDS = [
        # 顺序：先匹配 no_crash（最优先），再匹配其他
        ("不崩溃", "no_crash"),
        ("无崩溃", "no_crash"),
        ("不ANR", "no_crash"),
        ("无ANR", "no_crash"),
        ("无异常", "no_crash"),
        ("不出现", "no_crash"),
        ("BUILD SUCCESSFUL", "log_clean"),
        ("无错误", "log_clean"),
        ("无异常日志", "log_clean"),
        ("日志", "log_clean"),
        ("进度", "db_state"),
        ("保存", "db_state"),
        ("数据", "db_state"),
        ("配置", "prefs_state"),
        ("API", "web_api"),
        ("接口", "web_api"),
        ("URL", "page_jump"),
        ("跳转", "page_jump"),
        ("打开", "page_jump"),
        ("显示", "element_visible"),
        ("可见", "element_visible"),
        ("出现", "element_visible"),
        ("高亮", "element_visible"),
    ]

    def __init__(self):
        self.warnings: List[str] = []

    # === 任务 5.5：步骤语义化 ===
    def _classify_action(self, step_text: str) -> Step:
        """将步骤文本映射到结构化 Step

        匹配 ACTION_KEYWORDS 中的关键词，提取目标和输入值
        """
        step = Step(raw=step_text)
        for keyword, action in self.ACTION_KEYWORDS.items():
            if keyword in step_text:
                step.action = action
                # 提取目标：取关键词后的内容
                idx = step_text.find(keyword)
                if idx >= 0:
                    rest = step_text[idx + len(keyword):].strip()
                    rest = rest.lstrip("：: 、").strip()
                    step.target = rest
                break
        else:
            # 无关键词匹配，默认 assert
            step.action = "assert"
            step.target = step_text

        # 提取输入值（input 动作 + 引号内容）
        if step.action == "input":
            m = re.search(r'["\'`]([^"\'`]+)["\'`]', step_text)
            if m:
                step.value = m.group(1)
        return step

    # === 任务 5.6：预期类型识别 ===
    def _classify_expect(self, expect_text: str) -> Expect:
        """将预期文本映射到结构化 Expect（8 种预期类型）"""
        expect = Expect(raw=expect_text, expect_type="manual", description=expect_text)
        for keyword, expect_type in self.EXPECT_TYPE_KEYWORDS:
            if keyword in expect_text:
                expect.expect_type = expect_type
                break
        return expect

    # === 任务 5.3：单文件解析 ===
    def parse_file(self, path: Path) -> List[TestCase]:
        """解析单个 MD 文件，返回 TestCase 列表

        状态机扫描行：
        - 识别 TC 头部 → 新建 TestCase
        - 识别段落标题 → 切换 current_section
        - 在段落内识别步骤行/预期行/前置资源行
        - 容错：格式不规范时 parse_warnings，不阻断
        """
        path = Path(path)
        if not path.exists():
            logger.warning(f"用例文件不存在: {path}")
            return []

        try:
            text = path.read_text(encoding="utf-8")
        except Exception as e:
            logger.error(f"读取文件失败: {path}: {e}")
            return []

        cases: List[TestCase] = []
        current_tc: Optional[TestCase] = None
        current_section: Optional[str] = None
        current_module: str = ""

        # 从文件名提取模块（F-P0-1-debug-tools.md → F-P0-1）
        fname = path.stem
        m = re.match(r'^([FPA]-P[01]-\d+)', fname)
        if m:
            current_module = m.group(1)
        elif path.parent.name and path.parent.name.lower() != "cases":
            # 兜底：文件名无模块前缀时用所在目录名（F-UI-THEME/case.md → F-UI-THEME）
            current_module = path.parent.name

        for line_no, line in enumerate(text.splitlines(), 1):
            line_rstrip = line.rstrip()

            # 1. TC 头部匹配
            m = TC_HEADER_RE.match(line_rstrip)
            if m:
                # 保存上一个用例
                if current_tc is not None:
                    cases.append(current_tc)
                tc_id = m.group(1)
                title = m.group(2).strip()
                # 从标题提取用例类型（正常用例/边界用例/异常用例/Level N）
                case_type = ""
                type_m = re.search(
                    r'[（(]\s*(正常用例|边界用例|异常用例|Level\s*\d+)\s*[）)]',
                    title,
                )
                if type_m:
                    case_type = type_m.group(1)
                    title = re.sub(
                        r'\s*[（(]\s*(正常用例|边界用例|异常用例|Level\s*\d+)\s*[）)]\s*',
                        '', title,
                    ).strip()
                current_tc = TestCase(
                    tc_id=tc_id,
                    title=title,
                    case_type=case_type,
                    module=current_module,
                    source_file=str(path),
                )
                current_section = None
                continue

            if current_tc is None:
                continue

            # 2. 段落标题匹配
            m = SECTION_RE.match(line_rstrip)
            if m:
                section_name = m.group(1).strip()
                section_content = m.group(2).strip()
                if "测试步骤" in section_name:
                    current_section = "steps"
                elif "预期结果" in section_name:
                    current_section = "expects"
                elif "前置条件" in section_name or "前置资源" in section_name:
                    current_section = "preconditions"
                    # V3 前置资源：检查 [AI自备]/[用户必供]/[共享] 标记
                    if section_content:
                        pm = PRECOND_RE.match(section_content)
                        if pm:
                            current_tc.preconditions.append(Precondition(
                                resource_type=pm.group(1),
                                description=pm.group(2),
                                raw=section_content,
                            ))
                        else:
                            # 旧格式的前置条件，归为"共享"
                            current_tc.preconditions.append(Precondition(
                                resource_type="共享",
                                description=section_content,
                                raw=section_content,
                            ))
                elif "关联源码" in section_name:
                    current_section = "related_source"
                    if section_content:
                        current_tc.related_source.append(section_content)
                elif "关联Activity" in section_name or "关联 Activity" in section_name:
                    current_section = "related_activity"
                    if section_content:
                        current_tc.related_activity.append(section_content)
                else:
                    current_section = None
                continue

            # 3. 段落内容匹配
            if current_section == "steps":
                sm = STEP_RE.match(line_rstrip)
                if sm:
                    step_text = sm.group(1).strip()
                    step = self._classify_action(step_text)
                    current_tc.steps.append(step)
            elif current_section == "expects":
                em = EXPECT_RE.match(line_rstrip)
                if em:
                    expect_text = em.group(1).strip()
                    expect = self._classify_expect(expect_text)
                    current_tc.expects.append(expect)
            elif current_section == "preconditions":
                pm = PRECOND_RE.match(line_rstrip)
                if pm:
                    current_tc.preconditions.append(Precondition(
                        resource_type=pm.group(1),
                        description=pm.group(2),
                        raw=line_rstrip,
                    ))
                elif line_rstrip.strip() and not line_rstrip.startswith("---"):
                    # 旧格式前置条件（无标记）
                    current_tc.preconditions.append(Precondition(
                        resource_type="共享",
                        description=line_rstrip.strip(),
                        raw=line_rstrip,
                    ))
            elif current_section == "related_source":
                if line_rstrip.strip() and not line_rstrip.startswith("---"):
                    current_tc.related_source.append(line_rstrip.strip())
            elif current_section == "related_activity":
                if line_rstrip.strip() and not line_rstrip.startswith("---"):
                    current_tc.related_activity.append(line_rstrip.strip())

        # 文件末尾保存最后一个用例
        if current_tc is not None:
            cases.append(current_tc)

        # 任务 5.9：容错检查
        for tc in cases:
            if not tc.steps:
                tc.parse_warnings.append(f"{tc.tc_id}: 无测试步骤")
            if not tc.expects:
                tc.parse_warnings.append(f"{tc.tc_id}: 无预期结果")
            # 用户必供资源检查
            for p in tc.preconditions:
                if p.resource_type == "用户必供":
                    # 实际缺失检查由 run_e2e 在执行前完成
                    pass

        logger.info(f"解析 {path.name}: {len(cases)} 个用例")
        return cases

    # === 任务 5.4：批量解析 ===
    def parse_directory(self, dir_path: Path) -> List[TestCase]:
        """批量解析目录下所有 MD 文件"""
        dir_path = Path(dir_path)
        if not dir_path.exists():
            logger.warning(f"目录不存在: {dir_path}")
            return []

        all_cases: List[TestCase] = []
        for md_file in sorted(dir_path.glob("*.md")):
            if md_file.name == "README.md":
                continue
            cases = self.parse_file(md_file)
            all_cases.extend(cases)

        # V3 双轨调度：为每个用例查找 B 轨 Python 用例
        for tc in all_cases:
            python_path = self._find_python_track(tc.tc_id, tc.module)
            if python_path:
                tc.python_track_path = python_path
                tc.track_source = "python"
            else:
                tc.track_source = "md"

        logger.info(f"批量解析 {dir_path}: {len(all_cases)} 个用例")
        return all_cases

    def parse_all(self) -> List[TestCase]:
        """合并解析 docs/tests/ + ai_tests/cases/ 所有用例"""
        all_cases: List[TestCase] = []
        # docs/tests/ 顶层 MD
        all_cases.extend(self.parse_directory(DOCS_TESTS_DIR))
        # ai_tests/cases/*/case.md
        if AI_TESTS_CASES_DIR.exists():
            for case_dir in sorted(AI_TESTS_CASES_DIR.iterdir()):
                if case_dir.is_dir():
                    for md_file in sorted(case_dir.glob("*.md")):
                        all_cases.extend(self.parse_file(md_file))
        logger.info(f"合并解析全部用例: {len(all_cases)} 个")
        return all_cases

    # === 任务 5.7：V3 _find_python_track ===
    def _find_python_track(self, tc_id: str, module: str) -> Optional[str]:
        """V3 新增：查找 B 轨 Python 用例路径

        扫描 ai_tests/cases/{module}/auto_*.py 寻找同 TC-ID 的 B 轨用例
        匹配规则：
        1. 文件名匹配：auto_{tc_id_lower_with_underscores}.py
        2. 文件头注释匹配：@tc_id: TC-XXX
        """
        if not module:
            return None
        module_dir = AI_TESTS_CASES_DIR / module
        if not module_dir.exists():
            return None

        # 规则 1：文件名匹配（TC-ID 转小写，- 替换为 _）
        target_name = f"auto_{tc_id.lower().replace('-', '_')}.py"
        target_path = module_dir / target_name
        if target_path.exists():
            return str(target_path)

        # 规则 2：扫描文件头 @tc_id 注释
        for py_file in module_dir.glob("auto_*.py"):
            try:
                with open(py_file, "r", encoding="utf-8") as f:
                    for _ in range(20):  # 只读前 20 行
                        line = f.readline()
                        if not line:
                            break
                        if "@tc_id" in line and tc_id in line:
                            return str(py_file)
            except Exception:
                continue
        return None

    # === 任务 5.8：V3 双轨调度 ===
    def dispatch_test_case(self, tc: TestCase) -> str:
        """V3 双轨调度：同 TC-ID 时 Python 优先于 MD

        Returns: 实际执行轨道 "python" / "md"
        """
        if tc.python_track_path:
            return "python"
        return "md"
