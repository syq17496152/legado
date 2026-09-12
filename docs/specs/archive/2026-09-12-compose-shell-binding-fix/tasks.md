# tasks.md — compose-shell-binding-fix

## 1. 准备工作

- [x] 1.1 读 `BaseActivity` binding 消费路径与 `RssArticleInfoActivity` 全文，确认工厂函数签名（验证标准：工厂返回类型满足 `BaseActivity<ViewBinding>` 泛型约束）✅ 蓝队代理复核 BaseActivity.kt:46/55 泛型上界满足
- [x] 1.2 基于本 spec 的 git diff 更新 `app/src/main/assets/updateLog.md`（追加在 `## cronet版本:` 之后、已有条目之前）（验证标准：diff 审计逐文件对照，无漏项）✅ 第三十一批已录入，编译前完成

## 2. 核心实现

- [x] 2.1 新增 `base/ComposeBindingShells.kt`：`composeShell(context: Context): ViewBinding`，FrameLayout 在函数局部作用域创建并被匿名对象局部捕获 + MATCH_PARENT LayoutParams，附注释说明 Kotlin 合成属性遮蔽陷阱 ✅ (L1)
- [x] 2.2 `RssArticleInfoActivity`：删除类级 `root` 与手写匿名对象，`binding` 改为 `by lazy { composeShell(this) }` ✅ (L1)
- [x] 2.3 `AiImageProviderEditActivity` + `AiImageGalleryActivity`：同 2.2 ✅ (L1)（两文件内无其他 root 引用，Grep 核验）
- [x] 2.4 `RssFreeGridLayoutManager` 日志节流：`[布局]`/`[填充]`/`[区间]` 走 `putThrottledLog`（2000ms 窗口 + suppressed 计数 + `SystemClock.elapsedRealtime()`）；`提前返回`/`[回填]` 保持全量；`layoutChild` footer 校准处新增 1 条全量诊断日志 `[校准]` ✅ (L1)
- [x] 2.5 `LocalConfig.rssFreeFullLog` 开关（默认关），`onAttachedToWindow` 读取一次实例级缓存，布局路径零 LocalConfig 直读 ✅ (L1)

## 3. 验证测试

- [x] 3.1 Grep 审计（合入卡点）✅ PASS——迁移后全仓 `object : ViewBinding` 匿名对象剩余 6 处（工厂本身 + RelaySettings/Config/AiProviderEdit 局部捕获 + BookInfoCompose `refreshLayout` + SpeakerGroupManageDialog `composeView`，全部安全）+ `SimpleViewBinding` 命名类（构造参数 `rootView`，安全）；3 雷区已无手写匿名壳
- [x] 3.2 编译测试包 3.26.091212debug 并安装 ✅ BUILD SUCCESSFUL 17m58s（journal-1.lock daemon 残留按 SOP 清理后过）
- [x] 3.3 真机场景一（R1）：RssArticleInfoActivity 直启 onCreate ✅ PASS（L2）——崩溃点 `setContentView(binding.root)` 已越过，进程存活无 StackOverflow（完整 UI 路径的搜索→点结果交互未自动化，但崩溃点在本路径内）
- [x] 3.4 真机场景二（R3）：AiImageGalleryActivity + AiImageProviderEditActivity ✅ PASS（L2）——两页创建正常无崩溃
- [ ] 3.5 真机场景三（R4）：⏸ **受阻待补**——自由布局 style=5 在模拟器环境白屏（A/B 铁证：改动前 091200 构建同样白屏，既有"自由样式空白"问题，非本次回归，见 issues-found.md 新发现 1）。节流代码已确认编入 APK（dex 含 `putThrottledLog`），逻辑为主线程纯时间窗计数。L3 占比数据待自由布局空白修复后补测
- [ ] 3.6 全量回归 `run_e2e.py --tc all`：⏳ 待提权补跑（memuc 需管理员，沿用 run_e2e_admin.bat 模式）

## 4. 文档收尾

- [x] 4.1 issues-found.md 已记录（含白屏新复现路径 + MediaMuxer/嗅探超时两项后续立项）✅
- [x] 4.2 Kotlin 陷阱已沉淀至项目记忆（合成属性遮蔽规律：标识符须恰为 `root` 才触发）✅
- [x] 4.3 `docs/INDEX.md` 与 README 状态已更新 ✅
- [x] 4.4 最终验收 ✅ 2026-09-12 用户验收通过，归档
