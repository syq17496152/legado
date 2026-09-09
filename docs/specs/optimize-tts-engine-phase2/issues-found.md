# optimize-tts-engine-phase2 · issues-found

> 真机/模拟器 L2 测试发现的问题与环境限制记录（2026-09-09）

## 测试环境

- MEmu 模拟器 1600x1000（原 720x1280，重启后分辨率变化）
- 测试包 `io.legado.miss.app.debug`（3.26.090912，覆盖安装）
- TTS 引擎：RHVoice 1.18.4（F-Droid APK 安装，`settings put secure tts_default_synth` 设为默认）

## 环境限制（非代码问题，留真机验证）

### E1. 模拟器 DNS 解析故障，RHVoice 语音数据无法下载
- 现象：`ping <IP>` 通但所有域名解析失败（curl exit 6）；RHVoice 语音（BDL）下载卡在"取消"状态
- 已尝试：`setprop net.dns1`（新 Android 无效）、重启 netd（无效）；重启 netd 后 adb 短暂失联，最终重启 MEmu 恢复
- 结论：宿主网络环境（VPN/防火墙）导致 MEmu NAT DNS relay 故障，属环境问题
- 影响：RHVoice 无语音数据（"No voice data"），朗读无真实人声输出（AudioTrack 有帧输出但内容为空/短音）
- 处置：中文实际出声、试听真声、多角色实际分声效果 → 真机验证（真机有系统中文 TTS）

### E2. 模拟器无预装 TTS 引擎
- 现象：`pm list packages | grep tts` 为空、`tts_default_synth=null`，首次朗读 initTts 毫秒级失败
- 结论：这反而验证了 AD-03 降级链（引擎失败→单次回退默认→仍失败→暂停+通知）行为正确
- 处置：安装 RHVoice 后恢复

## 测试过程发现（技术结论）

### T1. `am force-stop` 后进程可能仍存活（复现 1 次）
- 现象：force-stop + sleep 2 后 `pidof` 仍返回存活 pid，导致 SP 注入被运行中进程写回覆盖（本次测试注入 `appTtsEngine`/`ttsCastingActiveId` 前必须 kill -9 确认死透）
- 启示：后续任何 SP/DB 注入类测试，脚本必须 `kill -9` + `pidof` 二次确认，参考 `ai_tests` 注入脚本模式
- 沉淀：建议补入 ai_e2e_testing_workflow 反模式清单（kill -9 + pidof 双确认）

### T2. Windows→adb shell 引号转义链不可靠
- 现象：PowerShell→adb shell→device sh 三层转义，内嵌双引号丢失/产生字面反斜杠，曾把 SP XML 写坏（`<string name= ttsEngine\>`）
- 处置：统一改为宿主 Write 写 sh 脚本 → `adb push` → `sh script` 执行，全部引号留在脚本文件内
- 沉淀：建议补入 ai_e2e_testing_workflow（设备端文本操作一律走脚本推送）

### T3. PreferKey 常量名与 SP 键名不一致陷阱
- `PreferKey.ttsEngine = "appTtsEngine"`（常量名≠键名），首次注入用错键名导致验证空转一轮
- 沉淀：SP 注入前必须先 Grep PreferKey.kt 确认真实键名

## L2 验证结论（模拟器可验证项全部通过）

| 项 | 结论 | 证据（logcat） |
|----|------|----------------|
| 主链：服务启动→initTts→绑定→朗读列表→speak | ✅ | `initTts engine:... generation:1` → `Successfully bound` → `朗读列表大小 30` → AudioTrack 8016 帧 |
| 引擎切换·显式路由 | ✅ | SP 注入 `appTtsEngine=<包名>` → `initTts engine:com.github...rhvoice.android` → 显式绑定 |
| 引擎切换·默认回退 | ✅ | 清空键 → `initTts engine:`（空）→ ENGINE_DEFAULT |
| 降级链（AD-03） | ✅ | 无引擎时：回退默认→仍失败→`初始化失败（含回退），已暂停朗读` |
| 多角色路由 | ✅ | 激活 `builtin_male_female` 后 legacy 标记（朗读列表大小/内容添加完成）消失、无选角解析错误、无崩溃 → speakMultiRole 路径 |
| 数据库迁移 109→110 | ✅ | 3.3 覆盖安装启动冒烟通过 |
| 真实人声/试听真声/预合成 HTTP 链 | ⏸ 真机 | E1 限制（无中文语音数据+DNS 障碍） |

## 残留风险

- R1：多角色逐段驱动（AD-09 onDone 链）只验证到"路径命中+无异常"，逐段推进节奏与翻页判定需真机带语音数据验证
- R2：预合成全链（TtsPrebuildManager→TtsPrebuildService 通知→HTTP 采集→原子提交）单测覆盖租约/账目纯函数，端到端需真机
