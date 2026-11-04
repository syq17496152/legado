# -*- coding: utf-8 -*-
"""把 llama.cpp 官方 Android 示例模块补丁成可被本仓（minSdk 23 / AGP 8.13）消费的形态。

为什么需要这个脚本
    `third_party/llama.cpp` 是 git submodule（固定 tag b9999），**子模块内改动不会随主仓提交**。
    官方模块面向「独立示例工程」：minSdk=33、按自己的 version catalog 取插件别名、CMake 用
    `__android_log_is_loggable`（API 30+）。直接 include 到本仓会编译失败。
    本脚本把四处必要补丁做成**幂等**操作；换机 / `git submodule update --init` 后重跑一次即可。

用法
    python scripts/patch_llama_cpp_android.py           # 应用补丁（重复执行安全）
    python scripts/patch_llama_cpp_android.py --check   # 只检查，未打补丁则 exit 1

补丁清单（与 docs/specs/offline-tts-llm-productization/tasks.md 2.1 对应）
    1) lib/build.gradle.kts：`libs.plugins.jetbrains.kotlin.android` → `libs.plugins.kotlin.android`
    2) lib/build.gradle.kts：minSdk 33 → 23（本应用 minSdk 23）
    3) lib/build.gradle.kts：GGML_BACKEND_DL / GGML_CPU_ALL_VARIANTS 置 OFF（单 CPU 后端直链，控制包体）
    4) lib/src/main/cpp/logging.h：`__android_log_is_loggable` 在 API < 30 时退化为级别比较
"""

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIB = ROOT / "third_party" / "llama.cpp" / "examples" / "llama.android" / "lib"
GRADLE = LIB / "build.gradle.kts"
LOGGING = LIB / "src" / "main" / "cpp" / "logging.h"

GRADLE_PATCHES = [
    (
        "alias(libs.plugins.jetbrains.kotlin.android)",
        "alias(libs.plugins.kotlin.android)",
    ),
    ("minSdk = 33", "minSdk = 23"),
    ('-DGGML_BACKEND_DL=ON', '-DGGML_BACKEND_DL=OFF'),
    ('-DGGML_CPU_ALL_VARIANTS=ON', '-DGGML_CPU_ALL_VARIANTS=OFF'),
]

LOGGING_OLD = """static inline int ai_should_log(int prio) {
    return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);
}"""

LOGGING_NEW = """static inline int ai_should_log(int prio) {
#if __ANDROID_API__ >= 30
    return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);
#else
    return prio >= LOG_MIN_LEVEL;
#endif
}"""


def check() -> int:
    if not GRADLE.is_file():
        print("[FAIL] 子模块未初始化：%s" % GRADLE)
        return 1
    text = GRADLE.read_text(encoding="utf-8")
    missing = [old for old, _ in GRADLE_PATCHES if old in text]
    log = LOGGING.read_text(encoding="utf-8") if LOGGING.is_file() else ""
    log_missing = LOGGING_OLD in log
    if missing or log_missing:
        print("[FAIL] 未打补丁：gradle=%d 处、logging=%s" % (len(missing), log_missing))
        return 1
    print("[OK] 补丁已就位（gradle 4 处 + logging 1 处）")
    return 0


def apply() -> int:
    if not GRADLE.is_file():
        print("[FAIL] 子模块未初始化：%s" % GRADLE)
        return 1
    text = GRADLE.read_text(encoding="utf-8")
    changed = 0
    for old, new in GRADLE_PATCHES:
        if old in text:
            text = text.replace(old, new)
            changed += 1
    GRADLE.write_text(text, encoding="utf-8")
    if LOGGING.is_file():
        log = LOGGING.read_text(encoding="utf-8")
        if LOGGING_OLD in log:
            LOGGING.write_text(log.replace(LOGGING_OLD, LOGGING_NEW), encoding="utf-8")
            changed += 1
    print("[OK] 已应用补丁 %d 处" % changed)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="补丁 llama.cpp Android 模块")
    ap.add_argument("--check", action="store_true", help="只检查不修改")
    args = ap.parse_args()
    return check() if args.check else apply()


if __name__ == "__main__":
    sys.exit(main())
