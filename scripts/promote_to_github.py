#!/usr/bin/env python3
"""私仓发版 → 公仓复用（不重复打包）。

前置：已执行 scripts/publish_gitee_release.py，产出 output/private-release-{version}.json。

做的事：
    1. 校验本地 APK 的 sha256 与私仓发版清单一致（确保「同一份产物」）
    2. 调用 publish_release.py --skip-build 只做校验 + GitHub Release + tag（不重新构建）
    3. 推送公仓源码快照（scripts/publish_public_snapshot.py）

用法：
    python scripts/promote_to_github.py --version 3.26.111121 --dry-run
    python scripts/promote_to_github.py --version 3.26.111121
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def find_local(version: str) -> list[Path]:
    cfg = json.loads((ROOT / "scripts" / "publish_config.json").read_text(encoding="utf-8"))
    out = []
    for _v, d in (cfg.get("apk_dirs") or {}).items():
        base = ROOT / d
        if base.is_dir():
            out.extend(p for p in base.glob("*.apk") if version in p.name)
    return sorted(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--skip-snapshot", action="store_true", help="不推公仓源码快照")
    args = ap.parse_args()

    manifest_path = ROOT / "output" / f"private-release-{args.version}.json"
    if not manifest_path.exists():
        print(f"[FAIL] 缺少私仓发版清单：{manifest_path}（先跑 publish_gitee_release.py）")
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    expected = {a["name"]: a["sha256"] for a in manifest.get("assets", [])}
    local = find_local(args.version)
    if not local:
        print(f"[FAIL] 本地无 {args.version} 的 APK")
        return 1

    mismatch = []
    for p in local:
        want = expected.get(p.name)
        if want is None:
            print(f"[WARN] 私仓清单无此产物，跳过校验：{p.name}")
            continue
        got = sha256(p)
        status = "OK" if got == want else "MISMATCH"
        print(f"[{status}] {p.name} sha256={got[:16]}…")
        if got != want:
            mismatch.append(p.name)

    if mismatch:
        print("[FAIL] 本地 APK 与私仓发版产物不一致，禁止复用（请重新打包或重新发私仓）")
        return 1

    cmd = [PY, str(ROOT / "scripts" / "publish_release.py"),
           "--version", args.version, "--skip-build", "--platform", "github"]
    if args.dry_run:
        cmd.append("--dry-run")
    env = os.environ.copy()
    env["ALLOW_PUSH"] = "1"
    print(f"[run] {' '.join(cmd)}")
    rc = subprocess.call(cmd, cwd=ROOT, env=env)
    if rc != 0:
        print("[FAIL] 公仓发布失败")
        return rc

    if not args.skip_snapshot:
        snap = [PY, str(ROOT / "scripts" / "publish_public_snapshot.py")]
        if not args.dry_run:
            snap.append("--push")
        print(f"[run] {' '.join(snap)}")
        rc = subprocess.call(snap, cwd=ROOT, env=env)
        if rc != 0:
            print("[FAIL] 公仓源码快照推送失败")
            return rc

    print(f"[OK] {args.version} 已由私仓复用至公仓（未重复打包）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
