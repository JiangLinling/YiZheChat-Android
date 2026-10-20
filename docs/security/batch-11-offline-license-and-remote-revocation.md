# 批次 11：离线许可证和远程吊销

## 结论

本批次完成 Android 客户端的有界离线许可证、签名吊销快照和本地密码学吊销执行链。实现遵守以下边界：

* 不在磁盘保存明文 CEK、KmsShare 或 Matrix Room Key；
* 离线许可证只保存设备公钥包装的 KmsShare，并由账号 Vault 的独立 `OFFLINE_LICENSE` 域再次加密；
* 设备离线时不能承诺即时吊销，最大暴露窗口是许可证 `refreshAfter`、许可证 `expiresAt`、内容策略到期三者的最早值；
* 重启、墙钟回拨、次数耗尽、设备失信、Vault 锁定、内容或租约吊销均失败关闭；
* 设备吊销时先持久化不可放宽的设备墓碑，再销毁账号 Vault 和 DeviceShare 私钥；
* `security.content_protection` 已完成并默认开启；真实 KMS、Device Registry、Matrix runtime 或证书 pin 缺失时失败关闭。
* 所有在线和离线 KMS 释放现在都先检查本地/远端永久墓碑；恢复旧 KMS 业务备份不能越过客户端墓碑。完整门槛见 [stage-3-security-gate.md](stage-3-security-gate.md)。

当前实现状态：Android 密码学/协议、生产签发/吊销服务、WorkManager 定时同步和 Matrix 事件映射均 `CONNECTED`；云 KMS/TLS 配置和真机取证验证为 `EXTERNAL_REQUIRED`。

## 持久格式和密钥层级

路径位于 `noBackupFilesDir/secure/v1/offline-licenses/<vault-id-sha256>/`。文件名只包含记录键的 SHA-256，不包含账号、内容 ID 或 KMS reference 明文。

每个 `.yzol` 记录使用独立随机 AES-256 DEK：

```text
DRK -> AVK -> OFFLINE_LICENSE domain key -> record DEK -> license/revocation payload
                                               |
                                               +-> AES-256-GCM + bound AAD
```

AAD 绑定记录类型、Vault ID 摘要和记录键摘要。容器采用有界二进制解析、AES-GCM 认证、`fsync` 和 partial/backup 替换恢复。任何字段、密文、包装密钥、记录类型或所属 Vault 被替换都会认证失败。

许可证记录包含：

* 账号、安装、DeviceShare Key ID、内容、策略、KMS reference 和 immutable context 绑定；
* KMS 签发时间、服务端时间、刷新时间和过期时间；
* boot count、最大使用次数、剩余次数和吊销序列；
* 获取时的 `elapsedRealtime`/墙钟锚以及最近观察到的墙钟；
* 服务端签名和设备包装的 KmsShare。

吊销记录包含单调序列、设备状态、租约/内容永久墓碑集合和本地内容墓碑。新快照不得降低序列，后续快照不能移除已经观察到的墓碑，`ACTIVE` 也不能把本地 `STALE/REVOKED` 放宽。

## 离线释放流程

```text
decrypt request
  -> 要求设备为 Device Registry 已验证的 TRUSTED 且处于 TEE/StrongBox
  -> 到达 refreshAfter 时尝试同步签名吊销快照
  -> 检查设备/内容/租约墓碑
  -> 读取现有许可证；不存在或安全过期才在线申请
  -> 校验账号、安装、Key ID、内容、策略、context、boot、TTL、次数和序列
  -> 用服务端时间 + elapsedRealtime 估算有效时间，同时检测墙钟回拨
  -> 先原子扣减并持久化剩余次数
  -> Android Keystore 解开设备包装 Share
  -> 本次解密完成后上层清零可控 Share/CEK ByteArray
```

“先扣次数再出钥”意味着崩溃可能浪费一次授权，但不会因崩溃额外铸造一次离线使用。KMS 不可达时，只有仍满足全部边界的已签许可证可以继续；签名/绑定/存储认证/信任错误不会被当成普通网络故障降级。

本批次明确不允许：

* 无限期或重启后继续离线；
* 用本地长期万能钥匙替代 KMS；
* 在 SharedPreferences、Room/SQLite、日志或通知中保存 Share/许可证；
* 因设备离线跳过策略、硬件信任、刷新期限或吊销检查。

## 远程吊销流程

客户端调用 `POST /v1/revocations/snapshot`，请求绑定账号 hash、installation ID、客户端已知序列和随机 nonce。响应必须由固定 KMS ECDSA 公钥签名并完整绑定：

* 账号和安装；
* 序列、签发/刷新/过期时间；
* request nonce；
* `ACTIVE`、`STALE` 或 `REVOKED`；
* 完整的 revoked lease/content ID 集合。

