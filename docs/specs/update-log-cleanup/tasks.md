# tasks — update-log-cleanup

- [x] 1.1 Explore：确认三个消费方（About 页 / 启动弹窗 / publish_release.py 提取逻辑）与格式约束
- [x] 1.2 全文通读 1647 行，建立按天合并的去重映射（125 批 → 70 天）
- [x] 2.1 重写 2026/09/07–09/12（批次段，压缩比最高）
- [x] 2.2 重写 2026/08/16–09/06（Compose 界面焕新段，系列条目压缩）
- [x] 2.3 重写 2026/07/04–08/15（网络引擎/视频播放器/图片浏览器基建段）
- [x] 3.1 拼装校验：`**YYYY/MM/DD` 每日期唯一、cronet 头保留、体积达标
- [x] 3.2 敏感词扫描（源名/域名/URL/cookie 代号合规）
- [x] 4.1 version-delivery-sync.md 同步"同日合并+分节格式"约定
- [x] 4.2 docs/INDEX.md 登记 spec
