# 批次 9：KMS 与内容信封

## 结论

本批次完成 Android 客户端的真实内层内容密码学核心和 KMS 接入协议。它不是把 KMS Share 存到本机的“假 KMS”，也没有在 KMS 未配置时回退到本地万能钥匙。

当前状态：

* 版本化二进制内容信封、双份额派生、字节和流式 AEAD：`CONNECTED`；
* 每安装独立 DeviceShare 包装密钥：`CONNECTED`，使用 Android Keystore RSA-3072；
* HTTPS KMS 写入、签名授权响应、设备包装释放和销毁墓碑协议：Android 客户端 `CONNECTED`；
* 生产 KMS v2、设备注册表公钥发布和 DAK proof：`server/control-plane` 已实现；腾讯云 CMK/CAM 资源仍需部署；
* Matrix 自定义事件收发、时间线渲染和媒体容器：`CONNECTED`；
* 跨进程持久离线许可证：批次 11 已接入；使用账号 Vault 独立域加密保存设备包装 Share，不保存明文 KmsShare。

`security.content_protection` 已标记为完成并默认开启。若生产 KMS、可信设备、签名策略、证书 pin 或 Matrix runtime 缺失，最终门禁拒绝发送/解密而不回退明文。生产验收清单见 [stage-3-security-gate.md](stage-3-security-gate.md)。

## 密钥结构

每个内容对象生成两份独立的 256 位随机材料：

```text
DeviceShare = random(32)
KmsShare    = random(32)
CEK         = HKDF-SHA256(DeviceShare || KmsShare, immutableContextSha256)
```

CEK、DeviceShare 和 KmsShare 只在一次保护/解密调用的局部作用域出现，完成后覆盖其可控 `ByteArray`。JVM、系统密码提供者和 TLS 实现内部副本不属于 App 可证明清零的范围，因此不得宣称进程内绝对无残留。

`immutableContextSha256` 由下列字段的规范二进制编码计算：

* room ID；
* format version、content ID、content kind 和 cipher suite；
* policy ID、policy version 和 expiresAt；
* sender installation ID 和 createdAt；
* `ONLINE_REQUIRED` 或 `BOUNDED_OFFLINE`。

规范 Header 又作为 AES-GCM AAD，绑定 KMS reference、每个 DeviceShare envelope、nonce 和所有不可变字段。跨房间替换、收件设备替换、策略降级、KMS reference 替换或 Header 重排都会导致认证失败。

## 设备份额

`AndroidDeviceShareKeyService` 为当前 installation ID 创建独立 Android Keystore RSA-3072 私钥：

* 私钥不可导出；
* 优先请求 StrongBox，不可用时回落到系统 Android Keystore；
* OAEP 使用 SHA-256，兼容 Android 24+ 的 MGF1-SHA1；
* Key ID 是 X.509 公钥 DER 的 SHA-256；
* 被包装明文同时绑定 content ID digest 和 immutable context digest；
* 解密只选择与本 installation ID 和 Key ID 完全匹配的 envelope。

接收设备公钥必须来自经过审批的 Device Registry。禁止直接相信 Matrix 消息内自报的公钥。重新安装产生新 installation ID 和新私钥，旧 DeviceShare envelope 不会自动转移到新安装。

## 内容加密格式

小对象使用 `AES_256_GCM_HKDF_SHA256`。正文 API 对单次内存对象设置 16 MiB 上限，避免错误路径用巨型附件耗尽进程内存。

图片、语音、视频、附件和录制对象使用 `CHUNKED_AES_256_GCM_HKDF_SHA256`：

* 64 KiB 独立认证块；
* 每块从 96 位基础 nonce 和单调块序号派生唯一 nonce；
* 块序号、final 标志和完整 Header 都进入 AAD；
* final 块用于检测截断和追加；
* 每块通过认证后才写给下游。

调用方仍必须把流式解密输出写入批次 1 的安全临时对象，全部成功后原子发布；不得把已经输出的早期认证块直接落到普通共享文件。

`ContentEnvelopeBinaryCodec` 对不可信长度、枚举、收件设备数、Header 大小和 ciphertext 大小做有界解析，不使用 Java/Kotlin 对象序列化。

## KMS 客户端协议

客户端使用独立、无 HTTP 日志拦截器的 OkHttp/Retrofit 实例，端点为：

