# issues-found — 内置视频播放器 DLNA/UPnP 投屏

> 记录本 spec 在探索、验证、实施过程中发现的**环境性/真机性**问题。
> 命名：IF-序号 ｜ 状态：🔴 待处置 / 🟡 已知规避 / ✅ 已解决
> 规范依据：`docs/project-rules/real-device-test-reuse.md`

---

## IF-1：Windows SSDPSRV 占用 UDP 1900，假渲染器"能 bind 却收不到包" 🔴 待规避（L2 前置）

**发现时间**：2026-09-12（构建假渲染器时，`--selftest` 自检暴露）

**现象**：假渲染器 `bind(("", 1900))` **返回成功**并打印"SSDP 监听中"，但从本机向 `127.0.0.1:1900` 发 M-SEARCH 后 **3 秒超时，一条都没收到**，日志中连 `[SSDP] M-SEARCH` 都没有。换 `--ssdp-port 1901` 后立即正常收发。

**根因**（已实证）：

```
Get-Service SSDPSRV          → Status=Running StartType=Manual
netstat -ano -p UDP | :1900  → UDP 127.0.0.1:1900 / 192.168.64.1:1900 /
                               192.168.153.1:1900 / 10.2.152.167:1900 /
                               2.0.2.201:1900   全部 pid=27476
Get-Process -Id 27476        → svchost
```

Windows 自带的 **SSDP Discovery 服务（SSDPSRV）** 已在所有接口上占用 UDP 1900。我们的进程靠 `SO_REUSEADDR` 仍能 bind 成功，但**入站报文被 svchost 抢走**，形成"监听正常但永远收不到"的假象。

**影响**：若未察觉，L2 会表现为"App 发现不了设备"，极易误判为 App 侧 SSDP 实现有 bug，浪费大量排查时间。

**处置**：

| 场景 | 做法 |
|------|------|
| 本机自检（`--selftest`） | 工具已默认改用 **1901**，不受影响 |
| **真机联调（必须 1900）** | 以管理员身份 `net stop SSDPSRV`；测试完成后 `net start SSDPSRV` 恢复 |
| 不停止服务时 | 只能被动依赖 Windows 自己的 UPnP 设备被发现，**无法验证我们的假渲染器** |

**已落地**：假渲染器新增 `check_ssdp_ownership()` 收包自检 —— 启动后主动发一包 M-SEARCH 验证自己能否收到，失败时打印上述处置指引（`ai_tests/scripts/dlna_fake_renderer.py`）。**不再依赖人工记得这件事。**

---

## IF-2：本机存在 4 个非回环 IPv4 地址（实证 AD-13 的必要性） 🟡 已知规避

**发现时间**：2026-09-12

**现象**：`netstat` 显示本机在以下地址上都有 UDP 1900 绑定：

```
2.0.2.201        （疑似虚拟网卡/异网段）
10.2.152.167     （企业/校园网段）
192.168.64.1     （虚拟网卡，典型 Hyper-V/VM 网段）
192.168.153.1    （虚拟网卡，典型 Hyper-V/VM 网段）
```

**意义**：这**直接证实了红队 P0-1 / AD-13 的真实性**——`NetworkUtils.getLocalIPAddress()` 会返回全部这些地址且无排序保证。若照既有 `WebService.kt:104` 的 `.first()` 写法取地址，投递给电视的很可能是一个它根本路由不到的虚拟网卡地址，投屏 100% 失败且无任何报错。

**处置**：AD-13 的三级选择策略（活动网络优先 → 接口名匹配 → 回退 + UI 提示）与 `CastNetworkHelperTest` 必须落地；**验证时本机多网卡环境本身就是最好的测试用例**。

---

## IF-3：默认 Python 出口 IP 未必是 Wi-Fi 地址 🟡 已知规避

**发现时间**：2026-09-12

`--advertise-ip` 未指定时，假渲染器用「连 8.8.8.8 选出口网卡」的方式探测本机 IP。在上述多网卡机器上，探测结果取决于路由表，**未必是 Wi-Fi 地址**，会导致 LOCATION 指向一个手机连不上的地址。

**处置**：真机联调时**必须显式指定** `--advertise-ip <PC 的 Wi-Fi 地址>`，以 `ipconfig` 中 WLAN 适配器的地址为准。工具已把该值打印在启动横幅中，便于核对。

---

## IF-4：会话内 Gradle 构建在 2m02s 被外部终止（与代码无关，已用 stash 二分证明） 🔴 待规避（最终编译需用户本地跑）

**发现时间**：2026-09-13（DLNA 投屏实施阶段）

**现象**：`testAppDebugUnitTest` / `compileAppDebugKotlin` / `build-legado.bat` / `--no-daemon` / `--info` 各种姿势**全部**在 `Duration: 2m 2s` 静默死亡：
- 构建日志（含 `--info`）停在 `:app:kspAppDebugKotlin`，**无任何 `e:` 错误、无 `BUILD FAILED`、无堆栈**
- Gradle daemon 自身日志（`F:\gh\daemon\8.14.4\daemon-*.out.log`）同样戛然而止
- **无 hs_err、无 `.hprof`**（已开 `-XX:+HeapDumpOnOutOfMemoryError`）、Windows 应用事件日志无 Error

