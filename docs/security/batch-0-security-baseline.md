# 批次 0：工程接入与安全基线

> 历史批次说明：本文保留批次 0 落地时的阶段性边界。当前安全开关、远端签名策略、KMS 和管理控制已完成代码闭环并默认启用；当前验收口径以仓库根目录 `docs/PRODUCTION_SECURITY_ACCEPTANCE.md` 为准。

<!--- TOC -->

* [状态](#状态)
* [已经接入](#已经接入)
* [默认策略](#默认策略)
* [CI 门禁](#ci-门禁)
* [明确未启用](#明确未启用)

<!--- END -->

## 状态

批次 0 已完成工程接入。安全模块和基线服务已经进入 App 的 Metro 图，但真实消息、媒体和数据库路径尚未改写。

状态必须分别记录：

* 模块与 App 依赖：`CONNECTED`；
* 41 项敏感载体目录与责任归属：`CONNECTED`；
* 内置严格基线策略：`CONNECTED`；
* 文件加密：批次 1 已实现真实 `SensitiveFileStore`，业务迁移仍按功能开关分批进行；
* DRK -> 随机 AVK -> 六域密钥 -> 对象 DEK 层级：批次 7 的账号 Vault 核心和 MEDIA 域已 `CONNECTED`，待真机/取证验收；
* DAK/服务端硬件证明、凭据/Matrix Store/数据库实际域迁移、内容 KMS 和远程删除行为：仍为 `SCAFFOLDED`。

## 已经接入

App 直接依赖以下实现模块：

* `security:secure-storage:impl`；
* `security:data-protection:impl`；
* `security:content-protection:impl`。

App Metro 图公开并验证以下安全边界：

* `SecureStorageRoots`；
* `SensitiveAssetCatalog`；
* `DataProtectionPolicyProvider`；
* `ContentEnvelopeValidator`。

开发期安全功能开关当前状态为：

* `security.secure_file_store`：批次 5 cutover 后固定默认开启并标记完成；
* `security.hardware_backed_vault`：默认关闭；该开关要求 Device Registry 已验证的 TEE/StrongBox 设备，不影响批次 7 已接入的账号 Vault 核心和
  MEDIA 域文件密钥底座；
* `security.content_protection`：默认关闭；
* `security.remote_deletion`：默认关闭。

这些开关只用于分批接入和回归验证，不得成为生产用户关闭基础加密的入口。

## 默认策略

`DataProtectionPolicyV1` 是当前稳定策略结构。内置策略为不可过期的本地安全下限：

* 内容保留 24 小时；
* 最大离线 24 小时；
* 通知隐藏正文；
* 禁止导出、剪贴板和外部分享；
* 阻止截屏；
* 硬件信任要求暂为 `NONE`，待第二阶段硬件证明完成后收紧。

当前策略只建立格式和默认值，尚未接入远程签名策略，也尚未改变 UI 行为。

## CI 门禁

`checkSecurityBaseline` 和 GitHub `security_baseline` 任务检查：

* 三个实现模块仍被 App 接入；
* `security.secure_file_store` 保持强制开启，其余三个未完成安全功能开关保持默认关闭；
* 文档清单、运行时目录和责任归属均覆盖 41 项资产；
* 新增静态敏感目录必须登记到 `tools/security/sensitive_paths.json`；
* Release 构建必须把 HTTP BODY 日志固定为 `NONE`；
* BODY 日志不得在其他生产源码中直接启用。

取证测试使用 `security:data-protection:test` 的 `SensitiveDataCanaryFactory` 生成文字、图片、语音、视频、附件、位置、通知和日志标记。

## 明确未启用

批次 0 本身不提供下列保护，后续批次的状态以各自交付文档为准：

* 真实文件加密；
* 硬件 Vault；
* KMS 和内容密钥；
* 远程删除执行；
* 管理端策略下发；
* 截屏、复制、导出行为拦截。

上述能力必须在后续批次接入真实业务路径并完成真机和取证验证后，才能标为 `VERIFIED`。
