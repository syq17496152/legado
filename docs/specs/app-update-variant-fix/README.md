# 应用内检查更新逻辑修复（app-update-variant-fix）

## 状态
**已完成（2026-09-12，编译+JVM 单测+真机 L2 全部通过）**

验证摘要：APK 3.26.091216 编译过；`AppReleaseInfoParseTest` 8 用例绿；真机 L2 T1（新包检查更新静默"暂无更新"、无"获取新版本出错"）+ T2（低版本包弹出更新框 tagName=3.26.090820、资产对号）双 PASS；证据 `ai_tests/reports/update_check_20260912_161932|162046/`。

## 概述
真机反馈"测试包更新失败/无更新提示"。排查（publish 侧只读核实，发版流程用户管控不动）确认应用内检查更新链路存在 4 处缺陷，全部在 App 侧解析/调度层：

| # | 缺陷 | 后果 |
|---|------|------|
| 1 | `PreferredAppUpdate` 把 Gitee 的"已是最新版本"异常当普通错误吞掉，强制降级 GitHub | 测试包/共存包每次检查必降级 GitHub beta 通道 |
| 2 | GitHub beta 通道指向 `releases/tags/beta` 滚动发布，该 release 从未创建（404 实测） | 兜底必抛"获取新版本出错(404)"→ 弹"检查更新"错误框 = 用户看到的"更新失败" |
| 3 | `GiteeAsset/Asset` 变体识别靠文件名含 `release/releaseA/releaseS`，实际上传名是 `legado_miss_app_debug_*`/`legado_miss_app_*`/`legado_legacy_app_*`（全不含 "release"） | 三个 asset 全解析为 OFFICIAL：测试包 filter 永远空 → 永远"已是最新"（有新版也不提示）；正式包混入测试/共存包 |
| 4 | `versionName = name.split("_")[2].dropLast(2)`，对新命名 `legado_miss_app_3.26.090820.apk` 解析出 `"app"→"a"` | 正式包 `"a" > "3.26.09..."` 恒真 → 永远弹"有新版本 a"且下载第一个 asset（= 测试包），正式包会被"更新"成测试包 |

实测数据支撑：Gitee/GitHub 两仓 latest release 均为 3.26.090820（prerelease=false，assets 三包命名如上）；GitHub `releases/tags/beta` 返回 404。

## 变更点（全部 App 侧，不动 publish 脚本）
- `AppReleaseInfo.kt`：变体识别改为按实际文件名（debug→BETA_RELEASE / legacy→BETA_RELEASEA / releaseA、releaseS 兼容保留 / prerelease+release→BETA_RELEASE 兼容旧滚动命名 / 其余 OFFICIAL）；versionName 解析改为取 `_` 最后一段去 `.apk`（旧命名 8 位日期尾部 2 位冗余自动 dropLast）
- `AppUpdate.kt`：`PreferredAppUpdate` 中 Gitee 判定"已是最新"作为终态直接上抛（isLatestVersionError 静默），仅真实错误才降级 GitHub
- `AppUpdateGitHub.kt`：beta 通道改用 `/releases/latest`（三包同处一个 release，变体靠文件名区分，与 Gitee 对齐；`tags/beta` 从未存在）

## 验证标准
- 编译通过；真机：测试包检查更新 → Gitee 090820 < 本地版本 → 静默"已是最新"（不再弹 404）；用旧版本包（< 090820）验证 → 能弹出更新对话框且版本号/下载链接为对应变体的正确 asset

## 文档索引
- [spec.md](./spec.md) ｜ [tasks.md](./tasks.md)