* `POST /v1/content-shares`：写入 KmsShare；
* `POST /v1/content-shares/release`：按设备、账号、策略、上下文、request nonce 和 boot count 申请释放；
* `POST /v1/content-shares/destroy`：写入不可回退销毁墓碑。
* `POST /v1/revocations/snapshot`：返回账号/安装绑定、序列化且经过签名的完整吊销快照。

除显式单元测试外只接受 HTTPS。可配置证书 SPKI pin；所有成功响应还必须由固定的 KMS ECDSA P-256 公钥使用 `SHA256withECDSA` 签名。

KMS 不返回裸 KmsShare。释放响应中的 KmsShare 必须用 Device Registry 中当前设备公钥包装，客户端先验证服务端签名和全部绑定字段，再交给 Android Keystore 解包。认证失败、未知枚举、过期、策略不一致、nonce 不一致、boot count 不一致、签名错误、HTTP 401/403/404/410 或未配置都失败关闭。

应用 Manifest 可提供非秘密连接配置：

* `io.yizhechat.kms.BASE_URL`；
* `io.yizhechat.kms.SIGNING_PUBLIC_KEY_DER_B64`；
* `io.yizhechat.kms.CERTIFICATE_PINS_SHA256`；
* `io.yizhechat.kms.MAXIMUM_OFFLINE_LEASE_MILLIS`；
* `io.yizhechat.kms.MAXIMUM_OFFLINE_LICENSE_USES`；
* `io.yizhechat.kms.CLOCK_ROLLBACK_TOLERANCE_MILLIS`。

授权令牌不允许写入 Manifest。默认 `UnavailableKmsAuthorizationProvider` 始终拒绝请求；企业会话层必须接入独立、短期、受众限定的 KMS 授权后才能启用功能。不得复用推送 token，也不应把长期 Matrix access token 直接交给 KMS。

## 离线边界

`BOUNDED_OFFLINE` 响应必须同时满足：

* KMS 签名有效；
* KmsShare 只以当前设备公钥密文存在；
* installation、Key ID、账号、content、policy、context、request nonce 和 boot count 绑定；
* TTL 不超过客户端配置上限，也不超过内容策略 expiresAt；
* boot count 不可用时拒绝 bounded offline。

批次 11 已使用账号 Vault `OFFLINE_LICENSE` 独立域保存经设备公钥包装的 Share、服务端时间锚、单调时钟锚、最近墙钟、剩余次数和吊销序列。进程重建后可以在签名 TTL、强制刷新时刻和使用次数三重边界内继续访问；重启、墙钟回拨、内容/租约/设备吊销、Vault 锁定或硬件信任下降均失败关闭。它不提供重启后的离线读取，也不允许“永久离线”。

## 删除和备份

`destroyContentAccess` 先写本地加密内容墓碑，再调用 KMS 销毁，远端失败不会回滚本地拒绝状态，也不会被解释为远端成功。生产 KMS 必须先写不可回退墓碑，再清除 Share；恢复旧 KMS 备份时必须重放墓碑。

Matrix Key Backup 只允许保存外层 Megolm key。不得备份 CEK、DeviceShare 私钥、KmsShare、KMS response 或离线许可证。只有在 KMS 销毁后恢复 Matrix 数据库和媒体备份仍不能解密，才达到第三阶段最终验收门槛。

## 已执行验证

单元测试覆盖：

* 双份额字节内容往返；
* 房间上下文替换在请求 KMS 前失败；
* ciphertext 篡改认证失败；
* KMS Share 销毁后不可解密；
* 150 KiB 多块流式往返；
* 流式尾部截断失败；
* 二进制信封编码/解码后仍可解密；
* 正确 KMS 签名和 Authorization header；
* 无签名/错误签名和无配置时失败关闭；
* Vault 加密离线许可证跨服务实例恢复且磁盘不出现内容 ID/设备包装 Share 明文；
* 持久记录篡改、重启、时钟回拨、租约次数耗尽失败关闭；
* 签名吊销快照、序列回退拒绝、设备吊销墓碑和账号/DeviceShare 毁钥。

仍需外部验收：真实 KMS/HSM、Device Registry 公钥审批、证书 pin 轮换、服务端墓碑备份恢复、Matrix 收发、国产 Android StrongBox/TEE 真机、进程强杀、断网和长时间离线。
