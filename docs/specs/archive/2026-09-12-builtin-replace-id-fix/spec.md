# spec.md — builtin-replace-id-fix

## Intent

修复内置替换净化规则点击编辑显示空数据的缺陷：根因是内置规则负数 id（-1~-12）与编辑页「新建」哨兵值（id=-1）及 `id > 0` 查库判断冲突。将内置规则 id 迁移为正数段，并为存量设备提供保留用户修改的数据迁移。

## Scope

**包含**：
- `assets/defaultData/replaceRules.json` 内置规则 id 改为 1~12
- `DefaultData.importDefaultReplaceRules()` 升级为「迁移 + 幂等导入」
- `LocalConfig.replaceRuleVersion` bump 1→2 触发存量设备执行

**不包含**：
- 不修改任何规则的 pattern/replacement 等内容字段
- 不修改 `ReplaceEditViewModel`/`ReplaceEditActivity`（上游逻辑保持）
- 不变更 Room schema（无 DB 版本升级）

## Approach（Delta，基于 F7/4.10）

F7/4.10 的「insertIfAbsent 只追加缺失 id」语义保留，新增一步：若 DB 存在旧负 id 行，则将该行**原内容换新 id** 写入（保留用户修改），再删除负 id 行。

## Requirements

### Requirement: 内置规则使用正数 id
系统 SHALL 将 12 条内置替换净化规则的 id 定义为 1~12 的正数，保证编辑页 `id > 0` 查库判断命中。

#### Scenario: 点击编辑内置规则
- **WHEN** 用户在替换净化列表点击任一内置规则的编辑
- **THEN** 编辑页回显该规则的全部字段（名称/分组/替换内容/替换为/开关等），非空

### Requirement: 存量设备数据迁移
系统 SHALL 在 `replaceRuleVersion` 升级后，将 DB 中已存在的负 id 内置规则行迁移为对应正数 id，且**保留该行的当前内容**（含用户修改过的 pattern、isEnabled 等）。

#### Scenario: 用户修改过的内置规则迁移
- **GIVEN** 用户曾将「行首空白清洗」（旧 id=-4）的 isEnabled 改为 false
- **WHEN** 版本升级触发迁移
- **THEN** DB 中出现 id=4 且 isEnabled=false 的行，id=-4 行被删除

#### Scenario: 迁移幂等
- **WHEN** 迁移逻辑重复执行（再次升级/重装触发）
- **THEN** 不产生重复行，不覆盖新 id 行的当前内容

### Requirement: 新装设备正常导入
系统 SHALL 在无负 id 存量行的设备上按新 id 正常导入内置规则（沿用 insertIfAbsent 语义）。

#### Scenario: 首装导入
- **WHEN** 全新安装触发 `needUpReplaceRules`
- **THEN** 12 条内置规则以 id 1~12 写入 DB

## Scenarios（补充边界）

- 新建规则（编辑页哨兵 id=-1）行为不变：仍创建 id 为 timestamp 的空规则。
- 用户手动删除过某条内置规则：迁移时无负 id 行可迁移，按「insertIfAbsent 追加」语义复活该规则（与 F7/4.10 既有 delta 追加行为一致，不额外引入删除标记）。