**排除项（逐一实证）**：
| 假设 | 排除证据 |
|------|---------|
| 我的新代码导致编译器崩溃 | **决定性**：`git stash push -u` 把全部改动暂存到干净树后，单独跑 `kspAppDebugKotlin` **同样在 2m02s 死** |
| OOM | 空闲内存 9.6G/32G；且无 `.hprof` 产物 |
| Kotlin daemon 缓存损坏 | 已按 §4.6 删除 `%LOCALAPPDATA%\kotlin\daemon` 并 `--stop`，仍死 |
| 进程入口问题 | `build-legado.bat`（规范入口，自动清场）同样死 |
| 输出捕获方式 | `Out-File -Width 4096` / `Tee-Object` / `--no-daemon` 各种组合均同 |

**结论**：本会话的工具进程树在 ~2 分钟被外部整体终止（Gradle client 连带其 daemon）。这与既有结论"**重流程必须在用户原生终端跑，禁入会话沙箱**"（publish.bat IF-5）同源，现扩展到 **Gradle 构建**。

**判别要点（供未来会话快速定性）**：
1. 构建日志停在某个任务中途、无任何错误输出 → 先怀疑**外部终止**，不要去修代码
2. 验证手段：`git stash push -u` 暂存全部改动 → 跑同一任务 → 若同样死，即环境问题（验证后 `git stash pop` 恢复）
3. **日志截断陷阱**：PowerShell 会把 gradle 输出按 ~115 字符换行导致 `e:` 错误正文截断，**必须 `| Out-File -Width 4096`**；且 `Out-File` 缓冲到进程结束才落盘，中途看不到进度

**处置**：最终编译在**用户原生终端**执行 `build-legado.bat`（测试包）——该入口自动完成环境变量、Kotlin daemon 缓存清理、transforms 清理、daemon 管理、APK 归档。

**附带**：二分过程中 stash@{0}（`dlna-wip-bisect`）在 pop 时保留了副本（git 行为），所有文件已完整恢复（dlna 包 16 个 .kt、0 冲突、无冲突标记）。该 stash 可作为额外备份保留，或在确认编译通过后 `git stash drop stash@{0}`。

---

## IF-5：MEmu NAT 吞掉 SSDP 单播响应——组播 M-SEARCH 能过、200 OK 回不来 🔴 已定性（环境限制，非 App 缺陷）

**发现时间**：2026-09-13（L2-b MEmu NAT 联调）

**现象**：
- 假渲染器收到 App 的 M-SEARCH（组播穿透 MEmu NAT 成功）并正确回 200 OK；
- App 端 AppLog：`DlnaCast SSDP 主发现无结果，回退 ssdp:all 重试` → 面板落「未发现可投屏设备」。

**根因**（定性）：假渲染器收到的 M-SEARCH 源地址是 **NAT 改写后的宿主 WLAN IP**（10.2.152.167:p，非 guest 192.168.232.2）。SSDP 响应按源地址回传 → 回到宿主自身。标准 NAT 引擎**不为组播出站流建立 UDP 映射**（组播不可 NAT），该响应无映射可翻译 → 丢弃。这是 SSDP 过 NAT 的结构性问题，与 App 实现无关。

**App 侧行为验证（全部正确）**：
| 项 | 结果 |
|----|------|
| M-SEARCH 组播出站（含 MulticastLock） | ✅ 假渲染器实收 2 条（ST=upnp:rootdevice） |
| 4s 收集窗超时 → `ssdp:all` 兜底重试 | ✅ AppLog 实证 |
| 无设备 → 面板「未发现可投屏设备」态 | ✅ 截图 ai_tests/logs/dbg1.png |
| DlnaCastService 前台服务 + 常驻通知 | ✅ 通知栏「投屏」实证 |
| 菜单「投屏」入口双门控 + 单 URL 直启 | ✅ uiautomator2 驱动通过 |

**结论**：SSDP 发现 → SOAP 投递 → CASTING 的完整链路在 MEmu NAT 下**无法端到端验证**（hairpin 断），须 L2-c（真机 + PC 同 Wi-Fi，同 L2 无 NAT）补验。真机复用脚本已就绪：`ai_tests/scripts/l2_verify_dlna_cast.py --fake-log <渲染器stdout>`（前置：`net stop SSDPSRV`，测后恢复）。

**L2-b 已验证项与缺口清单**：V1 直启 ✅ / V2 入口门控 ✅ / V3 M-SEARCH 出站 ✅ / V4 发现列表 ❌（NAT）/ V5 SOAP 投递 ⛔ 依赖 V4 / V6 状态条 ⛔ 依赖 V5 / V7 结束投屏 ⛔ 依赖 V5。

---

## 已排除（查过但没问题）

- `192.168.64.1` / `192.168.153.1` 这类 `.1` 结尾地址为宿主虚拟网卡网关，非 Wi-Fi，**不应**作为通告 IP（已由 IF-3 的处置覆盖）。
- 假渲染器把 `Accept-Encoding: gzip` 写进拉流请求是**故意的**——用于验证 App 代理是否按 design 铁律剥除该头；看到该头不代表工具有问题。
