# spec — app-update-variant-fix

## Intent
应用内检查更新必须做到：测试包从发布仓获取测试包更新、正式包获取正式包、共存包获取共存包，GitHub 兜底；"已是最新"静默，网络错误才报"更新失败"。发布侧（publish_release.py / 用户审批发版）不在本次范围。

## Scope
- **改**：`AppReleaseInfo.kt`（Asset/GiteeAsset 变体识别 + versionName 解析）
- **改**：`AppUpdate.kt`（PreferredAppUpdate"已是最新"终态透传）
- **改**：`AppUpdateGitHub.kt`（beta 通道 URL 统一 /releases/latest）
- **不改**：AppUpdateGitee.kt 请求逻辑（URL 本就正确）、publish_release.py、UpdateDialog/下载链路

## Requirements
- R1 三包变体与文件名映射正确：`*app_debug_*`→BETA_RELEASE、`*legacy_app_*`→BETA_RELEASEA、`*miss_app_{v}.apk`→OFFICIAL；旧命名 `releaseA/releaseS/release` 兼容保留
- R2 versionName 从资产文件名正确解析为 `3.XX.YYMMDD?HH` 格式并与本地 versionName 可比
- R3 Gitee 返回"已是最新版本"时不降级 GitHub（终态静默）；Gitee 网络错误才走 GitHub 兜底；GitHub 也失败才报错
- R4 不再请求不存在的 `releases/tags/beta`
- R5 对 090820 真实 release JSON 的解析结果：三个 asset 分别映射为 测试/正式/共存，versionName=3.26.090820

## Scenarios
### Scenario: 测试包本地已是最新
- WHEN 测试包（BETA_RELEASE）检查更新，Gitee latest=090820 且本地版本更新
- THEN Gitee 抛"已是最新版本"→ PreferredAppUpdate 上抛 → isLatestVersionError → 静默，不再触发 GitHub 404

### Scenario: 测试包本地较旧
- WHEN 测试包版本 < release 内 debug asset 版本
- THEN 弹更新对话框，tagName=解析出的版本号，下载链接指向 `legado_miss_app_debug_*.apk`

### Scenario: 正式包检查
- WHEN 正式包（OFFICIAL）检查更新
- THEN 只匹配 `legado_miss_app_{v}.apk`（不含 debug/legacy），不会"更新"成测试包

### Scenario: Gitee 请求失败
- WHEN Gitee 网络错误/限流
- THEN 降级 GitHub /releases/latest 按同名规则解析；两源均失败才报"获取新版本出错"

## Approach
纯解析层修正，无架构变更。变体映射与版本解析收敛为单一函数（internal top-level），Asset 与 GiteeAsset 共用，杜绝两套口径再分叉。

## Drawbacks
- 旧命名 8 位日期（尾部 2 位冗余）靠正则长度判别 dropLast，若未来出现 7 位混合格式会误判——兜底：publish 侧命名已固化为新格式，旧格式仅存在于 090820 前的历史 release，且检查只看最新 release
- GitHub API 匿名限流（403）时测试包仍会弹错误框（与现状一致）——后续可在请求头加 token 或延长检查间隔，本次不动
