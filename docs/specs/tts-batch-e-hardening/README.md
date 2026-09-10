# README.md — TTS 批次E 加固（tts-batch-e-hardening）

> 状态：✅ 已实施（2026-09-10；测试包 legado_miss_app_3.26.091014.apk；单测 42 项全绿含 E5 门面等价+E6 四象限；L1 装机零 FATAL；预存 flaky guardLog×3 全量运行为已知项与本期无关） ｜ 版本：v1.1 ｜ 创建：2026-09-10（v1.1：红队审查 21 项修正，详见 design §8）
> 上游基线：`docs/specs/optimize-tts-engine-phase2/`（期2 定稿）+ `code-review-20260910.md`（63 条全量审查，批次A-D 已修复推送 5a10b01）

## 功能概述

TTS 两次优化后的全量代码审查（2026-09-10）遗留批次E 六项收尾加固，经用户裁决立即实施。六项覆盖三条主线：

- **体验补全**：AI 预生成缓存从"全工程零调用"到真接线（E1）；选角模板资产进备份/恢复链与首装链（E3/E4）。
- **资产保护**：预合成保留名单进程重启后不失效，杜绝非当前章 >10min 产物被批量驱逐（E2）。
- **安全与一致性**：TTS 脚本 Rhino 沙箱分档收紧（E6）；缓存键三件套单源收编（E5）。

全部为存量迭代（Delta），不引入新表/新列/新页面，Room v110 冻结不动。

## 六项能力（E1-E6）

| 编号 | 短名 | 一句话 | 级别 |
|------|------|--------|------|
| E1 | AI 预热接线 | `BaseReadAloudService.newReadAloud` 起播点 fire-and-forget 直调 `AiReadAloudRoleService.ensureCache`（v1.1：不经 ensurePlayableCache 等待封装），AI 分配缓存提前落库（P0-5） | P0 |
| E2 | 名单落盘 | `TtsPrebuildManager.reservedKeys` 持久化到 `cacheDir/tts_prebuild/reserved_keys.json`，进程重启后名单自动回装；登记后立即落盘+runTask 名单/文件联合判定（v1.1）（P1-3） | P1 |
| E3 | 模板备份恢复 | `ttsCastingTemplates.json` 进 `BackupSelectorConfig.allItems`+导出+恢复三通道全生效（v1.1：backupFileNames 为死列表不接入），恢复后失效快照（F-1） | P2 |
| E4 | 模板首装链 | `DefaultData.upVersion()` 旗标接入 `importBuiltinTemplates` + builtin 内容变更覆盖刷新（F-2 / E4b） | P2 |
| E5 | 键因子收编 | 播放端/批量端 engineKey/speedKey/voiceKey 三件套获取与字符串化收进 `TtsCacheKeys` 单源（方向①） | P3 |
| E6 | TTS 脚本档 | `RhinoClassShutter` 新增 TTS 档：`io.legado.app.*` 前缀从"观察放行"改"实拦"（方向⑤） | P2 |

## 关键代码锚点（本设计已逐点核实，行号以 5a10b01 后工作树为准）

| 锚点 | 位置 |
|------|------|
| `ensureCache`（E1 直调入口：内置短路/DB 命中跳过，对 RUNNING 即返回） | `help/ai/AiReadAloudRoleService.kt:411`（短路 :417-424；外层等待封装 ensurePlayableCache :377 预热不经过） |
| 起播挂点 `paragraphStartPos = pos` | `service/BaseReadAloudService.kt:397`（`newReadAloud`:361 起，`execute(IO)` 块内） |
| `reservedKeys` 登记点 / 消费点 | `prebuild/TtsPrebuildManager.kt:365` / `service/HttpReadAloudService.kt:631-642` |
| 键单源两调用点 | `HttpReadAloudService.kt:534-552` / `TtsPrebuildManager.kt:296-303` |
| 备份选择项注册 / 导出 / 恢复（v1.1：backupFileNames 死列表不接入） | `help/storage/BackupSelectorConfig.kt:25-55`（httpTTS 项 :41）/ `Backup.kt:472-474` / `Restore.kt:163-165` |
| 内置模板幂等导入 / 兜底调用 | `casting/TtsCastingStore.kt:230-256` / `service/TTSReadAloudService.kt:87-98`（assets :92） |
| 沙箱判定链 / TTS 脚本策略切换点 | `modules/rhino/.../RhinoClassShutter.kt:240-259` / `help/readaloud/script/TtsScriptEngineClient.kt:115` |
| Coroutine 取消守卫（error 回调前重抛取消） | `help/coroutine/Coroutine.kt:182-183` |

## 六项与需求/决策映射（跨文档导航）

