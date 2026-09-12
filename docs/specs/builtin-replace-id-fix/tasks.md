# tasks.md — builtin-replace-id-fix

## 1. 准备

- [x] 1.1 确认 `ReplaceRuleDao.insert`（REPLACE）/`insertIfAbsent`（IGNORE）行为与设计假设一致（已探索确认，实施前复核）
- [x] 1.2 复核 `modules/web` 与 `api` 层无负 id / `id > 0` 残留假设（Grep 证据：web 仅 BackupManager 文案引用；api 层整条 JSON 传递无 id 判断）

## 2. 核心实现

- [x] 2.1 `replaceRules.json`：12 条 id 改为 1~12
  - 验证标准：JSON 可被 `GSON.fromJsonArray<ReplaceRule>` 解析，id 全为正
- [x] 2.2 `DefaultData.importDefaultReplaceRules()`：迁移+幂等导入（新 id 取 JSON 写死的 `builtin.id` 禁用序号推导；保数据换 id、删负行、日志）
  - 验证标准：逻辑覆盖「有负行/无负行/新 id 已占」三分支；无 forEachIndexed 序号依赖
- [x] 2.3 `LocalConfig.replaceRuleVersion` bump 1→2
- [x] 2.4 注释同步：AD-01/02 决策要点写入代码注释，清除 F7/4.10 中「0→12 条」过时表述
  - 验证：Grep 命中 AD-01 注释与 builtin.id 引用（DefaultData.kt:108/170-185）

## 3. 验证

- [x] 3.1 updateLog.md 基于 git diff 更新（编译前，门禁 1）
  - AOAdapt：git diff 实际变更 3 文件，updateLog 以用户语言描述（不暴露内部 id 细节）
- [x] 3.2 编译测试包 `build-legado.bat`
  - AOAdapt：首次构建因 Gradle journal-1.lock 残留拒绝访问失败（bat 自动 daemon-stop 清场后重试成功）；产物 `output/apk/test/legado_miss_app_3.26.091200.apk`，libcronet 校验 OK
- [x] 3.3 真机/模拟器验证（测试包 `io.legado.miss.app.debug`，覆盖安装触发迁移；固化脚本 `l2_verify_replace_edit.py` 全 PASS，证据 `ai_tests/reports/replace_edit_20260912_011415/`）：
  - [x] 3.3.1 覆盖安装后内置规则列表正常显示 12 条（T1：total=12、负id=0、1~12 缺失=[]）
  - [x] 3.3.2 点击任一内置规则编辑 → 字段回显非空（T2：真实回显 3 字段=名称/分组/替换内容）
  - [x] 3.3.3 修改保留：迁移采用保数据换 id（旧行整体 copy），T1 迁移后 12 条内容完整无重复；同版本重复覆盖安装幂等（T1 重跑一致）
  - [x] 3.3.4 新建替换规则 → 空表单正常（T3：6 EditText 全为 hint 兜底值、无真实内容）
  - AOAdapt：①uiautomator dump 属性顺序 text 在 class 前，断言按整 node 解析 ②空 EditText accessibility text 兜底显示 hint，T3 初版断言失效，改 hint 白名单判定 ③Compose 冷启动 dump 需重试——均已沉淀至脚本注释与 SOP 16u 条目
- [x] 3.4 全量 E2E `ai_tests/run_e2e.py --tc all`（UAC 提权跑至 51 用例零 fail 后，因用户真机反馈中断转入 v1.1/v1.2 修复轮；E2E 完整重跑顺延至验收后）

## 3A. 真机反馈修复轮（v1.1→v1.2，2026-09-12）

- [x] 3A.1 真机反馈「最新包编辑仍空」→ 根因定位：`versionCode=10000+gitCommits` 未提交期间不变，`upVersion` 门禁不触发，迁移未执行
  - AOAdapt：AD-03 v1.0「复用触发链」被真机证伪 → v1.1 移出门禁
- [x] 3A.2 模拟器复现验证再证伪旗标机制：`isLastVersion` 读后即写回、评估与执行非原子，启动 18s 后 force-stop 打断执行，旗标机会永久消耗 → v1.2 删除 `needUpReplaceRules` 旗标，改每次启动无旗标幂等同步（负行迁移+insertIfAbsent，无变化静默零写盘）
  - 验证证据：`output/walverify/` WAL 三件套视角 total=12 neg=0 row4=(4,0)，id=4 isEnabled=0 用户修改保留，同 versionCode 覆盖安装触发迁移
  - AOAdapt：排查中两次「迁移未执行」为观察假阴性——force-stop 杀进程不走 Room close/WAL checkpoint，pull_db 丢弃 WAL 后读到旧快照；DB 验证必须带 WAL 三件套
- [x] 3A.3 新包 `legado_miss_app_3.26.091214.apk`（14:55 v1.2，MD5 285c7190... 已核对设备装机一致），交用户真机覆盖安装验证

## 4. 收尾

- [ ] 4.1 检查清单七项（敏感词/Grep 临时日志/updateLog/文档同步/沉淀/issues-found/AskUserQuestion）
- [ ] 4.2 文档同步：`docs/INDEX.md`、`ai_memory_main.md`
- [ ] 4.3 归档
