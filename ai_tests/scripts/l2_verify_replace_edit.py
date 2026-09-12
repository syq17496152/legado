#!/usr/bin/env python3
r"""l2_verify_replace_edit.py — builtin-replace-id-fix L2 验证

验证内置替换净化规则 id 迁移（负 id -1~-12 → 正数 1~12）与编辑回显闭环：
  T1: 覆盖安装触发迁移 → DB 断言（负 id=0，正 id 1~12 齐全）
  T2: 打开替换净化列表 → 点击内置规则 → ReplaceEditActivity EditText 回显非空（核心缺陷闭环）

用法：
  ai_tests\venv\Scripts\python.exe ai_tests/scripts/l2_verify_replace_edit.py --scenario all
  ai_tests\venv\Scripts\python.exe ai_tests/scripts/l2_verify_replace_edit.py --scenario t1 --skip-install

产出：ai_tests/reports/replace_edit_<ts>/（ui xml、db 断言结果、logcat 尾部）
"""
import argparse
import glob
import os
import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, str(Path(__file__).parent.parent))
from config import ADB_PATH, MEMU_ADB_HOST, PACKAGE, MAIN_ACTIVITY, APK_GLOB_DIR, REPORTS_DIR

# 复用固化拉库通道（WAL 三件套安全处理）
sys.path.insert(0, str(Path(__file__).parent))
from import_rss_source import pull_db  # noqa: E402

REPLACE_RULE_ACTIVITY = "io.legado.app.ui.replace.ReplaceRuleActivity"
REPLACE_EDIT_ACTIVITY = "io.legado.app.ui.replace.edit.ReplaceEditActivity"
# 内置规则名关键词（本仓内置功能名，非书源业务数据）
BUILTIN_NAME_RE = re.compile(r"(标签残留|script 块|style 块|行首空白|空行压缩|错字|推广引导|引流整行|推广角标|跳转提示|Markdown|分隔线)")
BUILTIN_COUNT = 12
# 编辑页 EditText 的 hint 集合（空表单时 accessibility text 兜底显示 hint，无法与真回显按"非空"区分）
EDIT_HINTS = {
    "替换规则名称", "分组", "替换规则", "替换为",
    "替换范围，选填书名或者书源 URL", "排除范围，选填书名或者书源 URL",
}


def parse_edittexts(xml: str):
    """解析 dump 中全部 EditText 的 text 值（uiautomator 属性顺序 text 在 class 之前，按整节点解析）"""
    out = []
    for node in re.finditer(r"<node[^>]*>", xml):
        tag = node.group(0)
        cls = re.search(r'class="([^"]*)"', tag)
        txt = re.search(r'text="([^"]*)"', tag)
        if cls and "EditText" in cls.group(1) and txt:
            out.append(txt.group(1))
    return out


def sh(cmd, timeout=30):
    """执行 ADB 命令（整条命令单字符串传递，SOP 铁律 4/8）"""
    full_cmd = f'"{ADB_PATH}" -s {MEMU_ADB_HOST} {cmd}'
    return subprocess.run(full_cmd, shell=True, capture_output=True, text=True,
                          timeout=timeout, errors="ignore")


def find_latest_apk() -> str:
    """优先 output/apk/test 归档目录，退回 build 输出目录，取最新"""
    candidates = []
    for base in (str(APK_GLOB_DIR), str(APK_GLOB_DIR.parent.parent.parent.parent / "output" / "apk" / "test")):
        candidates.extend(glob.glob(os.path.join(base, "*.apk")))
    if not candidates:
        raise FileNotFoundError("未找到 APK，请先 build-legado.bat")
    return max(candidates, key=os.path.getmtime)


