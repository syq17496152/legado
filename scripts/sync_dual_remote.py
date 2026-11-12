#!/usr/bin/env python3
"""公私双仓同步（一键推送）。

公仓 origin（GitHub）  ：只推 master，只含核心源码；禁止 docs/ ai_tests/ .trae/ 等私有资产
私仓 gitee （Gitee）   ：推 master + private/assets（完整资产）+ 指定分支

用法：
    python scripts/sync_dual_remote.py --public            # 只推公仓 master
    python scripts/sync_dual_remote.py --private           # 只推私仓 master + private/assets
    python scripts/sync_dual_remote.py --all               # 两仓都推
    python scripts/sync_dual_remote.py --private --branch release/stable-20261111
    python scripts/sync_dual_remote.py --dry-run           # 只打印将执行的命令

规范：docs/project-rules/dual-remote-sync.md
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys

PUBLIC = "origin"
PRIVATE = "gitee"
PRIVATE_DEFAULT_BRANCHES = ["master"]


def run(cmd: list[str], dry: bool = False, env_extra: dict | None = None) -> int:
    printable = " ".join(cmd)
    if dry:
        print(f"[dry-run] {printable}")
        return 0
    env = os.environ.copy()
    env["ALLOW_PUSH"] = "1"
    if env_extra:
        env.update(env_extra)
    print(f"[run] {printable}")
    return subprocess.call(cmd, env=env)


def current_branch() -> str:
    out = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"],
                         capture_output=True, text=True)
    return out.stdout.strip()


def ensure_clean() -> bool:
    out = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True)
    dirty = [l for l in out.stdout.splitlines() if not l.startswith("??")]
    if dirty:
        print("[FAIL] 工作区有未提交改动，先提交或暂存：")
        for l in dirty[:10]:
            print(f"  {l}")
        return False
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--public", action="store_true", help="推公仓 master")
    ap.add_argument("--private", action="store_true", help="推私仓")
    ap.add_argument("--all", action="store_true", help="公私两仓都推")
    ap.add_argument("--branch", action="append", default=[], help="额外推送的分支（私仓）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not (args.public or args.private or args.all):
        ap.print_help()
        return 2

    if not args.dry_run and not ensure_clean():
        return 1

    do_public = args.public or args.all
    do_private = args.private or args.all
    rc = 0

    if do_public:
        # 公仓为「核心源码快照」：由 publish_public_snapshot.py 过滤生成后推送，本地文件不删
        cmd = [sys.executable, str(ROOT / "scripts" / "publish_public_snapshot.py")]
        if not args.dry_run:
            cmd.append("--push")
        if run(cmd, args.dry_run) != 0:
            rc = 1

    if do_private:
        ssh_env = {"GIT_SSH_COMMAND": "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes"}
        branches = list(dict.fromkeys(PRIVATE_DEFAULT_BRANCHES + args.branch))
        for b in branches:
            if run(["git", "push", PRIVATE, f"{b}:{b}"], args.dry_run, ssh_env) != 0:
                print(f"[WARN] 私仓分支推送失败：{b}（可能本地无该分支或网络问题）")
                rc = 1

    print(f"[{'OK' if rc == 0 else 'FAIL'}] 同步完成（branch={current_branch()}）")
    return rc


if __name__ == "__main__":
    sys.exit(main())