| 编号 | spec 需求 | design 详设 | 架构决策 | tasks 段 |
|------|-----------|-------------|----------|----------|
| E1 | R1/R2 | §3.1 | AD-E-01 | 2.1-2.2 / 3.3.1 |
| E2 | R3/R4 | §3.2 | AD-E-02 | 2.3-2.6 / 3.3.2 |
| E3 | R5 | §3.3 | AD-E-03 | 2.7-2.8 / 3.3.3 |
| E4 | R6 | §3.4 | AD-E-04 | 2.9-2.12 / 3.3.4 |
| E5 | R7 | §3.5 | AD-E-05 | 2.13-2.16 / 3.3.5 |
| E6 | R8 | §3.6 | AD-E-06 | 2.17-2.20 / 3.3.6 |

## 实施顺序建议

六项彼此独立可并行，但建议串行序：**E1（P0）→ E2（P1）→ E6 → E3 → E4 → E5**。理由：E5 与 E2 触碰同一文件（TtsPrebuildManager.kt），E2 先行落稳再收编键因子可减少同文件合并冲突；E6 单独成段（modules/rhino 独立 module）便于隔离回归；E3/E4 同链（备份+首装）收尾连验。所有实施任务以 tasks.md 为准，验证证据统一落 issues-found.md。

## 风险提示与回滚

- 最高风险项为 **E6 沙箱收紧**（第三方脚本生态行为变化）与 **E5 键收编**（缓存键漂移即存量失配）——两者均有 JVM 单测锁定（TtsClassShutterPolicyTest 四象限 / TtsCacheKeysTest 逐字节断言），实施时先写测试后改码。
- E2 落盘失败、E3 恢复失败均设计为静默降级（AppLog 留痕+既有兜底），无新增崩溃面。
- 回滚方式：六项无 schema/无迁移/无 ProtocolBuffer 变更，单 commit 粒度 revert 即可整体回退。

## 文档索引

| 文档 | 内容 |
|------|------|
| [spec.md](./spec.md) | Intent/Scope（含不做什么）/Approach（含 Alternatives）/R1-R8 需求与场景/验收标准 |
| [design.md](./design.md) | 总体架构（mermaid：E2 落盘数据流+E6 判定链）/3.1-3.6 子系统详设/AD-E-01~06/边界异常矩阵/File Changes/§8 红队审查记录 |
| [tasks.md](./tasks.md) | 准备/核心实现/验证/收尾四段任务清单（★ 验证标准） |
| 审查输入 | [../optimize-tts-engine-phase2/code-review-20260910.md](../optimize-tts-engine-phase2/code-review-20260910.md)（E 批次原始条目） |

## 约束基线（继承自上游）

- **Room v110 冻结**：零新表零新列零 bump，E2 落盘走 cacheDir JSON（先例 `ThemePackageManager.kt:602` / `EpubCoreDiskCache.kt:60`）。
- **KEY_VERSION=v3 冻结**：E5 纯收编不换算法，两调用点产出与现行完全逐字节一致。
- **modules/rhino 不依赖 app 层**：E6 复用 `ClassAccessObserver` 回调（`RhinoClassShutter.kt:64-70`）。
- **runCatching 吞取消铁律**：E1 取消安全直接复用 `Coroutine` 封装守卫（:182-183 已放行取消），不新增 runCatching 包裹。

## 变更日志

- v1.1（2026-09-10）：红队两路审查 32 条发现裁决修正（21 项，全记录见 design §8）。关键项：E1 预热入口改直调 `ensureCache`（ensurePlayableCache 等待语义为负资产）+取消残留 RUNNING 行清理+keepAlive 前台保活分档抑制+EnsureResult 按 status 留痕（R2-1/R2-2/R2-9/R3-9）；E3 注册位改 `BackupSelectorConfig.allItems`（backupFileNames 实测为全工程无消费方死列表，不接入）（R1-01）；E2 写时机取消防抖改立即同步落盘+回装改异步+runTask 幂等判定改名单/文件联合判定防 DONE 虚报（R3-1/R5-1/R2-4）；E4b 比对字段扩为全字段（R3-6）；E4 门控三态表述修正（R1-03）；E6 拦截范围措辞收敛（非 app 前缀维持放行现状，R2-6）；AD 编号统一 AD-E-01~06（R4-01）；三处行号修正（R4-02）；PrebuildTask.engineKey 死字段删除路径（R4-04）；沙箱纵深锚点两条（R5-3/R5-5）；removeReserved 死 API 定性+另立项核实（R2-3/R1-05）；播放侧消费链单独立项明示（R1-02）；删书孤儿产物场景登记（R1-04）。
- v1.0（2026-09-10）：初稿。基于批次A-D 修复后的源码实况完成 E1-E6 六项设计；`invalidateSnapshot` 为 public、`TtsCastingTemplateDao.insert` 为 REPLACE、`LocalConfig.isLastVersion` 首参为 per-key 独立计数器三项探索期存疑已本轮核实定型。
