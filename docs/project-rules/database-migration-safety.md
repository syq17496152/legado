# 数据库升级安全规范

> 数据库 migration 修改的强制安全规则，防止覆盖安装失败导致用户数据丢失或 App 崩溃。

## 触发场景

- 任何涉及 Room `@Database` version 变更的任务
- 任何修改 `@DatabaseView` SQL 的任务
- 任何修改实体字段（新增/删除/类型变更）的任务
- 任何新增 migration 的任务

## 核心规则

### R0: 迁移摘除三查铁律（2026-09-09 新增，铁证：109→110 覆盖安装闪退）

**背景**：migration_109_110 曾在排查期被临时从 `DatabaseMigrations.migrations` 数组摘除（注释"二分排查：暂摘"），随后被正式提交带进主干——AppDatabase version=110 但迁移链断链，**所有 v109 老库用户覆盖安装即闪退**（"A migration from 109 to 110 was required but not found"），且编译/单测/模拟器全新安装全绿无法发现（全新安装不触发 migration）。

**三查铁律（任何任务结束时逐条过）**：
1. **摘必挂**：因排查/调试临时摘除 migration 数组条目时，必须在**同一会话内**回挂；禁止跨提交携带"暂摘"状态（`git log -S "暂摘\|二分"` 可审计）
2. **版本=链长**：`AppDatabase.version` 每次递增 N，`migrations` 数组必须存在对应 `migration_(version-1)_version`；提交前 Grep 校验：
   ```bash
   # version=110 时应命中 migration_109_110（两处：定义 + 数组引用）
   rg "migration_109_110" app/src/main/java/io/legado/app/data/DatabaseMigrations.kt  # 期望 ≥2 处
   ```
3. **旧库实测**：覆盖安装验证必须用**老版本包装机后再装新包**（或 adb 注入 vN-1 的库），全新安装永远验证不了 migration 缺失；模拟器覆盖安装流程见 ai_e2e_testing_workflow

**自查口诀**：改了 version 却没跑通老库升级 = 必闪退；"编译绿+新装绿"不等于"覆盖安装绿"。

### R1: DatabaseView 修改必须 DROP+CREATE 重建

修改 `@DatabaseView` 注解的 SQL 后，必须在对应的 migration 中执行：

```kotlin
db.execSQL("DROP VIEW IF EXISTS view_name")
db.execSQL("CREATE VIEW view_name AS ...")  // 包含新字段的完整 SQL
```

**反模式**：migration_95_96 只执行 `ALTER TABLE book_sources ADD COLUMN lastHost`，没有 DROP+CREATE 重建 `book_sources_part` view，导致 Room schema 校验发现 view 结构不匹配 @DatabaseView 注解，抛 IllegalStateException（Issue-1）。

**根因**：Room 的 @DatabaseView 是基于 SQL 查询的虚拟表，修改实体字段后 view 的 SQL 也需要更新，但 ALTER TABLE 不会自动更新 view。Room 在 migration 完成后会校验 view 的 schema 是否匹配 @DatabaseView 注解，不匹配则抛异常。

### R2: migration 必须 runCatching 包裹+日志

每个 migration 操作必须用 `kotlin.runCatching` 包裹，并记录日志：

```kotlin
private val migration_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        kotlin.runCatching {
            db.execSQL("...")
        }.onFailure {
            AppLog.put("migration_X_Y failed", it)
        }
    }
}
```

### R3: version 必须递增，不可降级

- 新 version 必须大于旧 version
- 不可删除已发布的 migration
- 不可修改已发布的 migration（version 已升的不会重跑）

### R4: migration 不可重复执行

Room migration 是单向的，version 已升不会重跑。如果已发布的 migration 有 bug，必须新增 migration_X_(X+1) 修复，不可修改原 migration。

**反模式**：migration_95_96 有 bug（view 未重建），用户已升级到 version=96。重新安装 version=96 的新包不会触发 migration（version 相同），导致 bug 永远存在。正确做法是 version 96→97，新增 migration_96_97 强制重建 view（Issue-1）。

### R5: 覆盖安装兼容性测试要求

任何涉及数据库 version 变更的任务，必须真机验证覆盖安装：

1. 安装旧版本（version=N-1）
2. 导入测试数据（确保有数据）
3. 覆盖安装新版本（version=N）
4. 验证 App 正常启动，数据保留
5. logcat 确认无 `IllegalStateException: Migration didn't properly handle`

### R6: Room schema 校验是运行时的

Room 的 schema 校验是运行时的，编译期不会发现 view 未重建的问题。只有 migration 执行后才抛异常。

**强制规则**：涉及 @DatabaseView 修改的任务，必须真机验证 migration 执行后的 schema 校验通过。

## 修改实体字段的完整流程

修改实体字段（如新增 `lastHost`）时，必须同步完成：

1. **实体类**：`data class BookSource(..., val lastHost: String? = null)`
2. **@DatabaseView**：更新 view 的 SQL 包含新字段
3. **migration**：
   ```kotlin
   db.execSQL("ALTER TABLE book_sources ADD COLUMN lastHost TEXT")
   db.execSQL("DROP VIEW IF EXISTS book_sources_part")
   db.execSQL("CREATE VIEW book_sources_part AS ... (含lastHost)")
   ```
4. **@Database version**：递增 version
5. **DatabaseMigrations.kt**：注册新 migration 到 migrations 数组
6. **DAO**：如有查询用到新字段，更新 DAO
7. **真机验证**：覆盖安装测试

## 反模式汇总

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 只 ALTER TABLE 不 DROP+CREATE view | IllegalStateException 崩溃 | 同步 DROP+CREATE view |
| 修改已发布的 migration | 用户已升级不会重跑 | 新增 migration 修复 |
| 用 fallbackToDestructiveMigration | 清空用户全部数据 | version+1 新增 migration |
| 让用户卸载重装 | 规避问题不是解决问题 | 修复 migration 让覆盖安装成功 |
| 编译通过就认为 migration 正确 | 运行时 schema 校验才发现问题 | 真机验证 migration 执行 |

## 何时必须加载本规范

- 数据库 version 变更任务
- 修改 @DatabaseView SQL 任务
- 修改实体字段任务
- 新增 migration 任务
- 覆盖安装失败排查任务
