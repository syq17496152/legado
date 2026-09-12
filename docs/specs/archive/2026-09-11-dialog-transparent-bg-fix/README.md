# 遗留弹窗背景图模式透明穿帮修复（dialog-transparent-bg-fix）

> 状态：**✅ 已完成并验收归档（2026-09-11，测试包 legado_miss_app_3.26.091123.apk）**｜路径：快速路径｜创建：2026-09-11

## 功能概述

背景图模式（`主题→背景图` 配置且非 E-Ink）下，所有走 `AlertDialog.applyTint()` 及 prefs 三兄弟手动设背景的遗留 View 弹窗，其窗口背景取自 `Context.backgroundColor`——该属性在背景图模式下返回 `Color.TRANSPARENT`（为主界面沉浸设计），导致弹窗整体透明穿帮。典型现象：订阅源文章列表页右上角"页码"选择器弹框只剩数字轮子悬空。

修复方式：将弹窗窗口底色源从 `filletBackground`（消费 backgroundColor）统一切换为 `dialogSurfaceBackground`（独立弹窗底色，`themeCardColor` → `R.color.dialog_surface` 兜底，不透明），一处根因修复覆盖全部同类弹窗。

## 变更点

| 文件 | 变更 |
|------|------|
| `app/src/main/java/io/legado/app/utils/DialogExtensions.kt` | `AlertDialog.applyTint()`：`filletBackground` → `dialogSurfaceBackground`（覆盖 alert{} DSL 25 豁免文件、NumberPickerDialog 全部 9 调用方、ColorPreference） |
| `app/src/main/java/io/legado/app/lib/prefs/ListPreferenceDialog.kt` | 同上替换（L33） |
| `app/src/main/java/io/legado/app/lib/prefs/MultiSelectListPreferenceDialog.kt` | 同上替换（L35） |
| `app/src/main/java/io/legado/app/lib/prefs/EditTextPreferenceDialog.kt` | 同上替换（L32） |
| `app/src/main/java/io/legado/app/utils/DialogExtensions.kt` | `Dialog.applyModernWindowStyle()`（死代码）同步替换，防未来启用复活 bug |

## 受益弹窗（修复前均透明穿帮）

- NumberPickerDialog 调用方 ×9：订阅源页码（RssArticlesFragment:448）、发现页页码（ExploreShowActivity:55）、书源选择换源（SourcePickerDialog:104）、漫画页码 ×2（ReadMangaActivity:905）、朗读 BGM（ReadAloudBgmManageActivity:867）、code 设置（code/SettingsDialog:172）、视频设置 ×2（video/SettingsDialog:89、VideoSettingsPanel:180）、键盘辅助（KeyboardAssistsConfig:174）
- `alert{}` DSL：AndroidAlertBuilder（25 文件登记豁免）
- ColorPreference 取色器
- 设置 Preference 弹窗三兄弟

## 文档索引

- [spec.md](./spec.md) — 需求与场景
- [tasks.md](./tasks.md) — 任务清单

## 变更日志

- 2026-09-11 创建；Explore 完成，红队审查 2 轮通过
- 2026-09-11 实施完成（4 文件 5 处换源，compileAppDebugKotlin 通过），测试包 3.26.091123 产出
- 2026-09-11 用户真机验收通过，归档至 `docs/specs/archive/2026-09-11-dialog-transparent-bg-fix/`
