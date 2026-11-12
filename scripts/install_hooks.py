#!/usr/bin/env python3
"""安装仓库级 Git Hooks（复制到 .git/hooks/ 并置可执行位）。

单一源清单（HOOKS）：
    pre-commit ← ai_tests/scripts/hooks/pre-commit   （统一门禁 runner，含 G-55 开发基线）
    pre-push   ← scripts/hooks/pre-push              （双仓准入：gitee 全量 / origin 快照）

为什么 pre-commit 源在 ai_tests/ 而不是 scripts/hooks/：
    它由「统一门禁 runner」派发（gate_registry.json 唯一登记处），是项目既有卡点体系的正本；
    若在 scripts/hooks/ 再放一份副本，两处逻辑会各自演进 ⇒ 双源漂移（G-17 C4 的口径）。

用法：
    python scripts/install_hooks.py
    python scripts/install_hooks.py --check   # 只检查是否一致

依据：docs/project-rules/dual-remote-sync.md（双仓同步）、
      docs/project-rules/dev-branch-baseline.md（开发基线卡点）
"""
from __future__ import annotations

import argparse
import shutil
import stat
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DST = ROOT / ".git" / "hooks"

# hook 名 -> 仓库内版本化源文件（唯一源）
HOOKS = {
    "pre-commit": ROOT / "ai_tests" / "scripts" / "hooks" / "pre-commit",
    "pre-push": ROOT / "scripts" / "hooks" / "pre-push",
}


def install(check_only: bool = False) -> int:
    missing = [str(src) for src in HOOKS.values() if not src.is_file()]
    if missing:
        print(f"[FAIL] 源文件缺失：{', '.join(missing)}")
        return 1
    DST.mkdir(parents=True, exist_ok=True)
    changed = 0
    for name, src in HOOKS.items():
        dst = DST / name
        same = dst.exists() and dst.read_bytes() == src.read_bytes()
        if same:
            print(f"[OK]   {name} 已是最新")
            continue
        if check_only:
            print(f"[DIFF] {name} 与 .git/hooks 不一致")
            changed += 1
            continue
        shutil.copy2(src, dst)
        mode = dst.stat().st_mode
        dst.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        print(f"[OK]   已安装 {name} -> .git/hooks/{name}")
        changed += 1
    if check_only and changed:
        print(f"[FAIL] {changed} 个 hook 未同步")
        return 1
    print("[OK] hooks 安装检查完成")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只检查不安装")
    args = ap.parse_args()
    sys.exit(install(args.check))
