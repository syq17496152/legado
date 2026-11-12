#!/usr/bin/env python3
"""公仓快照推送：从本地全量 master 过滤出「核心源码」快照，推送到 GitHub 公仓。

背景（2026-11-12 用户裁定，dual-remote-sync v2）：
    本地 master 与 Gitee 私仓为「最全」内容（含 docs/、ai_tests/、.trae/、单测）；
    GitHub 公仓只允许核心源码。为不牺牲本地文件，改为「快照推送」：
    在临时 index 中剔除私有路径 → 生成一个快照 commit → force push 到 origin/master。

特性：
    - 不修改本地工作区、不切换分支、不删除任何文件
    - 快照 commit 的 parent 为上一次快照（公仓历史线性、可追溯）
    - 默认 --dry-run 只统计，需显式 --push 才推送

用法：
    python scripts/publish_public_snapshot.py                 # 预演（统计）
    python scripts/publish_public_snapshot.py --push          # 实际推送
    python scripts/publish_public_snapshot.py --source HEAD --remote origin --branch master
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 公仓排除路径（相对仓库根，前缀匹配）
EXCLUDE_PREFIXES = (
    "docs/",
    "ai_tests/",
    ".trae/",
    "app/src/test/",
    "temp/",
    ".temp/",
    "output/",
    "tools/reverse/",
    "tts/",
    "modules/web/node_modules/",
)


def git(*args: str, env: dict | None = None, check: bool = True) -> str:
    proc = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, env=env)
    if check and proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败：{proc.stderr.strip()}")
    return proc.stdout.strip()


def build_snapshot_tree(source: str) -> tuple[str, int]:
    """在临时 index 中构建过滤后的 tree，返回 (tree_sha, 文件数)。"""
    with tempfile.TemporaryDirectory() as tmp:
        index = os.path.join(tmp, "index")
        env = os.environ.copy()
        env["GIT_INDEX_FILE"] = index
        git("read-tree", source, env=env)
        paths = git("ls-tree", "-r", "--name-only", source).splitlines()
        excluded = [p for p in paths if p.startswith(EXCLUDE_PREFIXES)]
        if excluded:
            # 用 stdin 传路径（NUL 分隔），避免 Windows 命令行长度上限
            proc = subprocess.run(
                ["git", "update-index", "--force-remove", "-z", "--stdin"],
                cwd=ROOT, env=env,
                input="\0".join(excluded).encode("utf-8"),
                capture_output=True,
            )
            if proc.returncode != 0:
                raise RuntimeError(f"git update-index 失败：{proc.stderr.decode('utf-8', 'ignore')}")
        tree = git("write-tree", env=env)
        kept = len(paths) - len(excluded)
        return tree, kept


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default="HEAD")
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--branch", default="master")
    ap.add_argument("--push", action="store_true", help="实际推送（默认只预演）")
    ap.add_argument("--message", default="")
    args = ap.parse_args()

    tree, kept = build_snapshot_tree(args.source)
    total = len(git("ls-tree", "-r", "--name-only", args.source).splitlines())
    print(f"[INFO] 源 {args.source}：{total} 文件 → 快照 {kept} 文件（排除 {total - kept}）")
    print(f"[INFO] tree = {tree}")

    # 上次快照 parent（不存在则无 parent）
    remote_ref = f"refs/remotes/{args.remote}/{args.branch}"
    parent = git("rev-parse", "--verify", remote_ref, check=False)

    msg = args.message or f"chore(public): 源码快照（源自 {git('rev-parse', '--short', args.source)}，已剔除私有资产）"
    cmd = ["commit-tree", tree, "-m", msg]
    if parent:
        cmd += ["-p", parent]
    commit = git(*cmd)
    print(f"[INFO] snapshot commit = {commit}")

    diff_stat = git("diff", "--stat", parent, commit, check=False) if parent else "(首次快照)"
    if diff_stat:
        lines = diff_stat.splitlines()
        print(f"[INFO] 变更：{lines[-1] if lines else '无'}")

    if not args.push:
        print("[OK] 预演完成（未推送）。加 --push 执行：")
        print(f"     git push {args.remote} {commit}:refs/heads/{args.branch} --force")
        return 0

    env = os.environ.copy()
    env["ALLOW_PUSH"] = "1"
    proc = subprocess.run(
        ["git", "push", args.remote, f"{commit}:refs/heads/{args.branch}", "--force"],
        cwd=ROOT, env=env,
    )
    if proc.returncode != 0:
        print("[FAIL] 快照推送失败（检查 pre-push 闸门/网络）")
        return 1
    print(f"[OK] 已推送公仓 {args.remote}/{args.branch} ← {commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
