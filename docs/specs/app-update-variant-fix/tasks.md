# tasks — app-update-variant-fix

- [x] 1.1 排查：三消费链路核实（Gitee/GitHub 真实 release JSON 实测 + AppConst 变体判定 + PreferredAppUpdate 调度）
- [x] 2.1 AppReleaseInfo.kt：变体映射函数 + versionName 解析修正（Asset/GiteeAsset 共用）
- [x] 2.2 AppUpdate.kt：PreferredAppUpdate"已是最新"终态透传
- [x] 2.3 AppUpdateGitHub.kt：beta 通道改 /releases/latest
- [x] 3.1 updateLog 当日条目合并（2026/09/12 条目追加修复项）
- [x] 3.2 docs/INDEX.md 登记
- [x] 4.1 编译 + 真机验证 ✅（2026-09-12）：JVM 单测 8 用例绿（AppReleaseInfoParseTest）+ 真机 L2 T1 PASS（新包检查更新静默"暂无更新"，无 404 误报）+ T2 PASS（低版本包弹出更新框 tagName=3.26.090820，asset 对号）；证据 `ai_tests/reports/update_check_20260912_161932|162046/`；quick_build_install 600s 超时陷阱已修（IF-1）
