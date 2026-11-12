#!/usr/bin/env python3
"""开发分支基线自检（开工卡点 + G-55 门禁）。

背景（2026-11-12 用户裁定，dual-remote-sync v2）：
    本地只有 master（全量）→ 私仓 gitee/master 与之保持一致；
    公仓 origin/master 是「源码快照」，**不能作为开发基线**。
    AI 若基于公仓快照或误在 detached 状态开发，会造成分支/版本混乱。

用法：
    python scripts/check_dev_baseline.py          # 人工/AI 开工前自检（G-55 亦直接调用本命令）

退出码：0 = 通过（可含 WARN）；1 = 基线错误（阻断开发/提交）
"""
from __future__ import annotations

import subprocess
import sys
from typing import Optional

PRIVATE_REMOTE = "gitee"
PRIVATE_BRANCH = "master"


def git(*args: str) -> tuple[int, str]:
    p = subprocess.run(["git", *args], capture_output=True, text=True)
    return p.returncode, p.stdout.strip()


def evaluate(
    branch: str,
    upstream: str,
    behind: Optional[int],
    ahead: Optional[int],
    dirty_count: int,
    origin_is_ancestor: bool,
) -> tuple[list[str], list[str]]:
    """纯判定：输入 git 状态快照，输出 (fails, warns)。

    与 IO 解耦以便单测覆盖（ai_tests/tests/test_check_dev_baseline.py）。
    behind / ahead 为 None 表示无法从私仓读取（如私仓引用尚未 fetch）。
    """
    fails: list[str] = []
    warns: list[str] = []

    # C1 detached HEAD
    if branch == "HEAD" or not branch:
        fails.append("当前处于 detached HEAD（不在任何分支上），禁止在此状态开发")

    # C2 上游：master 必须跟踪 gitee/master
    if branch == PRIVATE_BRANCH:
        if not upstream:
            fails.append(
                f"master 未设置上游：请执行 git branch --set-upstream-to={PRIVATE_REMOTE}/{PRIVATE_BRANCH} master"
            )
        elif upstream.startswith("origin/"):
            fails.append(
                f"master 上游指向公仓快照 {upstream}：改为 {PRIVATE_REMOTE}/{PRIVATE_BRANCH}（公仓只收快照，不可作开发基线）"
            )
        elif upstream != f"{PRIVATE_REMOTE}/{PRIVATE_BRANCH}":
            warns.append(f"master 上游为 {upstream}，期望 {PRIVATE_REMOTE}/{PRIVATE_BRANCH}")
    else:
        if upstream.startswith("origin/"):
            warns.append(f"分支 {branch} 跟踪公仓 {upstream}，建议改跟踪私仓同名分支")

    # C3 与私仓最新一致（落后 / 领先提示）
    if behind:
        warns.append(
            f"本地落后 {PRIVATE_REMOTE}/{PRIVATE_BRANCH} {behind} 个提交：先 "
            f"git pull --rebase {PRIVATE_REMOTE} {PRIVATE_BRANCH} 再开发"
        )
    if ahead:
        warns.append(f"本地领先私仓 {ahead} 个提交：开发完成后记得推送私仓")

    # C4 工作区状态提示
    if dirty_count:
        warns.append(f"工作区有 {dirty_count} 项未提交改动（正常开发中，提交前确认归类）")

    # C5 公仓快照不可 pull 提示（origin/master 通常不是本地祖先）
    if not origin_is_ancestor:
        warns.append(
            f"origin/{PRIVATE_BRANCH} 不是本地祖先（符合预期：公仓是过滤快照）→ 禁止 git pull origin master"
        )

    return fails, warns


def collect_state() -> tuple[str, str, Optional[int], Optional[int], int, bool]:
    """从 git 采集判定输入。任一读取失败按「未知/宽松」处理，避免误阻断。"""
    rc, branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if rc != 0 or not branch:
        branch = "HEAD"

    upstream = ""
    if branch != "HEAD":
        rc, up = git("rev-parse", "--abbrev-ref", f"{branch}@{{upstream}}")
        upstream = up if rc == 0 else ""

    def count(spec: str) -> Optional[int]:
        rc, out = git("rev-list", "--count", spec)
        return int(out) if rc == 0 and out.isdigit() else None

    behind = count(f"HEAD..{PRIVATE_REMOTE}/{PRIVATE_BRANCH}")
    ahead = count(f"{PRIVATE_REMOTE}/{PRIVATE_BRANCH}..HEAD")

    rc, status = git("status", "--porcelain")
    dirty = len([l for l in status.splitlines() if l.strip()]) if rc == 0 else 0

    rc, _ = git("merge-base", "--is-ancestor", f"origin/{PRIVATE_BRANCH}", "HEAD")
    return branch, upstream, behind, ahead, dirty, rc == 0


def main() -> int:
    branch, upstream, behind, ahead, dirty, origin_is_ancestor = collect_state()
    fails, warns = evaluate(branch, upstream, behind, ahead, dirty, origin_is_ancestor)

    print("=" * 68)
    print(f"开发基线自检  branch={branch}  upstream={upstream or '(none)'}")
    print("=" * 68)
    for f in fails:
        print(f"  [FAIL] {f}")
    for w in warns:
        print(f"  [WARN] {w}")
    if not fails and not warns:
        print("  [PASS] 基线正常：本地 master 跟踪私仓，可与私仓同步开发")

    if fails:
        print("-" * 68)
        print("[FAIL] 基线错误：禁止在该状态开发/提交")
        print(f"  建议：git switch master && git branch --set-upstream-to={PRIVATE_REMOTE}/{PRIVATE_BRANCH} master")
        return 1
    print(f"[OK] 基线检查通过（WARN {len(warns)} 项不阻断）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
