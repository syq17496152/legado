#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载 sherpa-onnx AAR 到 app/libs（AD-22 路径②：不入库 + sha256 校验）。

用法：
    ai_tests\\venv\\Scripts\\python.exe scripts/fetch_sherpa_aar.py
    ... --url <自定义地址> --sha256 <期望值>

说明：
- 默认锚定 1.13.1（与 Kotlin API 实读一致：OfflineTtsConfig / generateWithConfigAndCallback）
- 下载走 GitHub 直连，失败自动尝试 gh-proxy 镜像
- 校验通过才落 `app/libs/sherpa-onnx.aar`（.gitignore 已忽略二进制）
"""
from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app" / "libs" / "sherpa-onnx.aar"

DEFAULT_ASSET = "sherpa-onnx-static-link-onnxruntime-1.13.1.aar"
DEFAULT_URLS = [
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.1/" + DEFAULT_ASSET,
    "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.1/" + DEFAULT_ASSET,
]
# 已知体积（用于下载完整性初筛；sha256 以本机实测为准，可用 --sha256 强校验）
EXPECTED_SIZE = 38127776


def download(url: str, target: Path) -> bool:
    print(f"[fetch] {url}")
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            if not response.headers.get("Content-Length"):
                pass
            target.parent.mkdir(parents=True, exist_ok=True)
            temp = target.with_suffix(".part")
            with temp.open("wb") as out:
                while True:
                    chunk = response.read(1 << 16)
                    if not chunk:
                        break
                    out.write(chunk)
            if temp.stat().st_size < EXPECTED_SIZE * 0.98:
                print(f"[fetch] 体积异常：{temp.stat().st_size}")
                temp.unlink(missing_ok=True)
                return False
            temp.replace(target)
            return True
    except Exception as exc:  # noqa: BLE001
        print(f"[fetch] 失败：{exc}")
        return False


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", action="append", default=[])
    parser.add_argument("--sha256", default="")
    args = parser.parse_args()

    urls = args.url or DEFAULT_URLS
    for url in urls:
        if download(url, TARGET):
            value = sha256(TARGET)
            print(f"[fetch] sha256={value}")
            if args.sha256 and value != args.sha256:
                print("[fetch] sha256 不匹配，删除产物")
                TARGET.unlink(missing_ok=True)
                return 1
            print(f"[fetch] OK -> {TARGET}")
            return 0
    print("[fetch] 全部源失败；可手动下载后放到 app/libs/sherpa-onnx.aar")
    return 1


if __name__ == "__main__":
    sys.exit(main())
