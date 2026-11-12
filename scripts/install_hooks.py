#!/usr/bin/env python3
"""安装仓库级 Git Hooks（从 scripts/hooks/ 复制到 .git/hooks/）。

用法：
    python scripts/install_hooks.py
    python scripts/install_hooks.py --check   # 只检查是否一致

依据：docs/project-rules/dual-remote-sync.md（双仓同步子规范）
"""
from __future__ import annotations

import argparse
import shutil
import stat
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "scripts" / "hooks"
DST = ROOT / ".git" / "hooks"


def install(check_only: bool = False) -> int:
    if not SRC.is_dir():
        print(f"[FAIL] 源目录不存在：{SRC}")
        return 1
    DST.mkdir(parents=True, exist_ok=True)
    changed = 0
    for src in sorted(SRC.iterdir()):
        if not src.is_file():
            continue
        dst = DST / src.name
        same = dst.exists() and dst.read_bytes() == src.read_bytes()
        if same:
            print(f"[OK]   {src.name} 已是最新")
            continue
        if check_only:
            print(f"[DIFF] {src.name} 与 .git/hooks 不一致")
            changed += 1
            continue
        shutil.copy2(src, dst)
        mode = dst.stat().st_mode
        dst.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        print(f"[OK]   已安装 {src.name} -> .git/hooks/{src.name}")
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