def dump_ui(evidence_dir: Path, name: str, retries: int = 3) -> str:
    """uiautomator dump 并返回本地 xml 文本（Compose 界面冷启动 accessibility 未就绪会失败，带重试）"""
    remote = "/sdcard/uidump_replace.xml"
    local = evidence_dir / f"{name}.xml"
    for attempt in range(retries):
        r = sh(f"shell uiautomator dump {remote}", timeout=20)
        out = (r.stdout or "") + (r.stderr or "")
        if "dump" not in out.lower():  # "UI hierchary dumped to" 才算成功
            print(f"  dump 第{attempt + 1}次未就绪（{out.strip()[:60]}），重试...")
            time.sleep(3)
            continue
        sh(f"pull {remote} \"{local}\"", timeout=20)
        sh(f"shell rm -f {remote}")
        if local.exists() and local.stat().st_size > 1000:
            return local.read_text(encoding="utf-8", errors="ignore")
        time.sleep(3)
    print(f"❌ dump_ui {name} 重试 {retries} 次仍失败")
    return ""


def tap_by_text(xml: str, pattern: re.Pattern) -> bool:
    """从 dump xml 找 text 匹配节点，取中心点 tap（dump→点击闭环，SOP 陷阱 1）"""
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        if pattern.search(text):
            sh(f"shell input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            return True
    return False


def current_activity() -> str:
    r = sh("shell dumpsys activity activities", timeout=20)
    m = re.search(r"topResumedActivity=.*?u0 ([\w.]+)/([\w.$]+)", r.stdout)
    if not m:
        m = re.search(r"mResumedActivity.*?u0 ([\w.]+)/([\w.$]+)", r.stdout)
    return f"{m.group(1)}/{m.group(2)}" if m else ""


def scenario_t1(evidence_dir: Path, skip_install: bool) -> bool:
    print("\n=== T1: 覆盖安装触发迁移 → DB 断言 ===")
    if not skip_install:
        apk = find_latest_apk()
        print(f"安装: {apk}")
        r = sh(f'install -r "{apk}"', timeout=180)
        if "Success" not in (r.stdout + r.stderr):
            print(f"❌ 安装失败: {r.stdout} {r.stderr}")
            return False
        print("✅ 覆盖安装成功")
    # 启动 App 触发 upVersion 迁移
    sh(f"shell am start -n {PACKAGE}/{MAIN_ACTIVITY}")
    time.sleep(15)
    # force-stop 后 Room 已 checkpoint，主 DB 即最新状态
    sh(f"shell am force-stop {PACKAGE}")
    time.sleep(2)
    tmp_db = evidence_dir / "legado.db"
    if not pull_db(str(tmp_db)):
        print("❌ 拉库失败")
        return False
    con = sqlite3.connect(str(tmp_db))
    cur = con.cursor()
    total = cur.execute("SELECT COUNT(*) FROM replace_rules").fetchone()[0]
    neg = cur.execute("SELECT COUNT(*) FROM replace_rules WHERE id < 0").fetchone()[0]
    ids = {row[0] for row in cur.execute("SELECT id FROM replace_rules").fetchall()}
    expected = set(range(1, BUILTIN_COUNT + 1))
    missing = sorted(expected - ids)
    con.close()
    print(f"  总规则数={total} 负id数={neg} 内置正id缺失={missing}")
    (evidence_dir / "t1_db_assert.txt").write_text(
        f"total={total}\nneg={neg}\nids={sorted(ids)}\nmissing={missing}\n", encoding="utf-8")
    ok = (neg == 0) and (not missing) and (total >= BUILTIN_COUNT)
    print("✅ T1 通过：负 id 已清零，内置正 id 1~12 齐全" if ok else "❌ T1 失败")
    return ok


def scenario_t2(evidence_dir: Path) -> bool:
    print("\n=== T2: 内置规则编辑回显非空 ===")
    sh(f"shell am force-stop {PACKAGE}")
    time.sleep(1)
    r = sh(f"shell am start -n {PACKAGE}/{REPLACE_RULE_ACTIVITY}")
    if r.returncode != 0:
        print(f"❌ 替换净化页启动失败: {r.stderr}")
        return False
    time.sleep(4)
    xml = dump_ui(evidence_dir, "t2_list")
    if not tap_by_text(xml, BUILTIN_NAME_RE):
        print("❌ 未在列表中定位到内置规则项（dump 已落盘排查）")
        return False
    time.sleep(3)
    act = current_activity()
    print(f"  当前 Activity: {act}")
    if REPLACE_EDIT_ACTIVITY not in act:
        print("❌ 未进入编辑页")
        return False
    xml2 = dump_ui(evidence_dir, "t2_edit")
    # 真实回显 = text 值不在 hint 白名单中（空字段 accessibility 兜底显示 hint）
    filled = [t for t in parse_edittexts(xml2) if t.strip() and t not in EDIT_HINTS]
    print(f"  真实回显字段数={len(filled)}（含替换内容等回显字段）")
    (evidence_dir / "t2_edit_assert.txt").write_text(
        f"activity={act}\nfilled_edittext={len(filled)}\n", encoding="utf-8")
    ok = len(filled) >= 2  # 名称 + 替换内容 至少两处非空回显
    print("✅ T2 通过：编辑页字段回显非空，缺陷闭环" if ok else "❌ T2 失败：编辑页仍为空数据")
    return ok


def scenario_t3(evidence_dir: Path) -> bool:
    print("\n=== T3: 新建替换规则回归（空表单态） ===")
    sh(f"shell am force-stop {PACKAGE}")
    time.sleep(1)
    # 无 extras 启动 → id 哨兵 -1 → 新建空表单（不保存，返回即无数据污染）
    sh(f"shell am start -n {PACKAGE}/{REPLACE_EDIT_ACTIVITY}")
    time.sleep(4)
    act = current_activity()
    print(f"  当前 Activity: {act}")
    if REPLACE_EDIT_ACTIVITY not in act:
        print("❌ 新建编辑页未打开")
        return False
    xml = dump_ui(evidence_dir, "t3_new")
    values = [t for t in parse_edittexts(xml) if t.strip()]
    real = [t for t in values if t not in EDIT_HINTS]  # 超出 hint 白名单 = 有真实内容
    has_title = "替换规则编辑" in xml
    print(f"  页面标题存在={has_title} EditText值={len(values)}（全hint={len(real) == 0}）真实内容={real}")
    (evidence_dir / "t3_assert.txt").write_text(
        f"activity={act}\nhas_title={has_title}\nvalues={values}\nreal={real}\n", encoding="utf-8")
    ok = has_title and len(real) == 0
    print("✅ T3 通过：新建态空表单正常" if ok else "❌ T3 失败")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", default="all", choices=["all", "t1", "t2", "t3"])
    ap.add_argument("--skip-install", action="store_true", help="T1 跳过安装（已装最新包时用）")
    args = ap.parse_args()

    evidence_dir = REPORTS_DIR / f"replace_edit_{time.strftime('%Y%m%d_%H%M%S')}"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    # logcat 尾部证据
    lc = sh("logcat -d -t 1000", timeout=20)
    (evidence_dir / "logcat_tail.txt").write_text(lc.stdout[-100000:], encoding="utf-8", errors="ignore")

    results = {}
    if args.scenario in ("all", "t1"):
        results["T1 迁移DB断言"] = scenario_t1(evidence_dir, args.skip_install)
    if args.scenario in ("all", "t2"):
        results["T2 编辑回显"] = scenario_t2(evidence_dir)
    if args.scenario in ("all", "t3"):
        results["T3 新建回归"] = scenario_t3(evidence_dir)

    print("\n" + "=" * 50)
    all_ok = all(results.values()) if results else False
    for k, v in results.items():
        print(f"  {'✅' if v else '❌'} {k}")
    print(f"结论: {'PASS' if all_ok else 'FAIL'}｜证据目录: {evidence_dir}")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
