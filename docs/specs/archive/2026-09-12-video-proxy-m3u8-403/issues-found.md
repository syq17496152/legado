# issues-found.md — video-proxy-m3u8-403 真机/环境问题记录

> 记录时间：2026-09-12｜环境：MEmu 模拟器 SDK28（127.0.0.1:21503），测试包 io.legado.miss.app.debug

## 1. Cronet TLS 被「站点B」类 CDN 拒绝（已修复，AD-04）

- **现象**：壳域名（站点A）清单经 Cronet TLS 加载成功，但站点B 域名的 AES-128 密钥/分片请求报 `CronetUrlRequest: net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH`（ERROR_CODE_IO_NETWORK_CONNECTION_FAILED / 2001）。
- **与既有铁证方向相反**：`ExoPlayerHelper.cacheDataSourceFactory` 注释记载的案例是「OkHttp 被某 CDN 重置、Cronet 能过」；本案 CDN 恰好拒 Cronet。两类 CDN 各拒一种 TLS 栈。
- **修复**：新增 `TlsFallbackDataSourceFactory`（Cronet 优先 + TLS 握手失败自动回退 OkHttp），真机验证播放成功（first frame 3094ms，state READY）。
- **遗留**：同主机每次请求仍先试 Cronet（无主机级记忆），被拒时多一次失败握手；如实测开销显著可升级主机级缓存。

## 2. MEmu 模拟器系统 DNS 大面积失效（环境问题，已绕过）

- **现象**：模拟器内 `www.microvirt.com`、站点A/站点B 域名均 `UnknownHostException`；DNS 网关 10.0.2.2 的代理失效。App 内 DoH server#1 可用（爬取链路正常），但 ExoPlayer 数据源（Cronet 系统解析）不受 DoH 覆盖。
- **绕过**：root 下 `mount --bind /data/local/tmp/hosts /system/etc/hosts`（/system 只读不可直接写；bind mount 重启后失效）。**模拟器重启后该修复消失，涉及外网域名的播放类测试需重新写入。**
- **备注**：这是模拟器环境问题，不影响真机；App 层可考虑给 ExoPlayer 数据源接 DoH DNS（另行评估，暂不做）。

## 3. Gradle daemon 在 kaptAppDebugUnitTestKotlin 挂死（新陷阱）

- **现象**：daemon-stop 清场后冷启动全量编译，`kaptAppDebugKotlin` 完成后 daemon 在 `kaptAppDebugUnitTestKotlin` 阶段挂死：CPU 10 秒增量≈0、daemon 日志 48 分钟无输出。
- **处置**：强杀 java 进程 + `gradlew --stop` 后重跑，5 分钟通过（编译产物已缓存）。
- **SOP 建议**：后台跑 Gradle 用 `--console=plain` 直连输出，禁用 `Select-Object -First/-Last` 缓冲管道（既看不见进度，管道关闭还可能截杀进程）；卡死判据 = 两次采样 CPU 增量≈0 且 daemon 日志停止更新。
- **另**：`build-legado.bat` 末尾 `pause` 会卡死非交互后台调用，子命令化调用需注意。

## 4. adb shell 传参会被远端 shell 拆断（测试方法陷阱）

- **现象**：`adb shell am start --es videoUrl <含&的URL>` 中 `&` 被远端 sh 解析，URL 在第一个 `&` 处截断，导致代理壳返回 400（误判为新问题）。
- **正确写法**：`adb shell "am start -n <cmp> --es videoUrl '<url>'"`（整体引号）。
- **教训**：400/403 排查时先核对客户端实际发出的 URL 完整性，再怀疑服务端。
