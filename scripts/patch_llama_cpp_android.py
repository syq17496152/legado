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
    5) lib/src/main/cpp/ai_chat.cpp：chat 模板空指针防护（crash-hardening AD-05，tombstone 实证）
"""

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIB = ROOT / "third_party" / "llama.cpp" / "examples" / "llama.android" / "lib"
GRADLE = LIB / "build.gradle.kts"
LOGGING = LIB / "src" / "main" / "cpp" / "logging.h"
AI_CHAT = LIB / "src" / "main" / "cpp" / "ai_chat.cpp"

GRADLE_PATCHES = [
    (
        "alias(libs.plugins.jetbrains.kotlin.android)",
        "alias(libs.plugins.kotlin.android)",
    ),
    ("minSdk = 33", "minSdk = 23"),
    ('-DGGML_BACKEND_DL=ON', '-DGGML_BACKEND_DL=OFF'),
    ('-DGGML_CPU_ALL_VARIANTS=ON', '-DGGML_CPU_ALL_VARIANTS=OFF'),
]

# 追加参数（不是替换，需显式判重）：KleidiAI 走 CMake FetchContent 从 GitHub 拉源码包，
# 配置阶段网络不可达会直接失败；本仓只要纯 CPU 后端。
KLEIDIAI_ARG = 'arguments += "-DGGML_CPU_KLEIDIAI=OFF"'
KLEIDIAI_ANCHOR = 'arguments += "-DGGML_LLAMAFILE=OFF"'
OPENMP_ARG = 'arguments += "-DGGML_OPENMP=OFF"'

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

# crash-hardening AD-05（2026-11-11）：chat 模板空指针防护。
# 真机 tombstone 实证：`system prompt` 二次设置失败后继续生成会让 g_chat_templates 为空，
# `common_chat_templates_was_explicit` 解引用空指针 SIGSEGV，整个 `:llm` 进程消失。
AI_CHAT_HELPER_OLD = """static common_sampler                   * g_sampler;"""

AI_CHAT_HELPER_NEW = """static common_sampler                   * g_sampler;

// crash-hardening AD-05: chat templates may be null after a failed init or after unload().
// Every use site must check this before dereferencing, otherwise the whole :llm process
// dies with SIGSEGV (tombstone evidence: common_chat_templates_was_explicit +0, fault addr 0x2).
static bool chat_template_available() {
    return g_chat_templates != nullptr;
}"""

AI_CHAT_PREPARE_OLD = """    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);"""

AI_CHAT_PREPARE_NEW = """    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    // crash-hardening AD-05: template parsing may throw (invalid jinja). Never let a C++
    // exception cross the JNI boundary; report it as a distinct error code (3) instead.
    try {
        g_chat_templates = common_chat_templates_init(g_model, "");
    } catch (const std::exception & e) {
        LOGe("%s: failed to initialize chat templates: %s", __func__, e.what());
        return 3;
    } catch (...) {
        LOGe("%s: failed to initialize chat templates (unknown error)", __func__);
        return 3;
    }
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);"""

AI_CHAT_SYSTEM_OLD = """    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);"""

AI_CHAT_SYSTEM_NEW = """    // Format system prompt if applicable
    // crash-hardening AD-05: null template MUST be rejected before any dereference (used to SIGSEGV here).
    if (!chat_template_available()) {
        LOGe("%s: chat templates unavailable, reject system prompt", __func__);
        env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
        return 4;
    }
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);"""

AI_CHAT_USER_OLD = """    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);"""

AI_CHAT_USER_NEW = """    // Format user prompt if applicable
    // crash-hardening AD-05: same null guard as processSystemPrompt (tombstone crash path).
    if (!chat_template_available()) {
        LOGe("%s: chat templates unavailable, reject user prompt", __func__);
        env->ReleaseStringUTFChars(juser_prompt, user_prompt);
        return 4;
    }
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);"""

AI_CHAT_PATCHES = [
    (AI_CHAT_HELPER_OLD, AI_CHAT_HELPER_NEW),
    (AI_CHAT_PREPARE_OLD, AI_CHAT_PREPARE_NEW),
    (AI_CHAT_SYSTEM_OLD, AI_CHAT_SYSTEM_NEW),
    (AI_CHAT_USER_OLD, AI_CHAT_USER_NEW),
]


def check() -> int:
    if not GRADLE.is_file():
        print("[FAIL] 子模块未初始化：%s" % GRADLE)
        return 1
    text = GRADLE.read_text(encoding="utf-8")
    missing = [old for old, _ in GRADLE_PATCHES if old in text]
    log = LOGGING.read_text(encoding="utf-8") if LOGGING.is_file() else ""
    log_missing = LOGGING_OLD in log
    if not AI_CHAT.is_file():
        print("[FAIL] 子模块未初始化：%s" % AI_CHAT)
        return 1
    ai_text = AI_CHAT.read_text(encoding="utf-8")
    ai_missing = [old for old, new in AI_CHAT_PATCHES if old in ai_text and new not in ai_text]
    kleidiai_missing = KLEIDIAI_ARG not in text
    openmp_missing = OPENMP_ARG not in text
    if missing or log_missing or kleidiai_missing or openmp_missing or ai_missing:
        print("[FAIL] 未打补丁：gradle=%d 处、logging=%s、kleidiai=%s、openmp=%s、ai_chat=%d 处"
              % (len(missing), log_missing, kleidiai_missing, openmp_missing, len(ai_missing)))
        return 1
    print("[OK] 补丁已就位（gradle 4 处 + kleidiai 1 处 + openmp 1 处 + logging 1 处 + ai_chat 4 处）")
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
    if KLEIDIAI_ARG not in text and KLEIDIAI_ANCHOR in text:
        text = text.replace(
            KLEIDIAI_ANCHOR,
            KLEIDIAI_ANCHOR + "\n                " + KLEIDIAI_ARG
        )
        changed += 1
    if OPENMP_ARG not in text and KLEIDIAI_ARG in text:
        text = text.replace(
            KLEIDIAI_ARG,
            KLEIDIAI_ARG + "\n                " + OPENMP_ARG
        )
        changed += 1
    GRADLE.write_text(text, encoding="utf-8")
    if LOGGING.is_file():
        log = LOGGING.read_text(encoding="utf-8")
        if LOGGING_OLD in log:
            LOGGING.write_text(log.replace(LOGGING_OLD, LOGGING_NEW), encoding="utf-8")
            changed += 1
    if AI_CHAT.is_file():
        ai = AI_CHAT.read_text(encoding="utf-8")
        ai_changed = 0
        for old, new in AI_CHAT_PATCHES:
            # 幂等：辅助函数补丁的 new 以 old 开头，必须用 new 判重防止重复插入
            if old in ai and new not in ai:
                ai = ai.replace(old, new)
                ai_changed += 1
        if ai_changed:
            AI_CHAT.write_text(ai, encoding="utf-8")
            changed += ai_changed
    print("[OK] 已应用补丁 %d 处" % changed)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="补丁 llama.cpp Android 模块")
    ap.add_argument("--check", action="store_true", help="只检查不修改")
    args = ap.parse_args()
    return check() if args.check else apply()


if __name__ == "__main__":
    sys.exit(main())
