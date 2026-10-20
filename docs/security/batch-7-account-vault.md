# 批次 7：账号 Vault

> 当前状态：Session 凭据、Matrix Store/Session SQLCipher 密钥、数据库密钥和保护内容路径已经迁移到 DRK/Vault 信封体系；本文“明确未完成”保留为批次 7 当时的历史记录。当前验收口径以仓库根目录 `docs/PRODUCTION_SECURITY_ACCEPTANCE.md` 为准。

<!--- TOC -->

* [状态](#状态)
* [已经实现](#已经实现)
* [密钥与文件层级](#密钥与文件层级)
* [状态机和生命周期](#状态机和生命周期)
* [媒体对象接入与兼容](#媒体对象接入与兼容)
* [轮换与删除语义](#轮换与删除语义)
* [卸载和恢复边界](#卸载和恢复边界)
* [明确未完成](#明确未完成)
* [验证范围](#验证范围)

<!--- END -->

## 状态

批次 7 已把账号 Vault 核心和真实加密媒体对象链路标记为 `CONNECTED`，但仍未达到完整第二阶段的 `VERIFIED`。

本批次不是只增加接口：账号清单、六个随机域密钥信封、锁定内存状态、Matrix 登录/恢复接入、MEDIA 域对象包装、退出毁钥和旧信封升级均有真实实现。凭据、Matrix Store、普通数据库、缓存和日志尚未迁移到对应域，因此不得将六个域全部宣传为已保护业务数据。

## 已经实现

* `VaultKeyService` 提供 `ABSENT -> PROVISIONING -> LOCKED/UNLOCKED -> ROTATING/DESTROYING -> DESTROYED` 状态机。
* 每个 Matrix 账号和设备组合使用稳定但不含账号明文的 `AccountVaultId`。
* 每个账号创建一个随机 256 位 AVK；AVK 继续由批次 6 的不可导出 DRK 包装。
* 每个账号和版本创建六个独立随机 256 位域密钥：`CREDENTIAL`、`MATRIX_STORE`、`DATABASE`、`MEDIA`、`CACHE`、`LOG`。
* 域密钥只以 AVK 包装的 AES-256-GCM 信封持久化，AAD 绑定账号 Vault、域和版本。
* AVK 仅在 Vault 显式解锁期间以可擦除字节驻留进程内存；`lock`、账号切换、App 进入后台和内存压力会释放缓存副本。
* App 冷启动先完成删除续跑，再启动账号 Vault 协调器，避免把待删除账号重新 provision。
* Matrix 新登录和恢复客户端都会先 provision/unlock 对应账号 Vault；硬件严格开关启用时拒绝软件级 DRK。
* 持久清单和域密钥记录使用原子写、版本头、账号绑定和 SHA-256 完整性校验。

## 密钥与文件层级

```text
Android Keystore DRK（不可导出）
└── noBackup/secure/v1/vault-keys/<vaultHash>.yzvk       随机 AVK 信封
    └── noBackup/secure/v1/account-vaults/<vaultHash>/
        ├── manifest.yzav                                账号哈希、状态、当前版本和策略
        └── domain-keys/
            ├── credential-vN.yzdk
            ├── matrix_store-vN.yzdk
            ├── database-vN.yzdk
            ├── media-vN.yzdk
            ├── cache-vN.yzdk
            └── log-vN.yzdk
                └── objects/.../*.yzkey                  每对象随机 DEK 信封
```

文件名只包含 Vault 哈希、域名和版本，不保存 Matrix 用户 ID。`manifest.yzav` 保存的是账号 ID 的 SHA-256，不保存令牌、密码、正文或原始密钥。

## 状态机和生命周期

* `provision`：校验账号绑定和硬件策略，创建缺失的 AVK/域密钥，最后发布 ACTIVE 清单；重复执行幂等。
* `unlock`：从 DRK 解开 AVK，验证当前版本的全部域信封，然后把 AVK 的受控副本放入内存。
* `lock`：覆盖并移除内存 AVK；之后对象 wrap/unwrap 失败关闭，不会因为“未设置 PIN”自动重载密钥。
* `rotate`：先创建新版本的六个域密钥，再原子切换清单当前版本；旧域版本保留用于读取旧对象。
* `destroy`：先把清单写为不可逆 DESTROYED 墓碑，再删除 AVK、所有域密钥和旧 Keystore alias。

App 生命周期协调器只允许当前账号在 App 可见且应用锁为 `Unlocked` 时解锁。非当前账号、后台、应用锁定和内存回收场景执行 `lock`。该锁定是 App 进程内业务密钥门禁，并不等于 Android Keystore 的每次生物识别授权；后者仍属于后续硬件用户认证增强。

## 媒体对象接入与兼容

新写入的图片、语音、视频、通知媒体和附件对象使用：

```text
AVK -> MEDIA Domain Key(vN) -> Object DEK -> encrypted media chunks
```

对象 `.yzkey` 当前支持三种版本：

1. v1：早期每账号 Android Keystore alias 直接包装对象 DEK；
2. v2：批次 6 的 AVK 直接包装对象 DEK；
3. v3：批次 7 的 MEDIA 域密钥包装对象 DEK，并记录域密钥版本。

v1/v2 对象只能在账号 Vault 已解锁时读取，认证成功后会在返回明文前将 DEK 信封原子升级为 v3。旧密文数据文件不需要重新写入，迁移中断后可重复执行。

## 轮换与删除语义

当前轮换是前向轮换：新对象立即使用新域版本，旧对象仍可通过保留的旧域信封读取。这不等于“旧内容立即失效”。若要撤销历史内容，删除协调器必须删除对应对象 DEK；若要彻底移除旧域版本，后续任务必须先完成对象信封批量重包装或删除，再清理旧 `.yzdk`。

账号退出和本地账号删除仍通过批次 4 删除协调器执行。`SensitiveFileStore.deleteVault` 最终调用账号 Vault `destroy`，因此即使密文文件物理删除失败，AVK 和域密钥删除也先形成密码学不可恢复边界。DESTROYED 清单保留，阻止同一安装静默重新创建相同 Vault。

## 卸载和恢复边界

本批次继续遵循“不依赖卸载回调”的设计：卸载/清除数据会删除 App 私有 `.yzvk/.yzdk/.yzkey`，Android Keystore 旧安装 DRK 同时失去可用绑定；旧磁文的完整文件恢复也缺少解密链。系统备份和 D2D 不得恢复这些 no-backup 记录。

这仍需在 OEM 真机验证。Root/Hook 在 Vault 已解锁并正在解密时可能截取进程明文或密钥操作，本批次不宣称抵御已完全控制的运行中设备。

## 明确未完成

* `CREDENTIAL` 域已生成，但 Session access/refresh token 和 Matrix passphrase 尚未迁移。
* `MATRIX_STORE` 与 `DATABASE` 域已生成，但 Rust Matrix 数据库和 App SQLCipher 数据库尚未执行 shadow migration/重建密钥。
* `CACHE` 与 `LOG` 域已生成，但现有缓存和诊断日志没有启用这些域；Release 日志仍保持禁用优先。
* Android Keystore 用户认证窗口以及 Device Registry 的 StrongBox/TEE 服务端判定、设备审批和远程吊销尚未实现；批次 8 已补客户端 DAK 和
  待验证证据。
* 轮换后的旧域密钥批量重包装/退役任务尚未实现。
* DAK、KMS、内容 CEK、离线许可证和 Matrix 内层信封属于第三阶段。

因此 `security.hardware_backed_vault` 仍保持默认关闭和未完成状态；它表示“强制可信硬件策略”，不再代表账号 Vault 核心是否存在。

## 验证范围

自动验证覆盖：

* 六域 provision 和持久格式；
* lock 后 wrap/unwrap 失败关闭；
* 新进程必须显式 unlock；
* AAD 防替换；
* 域版本轮换及旧对象可读；
* 批次 6 直接 AVK 信封兼容；
* destroy 墓碑和 key material 删除；
* 软件级设备拒绝严格硬件策略；
* MEDIA 域对象往返、账号隔离和 DRK 断链。

仍需至少两类国产 Android 真机覆盖：前后台/锁屏、账号切换、强杀、重启、低内存、StrongBox/TEE 实际等级、退出中断、卸载重装、D2D/备份和 Root 文件系统镜像。JVM 测试和 APK 编译不能把这些状态提升为 `VERIFIED`。
