# 批次 8：设备可信与卸载防护

状态：`CONNECTED`（Android 客户端），`PENDING`（Device Registry），`NOT VERIFIED`（真机取证）

## 目标和边界

本批次建立安装级设备身份、Android Keystore 设备证明密钥（DAK）、challenge 证据、客户端风险状态和账号 Vault 门禁。它不宣称普通
Android App 可以阻止系统卸载，也不把客户端自检结果冒充服务端证明结论。

端到端 `TRUSTED` 必须由独立 Device Registry 校验证书链、Android Key Attestation 扩展、App 签名、Verified Boot、补丁级别、challenge
和证据签名后产生。当前仓库尚无 Device Registry API，因此真实实现只会进入 `NOT_ENROLLED`、`PENDING`、`INTEGRITY_WARNING` 或
`LOST`，不会自行写入 `TRUSTED`。

## 安装身份和 DAK

安装身份由批次 6 的 256 位 installation ID 与不可导出 DRK 继续承担。首次获得服务端 challenge 时创建独立 EC P-256 DAK：

* 使用 `AndroidKeyStore`，只允许 `SHA256withECDSA` 签名；
* Android 9 及以上优先请求 StrongBox，明确不可用时退回系统 Keystore；
* 创建时写入 attestation challenge，并导出 DER 证书链；
* DAK alias 绑定 installation ID 摘要，重装的新 installation ID 不会复用旧 alias；
* 每次刷新对包含 challenge 摘要、安装 ID、DAK 公钥、DRK/DAK 安全等级、包名、版本、首次安装时间、App 签名摘要和风险信号的规范二进制
  statement 签名；
* 证书链和签名只作为待服务器验证的证据，不在客户端判定 Root of Trust。

证据和证书链只在调用期间返回并保留于进程内；持久记录只保存摘要、版本、状态和时间，不保存私钥、challenge 或服务器令牌。

## 状态和本地门禁

持久状态位于 `noBackupFilesDir/secure/v1/device-root/device-trust.yzdt`，使用 magic、版本、长度边界、SHA-256 完整性校验和原子替换。

状态语义：

* `NOT_ENROLLED`：安装身份和 DRK 存在，尚未创建服务器 challenge 证据；
* `PENDING`：DAK 证据已生成，等待 Device Registry；
* `TRUSTED`：仅预留给通过服务端验证的结果；
* `INTEGRITY_WARNING`：检测到阻断级本地风险；
* `REVOKED/LOST/STALE`：远程吊销、DAK 丢失或信任过期，禁止打开账号 Vault。

阻断级信号包括 Release 调试器、常见 Root 二进制、App 签名不可用或签名变化。Debuggable/test-keys 和硬件证明链不可用会进入证据风险集合，
但默认策略下不单独锁死兼容设备；严格硬件开关要求服务端状态为 `TRUSTED` 且 DRK/DAK 均为 TEE 或 StrongBox。

冷启动在账号 Vault 初始化之前验证可信记录和 DAK。前后台协调器把信任状态并入解锁条件；出现阻断状态、低内存或后台切换时释放账号 AVK 和
进程内证明材料。

## 卸载、清除数据和恢复

卸载安全不依赖卸载广播或退出回调：

```text
系统删除 App 私有 no-backup 目录中的 installation ID、可信记录、AVK/domain/DEK 信封
+ 系统删除 Android Keystore 中旧安装 DRK 和 DAK
= 恢复出的旧密文缺少完整解密链和已注册设备私钥
```

App Manifest 设置 `allowBackup=false`，旧版备份规则、Android 12+ cloud backup 和 device-transfer 均排除 root、file、database、sharedpref
和 external 域。复制旧 no-backup 记录但缺少旧 DAK 会持久进入 `LOST`；复制 DRK 信封但缺少旧 Keystore DRK 会更早失败关闭。

重新安装必须生成新 installation ID、DRK 和 DAK，并在 Device Registry 作为新设备审批。服务器只能通过心跳/推送/KMS 活动把长期离线设备标记
为 stale，不能在卸载瞬间可靠区分卸载、关机和断网。

系统相册、下载目录、第三方分享目标、截图、录屏、外部录音以及 Root 后在解锁状态抓取的进程内明文不受卸载断链自动保护，仍属于 DLP、设备
管理和后续内容密钥/KMS 范围。

## 验证

JVM 测试覆盖：等待服务端而不自信任、challenge/statement 绑定、进程重启、Root 风险、App 签名变化、复制记录缺 DAK、LOST 墓碑和销毁顺序。
安全基线同时锁定 DAK 算法、证书链、严格 Vault 门禁、启动顺序和完整备份排除。

仍需至少两类国产 Android 真机验证 StrongBox/TEE 证书链、OEM 证明兼容、Root/Magisk、调试器、升级、清除数据、卸载重装、D2D、OEM 换机、
Root 文件系统镜像及 AFU/BFU。只有接入 Device Registry 并完成这些验收后，才能把端到端设备信任标记为 `VERIFIED`。