处理顺序：

1. HTTPS/pin 和 ECDSA 签名通过；
2. 校验 nonce、账号、安装、序列和有界集合；
3. 将快照与永久墓碑合并并加密落盘；
4. `STALE`：写设备限制状态并锁定账号 Vault；
5. `REVOKED`：写设备 `REVOKED` 墓碑，销毁账号 Vault、DeviceShare 私钥和许可证目录；
6. `ACTIVE`：只更新服务端快照，绝不由客户端自我提升为 `TRUSTED`。

设备墓碑位于账号 Vault 之外，由 DRK 认证，因此在销毁账号 Vault 后仍能阻止本地重新打开。`REVOKED` 是粘性状态；后续 `STALE` 不能把它降级。

内容删除先写本地加密墓碑，再请求 KMS 销毁 Share。本地拒绝不依赖服务器可达；服务器失败仍要重试，不能被报告为远端删除成功。

## 长期离线、卸载和恢复边界

长期离线设备只能使用未过最早截止时间、未过次数且未重启的许可证。到 `refreshAfter` 后如果无法取得有效吊销状态，已有许可证的有效截止仍然生效；不会自动续期。远程吊销实际生效延迟不可能小于设备重新联网或当前离线窗口结束的时间。

卸载/清除数据后 Android 删除 App 私有目录，Keystore 中 installation-bound DRK、DAK、DeviceShare 私钥和本地 Vault 信封断链。许可证文件即使从闪存恢复也缺少账号 Vault 域密钥和 DeviceShare 私钥，不能产生 KmsShare。重装会产生新的 installation ID 和密钥，必须作为新设备重新审批。

本地 root 攻击者若同时回滚完整 no-backup 目录和可用 Keystore 状态，客户端单独无法提供硬件单调计数器级的绝对防回滚；因此服务器吊销序列、短 TTL、强制刷新和生产 Device Registry 是必要边界。已解锁、已 Root/注入、能够读取进程内存的实时攻击不属于“已删除磁盘恢复”承诺。

## KMS 协议版本 2

批次 11 将 KMS 客户端协议提升为版本 2。释放响应新增并签名绑定：

* `account_id_hash`；
* `refresh_after_ms`；
* `revocation_sequence`；
* `maximum_uses`。

Manifest 仅允许配置非秘密参数：最大离线时长、最大使用次数、时钟回拨容忍值、KMS URL、公钥和证书 pin。授权令牌继续由运行时短期授权提供器注入，禁止写入 Manifest 或日志。

Android 端另有不可由 Manifest 放宽的硬上限：离线租约最多 24 小时、单租约最多 10,000 次、时钟回拨容忍最多 5 分钟且不得超过租约时长。严格企业策略推荐 1 小时租约和更短的强制刷新时间；24 小时只是代码拒绝边界，不是推荐默认值。

协议升级不向后兼容 v1 响应；旧响应必须失败关闭，不能静默忽略新增约束。

## 已执行验证

自动化测试覆盖：

* 许可证跨存储实例恢复，磁盘密文不包含内容 ID 或设备包装 Share 明文；
* `.yzol` 篡改认证失败；
* 离线复用、使用次数先扣减和次数耗尽；
* boot count 变化和墙钟回拨失效；
* 签名 bounded lease 和签名吊销快照协议；
* 吊销序列不能回退、墓碑只能增加；
* 设备 `REVOKED` 写入后账号 Vault、DeviceShare 私钥和许可证被销毁；
* 真实设备信任持久记录的 `STALE/REVOKED` 限制以及重启后粘性。

## 生产启用前必须完成

以下事项不在当前 Android 仓库内，缺一项都不能宣称批次 11 生产闭环：

* KMS/HSM 实现协议 v2、不可回退墓碑和旧备份恢复重放；
* Device Registry 验证 Android attestation/Verified Boot/App 签名并发布已审批 DeviceShare 公钥；
* 使用推送唤醒和 WorkManager/登录态调度调用 `RemoteRevocationService.synchronize`；
* 服务器以单调序列返回完整吊销集合，并对审计、授权和 KMS 数据库做权限隔离；
* 把 Matrix 收发/渲染真正映射到 `ContentProtectionService`，完成批次 10；
* 两台国产 Android TEE/StrongBox 真机执行联网、断网、强杀、锁屏、时钟修改、重启、吊销、清数据、卸载重装和文件系统镜像验收；
* 用生产 KMS 的旧备份恢复演练证明被吊销 Share 不会复活。

在这些验收完成前，当前实现是可编译、可单测的 Android 安全底座，不是已经上线的远程控制能力。
