#!/usr/bin/env python3
"""Gitee 私仓发版（tag + Release + APK 上传）。

流程定位（2026-11-12 用户裁定，dual-remote-sync v2）：
    私仓先行发版 → 用户审核验证 → scripts/promote_to_github.py 复用同一 APK 推公仓。

用法：
    python scripts/publish_gitee_release.py --version 3.26.111121 --dry-run
    python scripts/publish_gitee_release.py --version 3.26.111121
    python scripts/publish_gitee_release.py --version 3.26.111121 --no-upload   # 只建 Release

依赖：publish_config.json 的 gitee 段（owner/repo/token/api_base），该文件不入库。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "scripts" / "publish_config.json"


def load_cfg() -> dict:
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    g = cfg.get("gitee") or {}
    for k in ("owner", "repo", "token"):
        if not g.get(k):
            raise SystemExit(f"[FAIL] publish_config.json 缺少 gitee.{k}")
    return cfg


def api(url: str, token: str, method: str = "GET", payload: dict | None = None):
    data = None
    headers = {"Content-Type": "application/json;charset=UTF-8"}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    sep = "&" if "?" in url else "?"
    req = urllib.request.Request(f"{url}{sep}access_token={urllib.parse.quote(token)}",
                                 data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read().decode("utf-8", "ignore")
            return json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")[:400]
        raise SystemExit(f"[FAIL] HTTP {e.code} {url} -> {detail}")


def upload_asset(url: str, token: str, file: Path):
    boundary = "----legado" + hashlib.md5(file.name.encode()).hexdigest()
    head = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{file.name}"\r\n'
            f"Content-Type: {mimetypes.guess_type(file.name)[0] or 'application/octet-stream'}\r\n\r\n").encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    body = head + file.read_bytes() + tail
    sep = "&" if "?" in url else "?"
    req = urllib.request.Request(f"{url}{sep}access_token={urllib.parse.quote(token)}",
                                 data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return json.loads(r.read().decode("utf-8", "ignore") or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")[:400]
        raise SystemExit(f"[FAIL] 上传失败 {file.name}: HTTP {e.code} -> {detail}")


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def find_apks(cfg: dict, version: str) -> list[Path]:
    out = []
    for _variant, d in (cfg.get("apk_dirs") or {}).items():
        base = ROOT / d
        if not base.is_dir():
            continue
        for p in base.glob("*.apk"):
            if version in p.name:
                out.append(p)
    return sorted(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--notes-file", default=str(ROOT / "output" / "release-notes"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-upload", action="store_true")
    args = ap.parse_args()

    cfg = load_cfg()
    g = cfg["gitee"]
    owner, repo, token = g["owner"], g["repo"], g["token"]
    api_base = g.get("api_base", "https://gitee.com/api/v5").rstrip("/")
    target = g.get("target_commitish", "master")

    apks = find_apks(cfg, args.version)
    if not apks:
        raise SystemExit(f"[FAIL] 未找到版本 {args.version} 的 APK（先跑 build-legado.bat）")

    notes_path = Path(args.notes_file) / f"{args.version}.md"
    body = notes_path.read_text(encoding="utf-8") if notes_path.exists() else f"版本 {args.version}"
    tag = f"v{args.version}"

    print(f"[INFO] 私仓 {owner}/{repo} tag={tag} target={target}")
    for p in apks:
        print(f"       APK {p.name}  {p.stat().st_size / 1024 / 1024:.2f} MB  sha256={sha256(p)[:16]}…")
    if args.dry_run:
        print("[OK] 预演完成（未调用 API）")
        return 0

    release = api(f"{api_base}/repos/{owner}/{repo}/releases", token, "POST", {
        "tag_name": tag,
        "name": f"Legado {args.version}",
        "body": body[:60000],
        "target_commitish": target,
        "prerelease": False,
    })
    rid = release.get("id")
    print(f"[OK] 已创建 Gitee Release id={rid} tag={tag}")

    if args.no_upload:
        print("[OK] 跳过附件上传")
        return 0

    for p in apks:
        res = upload_asset(f"{api_base}/repos/{owner}/{repo}/releases/{rid}/attach_files", token, p)
        print(f"[OK] 已上传 {p.name} -> {res.get('browser_download_url', '')}")

    manifest = {
        "version": args.version,
        "tag": tag,
        "repo": f"{owner}/{repo}",
        "assets": [{"name": p.name, "sha256": sha256(p), "size": p.stat().st_size} for p in apks],
    }
    out = ROOT / "output" / f"private-release-{args.version}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[OK] 发版清单：{out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
