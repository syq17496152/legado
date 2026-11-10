#!/usr/bin/env python3
"""l2_import_voice_pack.py —— 真机 L2：导入全量音色包并核对归档/去重结果（2026-11-10）。

背景：用户提供过全量 CloneTTS 音色包（506 条），但清空/重装后库里只剩播种音色，
真机音色页显示 282 条且全是「前缀+序号」。本脚本负责：
  1) 把音色包放到应用外置导入目录（tts-import，adb 可写、免存储权限）；
  2) `--es import_voices <文件名>` 走应用内导入通道（不经过 SAF）；
  3) 回读音色页摘要，打印 导入回执 + 音色总数 + 归档分组标题，供人工/门禁判定。

用法：
  ai_tests\\venv\\Scripts\\python.exe ai_tests/scripts/l2_import_voice_pack.py [包文件名]
默认包名 CloneTTS_Voices_20261030_090010.zip（设备 /sdcard/02.阅读/CloneTts/ 下）。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb360 import open_adb  # noqa: E402

PKG = "io.legado.miss.app.debug"
BUILTIN = "%s/io.legado.app.ui.book.read.config.BuiltinTtsActivity" % PKG
SOURCE_DIR = "/sdcard/02.阅读/CloneTts"
IMPORT_DIR = "/sdcard/Android/data/%s/files/tts-import" % PKG
DEFAULT_PACK = "CloneTTS_Voices_20261030_090010.zip"


def dump_texts(adb, serial):
    xml = adb.dump_ui(serial=serial)
    texts = []
    for match in re.finditer(r"<node[^>]*?/?>", xml):
        for t in re.findall(r'(?:text|content-desc)="([^"]*)"', match.group(0)):
            if t.strip():
                texts.append(t)
    return texts


def wait_text(adb, serial, keyword, tries=10):
    for _ in range(tries):
        for t in dump_texts(adb, serial):
            if keyword in t:
                return t
        time.sleep(1.5)
    return ""


def main():
    pack = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PACK
    adb = open_adb()
    serial = adb.resolve_serial("")

    exists = adb.shell("ls -l %s/%s" % (IMPORT_DIR, pack), serial=serial, timeout=30.0).strip()
    if "No such file" in exists or not exists:
        src = "%s/%s" % (SOURCE_DIR, pack)
        out = adb.shell("cp '%s' '%s/' 2>&1" % (src, IMPORT_DIR), serial=serial, timeout=300.0)
        print("[..] 复制音色包到导入目录：%s" % (out.strip() or "ok"))
        exists = adb.shell("ls -l %s/%s" % (IMPORT_DIR, pack), serial=serial, timeout=30.0).strip()
    print("[OK] 导入目录就绪：%s" % exists.split()[-1] if exists else "[FAIL] 音色包未就绪")

    adb.shell("logcat -c", serial=serial, timeout=30.0)
    adb.shell(
        "am start -W -n %s --es page_tab voices --es import_voices %s" % (BUILTIN, pack),
        serial=serial, timeout=120.0
    )
    msg = wait_text(adb, serial, "导入", tries=12)
    print("[回执] %s" % (msg or "（未捕获导入回执）"))

    summary = wait_text(adb, serial, "个音色", tries=8)
    print("[摘要] %s" % (summary or "（未捕获音色总数）"))

    sections = [t for t in dump_texts(adb, serial) if re.search(r"(本档可用|其他档位) · ", t)]
    print("[分组] %s" % (" | ".join(sections[:6]) if sections else "（当前首屏无分组标题）"))

    fatal = adb.shell(
        "logcat -d -b crash | grep -E 'FATAL EXCEPTION' | tail -3", serial=serial, timeout=60.0
    )
    fatal_lines = [l for l in fatal.splitlines() if l.strip()]
    print("[FATAL] %d 行" % len(fatal_lines))

    ok = bool(summary) and not fatal_lines
    print("\n结果：%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
