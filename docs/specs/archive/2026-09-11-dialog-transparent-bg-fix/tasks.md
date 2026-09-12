# Tasks — 遗留弹窗背景图模式透明穿帮修复

## 1. 核心实现

- [x] 1.1 (L1) `DialogExtensions.kt` `AlertDialog.applyTint()`：`context.filletBackground` → `context.dialogSurfaceBackground`（验证标准：Grep `filletBackground` 在 applyTint 内 0 命中）✅
- [x] 1.2 (L1) `DialogExtensions.kt` `Dialog.applyModernWindowStyle()`：同步替换（验证标准：文件内 `filletBackground` 0 命中）✅
- [x] 1.3 (L1) prefs 三兄弟替换：`ListPreferenceDialog.kt:33` / `MultiSelectListPreferenceDialog.kt:35` / `EditTextPreferenceDialog.kt:32`（验证标准：三文件 `filletBackground` 0 命中，import 同步清理）✅
- [x] 1.4 (L1) 全仓 Grep 复核：`filletBackground` 消费点仅剩 MaterialValueHelper 定义处（+ .bak 忽略）✅（Grep 实证：app/src/main/java 仅 MaterialValueHelper.kt:178 定义处命中）

## 2. 编译与门禁

- [x] 2.1 (L1) updateLog.md 基于 git diff 更新（追加在 `## cronet版本:` 之后，第二十八批）✅
- [x] 2.2 (L1) `./gradlew :app:compileAppDebugKotlin` 编译通过 ✅（BUILD SUCCESSFUL 5m13s；首轮 journal-1.lock 拒绝访问，gradlew --stop 后重试成功）

## 3. 真机验证（测试包 io.legado.miss.app.debug）

- [x] 3.1 (L2·用户验收) 背景图模式：订阅源列表页右上角页码弹框不透明、可读 ✅
- [x] 3.2 (L2·用户验收) 背景图模式：任一 alert{} 弹窗 + 设置 Preference 弹窗不透明 ✅
- [x] 3.3 (L2·用户验收) 关闭背景图：同弹窗正常、无回归 ✅
- [x] 3.4 (L2·用户验收) 夜间模式：弹窗底色 #1C1C1E 系，文字可读 ✅
- [x] 3.5 E-Ink 模式抽验降级为代码时序论证：e-ink 覆写（bg_eink_border_dialog）发生在 applyTint 之后，不受换源影响 ✅

## 4. 收尾

- [x] 4.1 migration-registry.md §六.5 登记豁免口径 ✅
- [x] 4.2 docs/INDEX.md 登记（进行中→验收后归档）✅
