# 真机测试流程复用规范

> 真机测试流程的复用机制，避免每次 spec 任务都重新设计测试流程，强制复用已有脚本。

## 触发场景

- 任何代码变更任务完成后（编译前必须更新 updateLog，编译后必须真机验证）
- OpenSpec 步骤 5.5 AI 自动端到端测试
- 功能优化/Bug 修复/重构任务
- 用户反馈问题后的验证

## 可用测试脚本清单

| 脚本 | 路径 | 用法 | 说明 |
|------|------|------|------|
| quick_build_install.py | `ai_tests/scripts/quick_build_install.py` | `python ai_tests/scripts/quick_build_install.py` | 编译+安装+L1验证 |
| import_rss_source.py | `ai_tests/scripts/import_rss_source.py` | `python ai_tests/scripts/import_rss_source.py <json>` | 导入订阅源 |
| import_book_source.py | `ai_tests/scripts/import_book_source.py` | `python ai_tests/scripts/import_book_source.py <json>` | 导入书源 |
| l2_verify_video_player.py | `ai_tests/scripts/l2_verify_video_player.py` | `python ai_tests/scripts/l2_verify_video_player.py [--scenario SCENARIO]` | L2验证视频播放器 |
| swipe_test_log.py | `ai_tests/scripts/swipe_test_log.py` | `python ai_tests/scripts/swipe_test_log.py [clear\|capture\|analyze]` | 日志分析 |

**强制规则**：所有测试操作必须使用 `ai_tests/scripts/` 下的固定脚本，禁止在 `temp/` 创建临时脚本。

## 测试环境

- **ADB 路径**：优先使用 `PATH` 中的 `adb`；本机示例为 MEmu 自带 `D:/Program Files/Microvirt/MEmu/adb.exe`（**环境相关，换机/换模拟器需自行替换，勿照抄**）
- **设备序列号**：`127.0.0.1:21503`（MEmu 默认端口；**换环境后必须先用 `adb devices` 查询实际值再替换**）
- 下文命令中的 `-s 127.0.0.1:21503` 同理，均为本机示例值；若已封装进 `ai_tests/scripts/` 脚本，优先走脚本而非手敲命令
- **包名**：`io.legado.miss.app.debug`
- **Python 环境**：`ai_tests/venv/Scripts/python.exe`（禁止公共 Python）
- **venv 激活**：`ai_tests\venv\Scripts\activate`

## 测试流程模板

### 标准测试流程（5步）

```
1. 编译安装：python ai_tests/scripts/quick_build_install.py
2. 导入数据：python ai_tests/scripts/import_rss_source.py <json>  或  import_book_source.py <json>
3. 执行功能：手动操作或脚本驱动
4. 日志分析：python ai_tests/scripts/swipe_test_log.py analyze  或  adb logcat
5. 问题记录：追加到 issues-found.md
```

### 数据库验证流程

由于 MEmu 设备无 sqlite3 二进制，必须用 ADB pull DB + Python sqlite3 查询：

```python
# 1. Pull DB（含WAL/SHM）
adb -s 127.0.0.1:21503 shell su -c 'cp /data/data/io.legado.miss.app.debug/databases/legado.db /sdcard/legado.db'
adb -s 127.0.0.1:21503 pull /sdcard/legado.db tmp.db

# 2. Python sqlite3 查询
import sqlite3
conn = sqlite3.connect('tmp.db')
cursor = conn.cursor()
cursor.execute("PRAGMA wal_checkpoint(TRUNCATE)")  # 关键：合并WAL
cursor.execute("SELECT count(*), min(weight), max(weight) FROM book_sources")
```

**重要**：Room 使用 WAL 模式，必须同时 pull `.db-wal`/`.db-shm` 文件，否则 WAL 中的旧状态会在 App 启动时覆盖导入的新数据。

## 测试发现问题的闭环

测试中发现问题必须强制闭环，防止压缩上下文后丢失：

```
发现问题 → 记录到 issues-found.md → 修复 → 真机验证 → 回填状态
```

### issues-found.md 记录格式

每个 Issue 必须包含5个维度：

```markdown
### Issue-N: 问题标题（P0/P1/P2 优先级）

- **发现时间**：YYYY-MM-DD
- **发现测试**：测试N - 功能名
- **问题描述**：具体现象
- **根因分析**：精确到文件+行号
- **修复方案**：具体代码改法
- **涉及文件**：文件列表
- **状态**：待修复 / 修复中 / 已修复
- **验证结果**：待验证 / 已验证PASS / 已验证FAIL
```

### 状态回填要求

- 修复完成后立即回填状态为"已修复"
- 真机验证通过后回填验证结果为"已验证PASS"
- 验证失败回填为"已验证FAIL"并分析原因

## 真机验证的核心要求

### 不能只做表面验证

- ❌ 只验证"能进入 Activity"
- ✅ 验证功能实际可用（UI 显示+实际执行+日志分析）

### 必须深度分析日志

- ❌ 只看脚本报告
- ✅ 用 logcat Grep 确认真实错误模式

**反模式**：测试报告说"90ms 优化成功"，但实际是 Socket 快速失败路径，根本没触发 AnalyzeUrl 真实请求模式（rss-concurrency Issue-10）。

### 校验类功能必须真正触发功能路径

测试校验功能时必须：
1. 勾选"域名" CheckBox
2. 选择"解析规则真实请求"模式
3. 触发 AnalyzeUrl 真实请求路径
4. 不能走 Socket 快速失败路径

## 测试 SOP

测试前必读：[ai_tests/docs/fixed_test_workflow.md](../../ai_tests/docs/fixed_test_workflow.md)

全量测试用例：`python ai_tests/run_e2e.py --tc all`

快速 L2 验证：用 `scripts/` 下脚本

## 反模式

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 在 temp/ 创建临时脚本 | 脚本丢失无法复用 | 用 ai_tests/scripts/ 固定脚本 |
| 只验证"能进入 Activity" | 功能实际不可用未发现 | 验证功能实际可用+日志分析 |
| 只看脚本报告 | 误报"通过" | 用 logcat Grep 确认真实错误模式 |
| 测试不勾选"域名"CheckBox | 走 Socket 快速失败路径 | 必须勾选触发 AnalyzeUrl 真实请求 |
| 测试发现问题不记录 | 压缩上下文后丢失 | 立即记录到 issues-found.md |
| 修复后不回填状态 | 不知道是否已修复 | 立即回填状态+验证结果 |
| 公共 Python 环境安装依赖 | 污染系统环境 | 用 ai_tests/venv/ 虚拟环境 |

## 何时必须加载本规范

- **Bug修复/代码优化任务开始时（强制）**：创建或追加 issues-found.md 问题清单，记录每个发现的问题
- 代码变更任务完成后（强制）
- OpenSpec 步骤 5.5 AI 自动端到端测试
- 用户反馈问题后的验证
