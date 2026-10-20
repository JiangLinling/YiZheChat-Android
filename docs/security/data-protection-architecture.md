# YiZheChat 数据保护总设计

> 状态说明：本文中的 `DESIGNED/SCAFFOLDED` 和批次尾部缺口是对应提交阶段的历史快照。当前代码闭环、自动化证据和仍需外部部署的生产门槛，以仓库根目录 `docs/PRODUCTION_SECURITY_ACCEPTANCE.md` 为唯一验收清单。

<!--- TOC -->

* [文档状态](#文档状态)
* [目标与边界](#目标与边界)
* [安全不变量](#安全不变量)
* [威胁模型](#威胁模型)
* [总体架构](#总体架构)
  * [模块边界](#模块边界)
  * [密钥层级](#密钥层级)
  * [载体与内容流](#载体与内容流)
* [第一阶段：关闭持久明文残留](#第一阶段关闭持久明文残留)
* [第二阶段：硬件密钥与账号 Vault](#第二阶段硬件密钥与账号-vault)
* [第三阶段：内容密钥与 KMS](#第三阶段内容密钥与-kms)
  * [内容密钥拆分](#内容密钥拆分)
  * [离线许可证](#离线许可证)
  * [Matrix Key Backup](#matrix-key-backup)
* [统一删除协议](#统一删除协议)
* [卸载、清除数据和重新安装](#卸载清除数据和重新安装)
* [迁移和兼容策略](#迁移和兼容策略)
* [验收门槛](#验收门槛)
* [推荐默认策略](#推荐默认策略)
* [当前实施状态](#当前实施状态)

<!--- END -->

## 文档状态

本文是 YiZheChat Android 数据保护工程的总设计基线。它覆盖本地持久化、硬件密钥、内容密钥、KMS、离线、删除、卸载和恢复，不等同于当前已经完成的功能清单。

实现状态必须使用以下词汇：

* `DESIGNED`：已经设计，尚未实现。
* `SCAFFOLDED`：模块和接口已经建立，尚未接入真实数据路径。
* `CONNECTED`：已经接入真实业务路径。
* `VERIFIED`：已经完成对应单元、真机或取证验证。

禁止仅凭编译通过将安全能力标为 `VERIFIED`。

## 目标与边界

目标是在不重写 Matrix Olm/Megolm 的前提下建立统一、不可绕过的数据保护底座：

* 文字、图片、语音消息、视频、文件和缩略图不以持久明文落盘。
* 数据删除首先销毁解密能力，再清理数据库、WAL、文件和服务器副本。
* 设备存储镜像、已删除页、缓存和备份只能恢复出密文。
* 账号退出、设备吊销、内容到期和 App 卸载均有明确的密钥失效语义。
* KMS、Matrix 服务器或手机存储镜像任一方单独泄露时，不能独立解密业务内容。
* 手机离线风险由有界离线许可证控制，不承诺永久离线与即时吊销同时成立。

本设计不能消除以下固有风险：

* 用户查看时屏幕、音频、视频和进程内存中的短暂明文。
* 已 Root/Hook 或利用系统漏洞的设备在解密瞬间实时截取内容。
* 用户主动拍照、录音、转发或复制到第三方应用后的副本。
* 接收方在到期前已经脱离受控客户端保存的内容。

## 安全不变量

1. 敏感持久对象必须先加密后写入，禁止先写明文再补加密。
2. 密文、内容密钥、包装密钥和硬件根密钥必须处于不同层级。
3. API 不向业务层返回可长期保存的 Vault/数据库原始密钥。
4. 删除操作必须先将对象标为不可访问，再毁钥，再做物理清理。
5. 卸载安全不能依赖卸载回调或卸载前任务。
6. 所有迁移和删除状态机必须可重入、可断电恢复、失败关闭。
7. 生产日志不得包含消息、附件、令牌、密码、密钥或解密后的网络正文。
8. 推送只负责唤醒或传递不敏感标识，不承载消息正文或附件。
9. Matrix Key Backup 不得备份 YiZheChat 内层内容密钥或 KMS 份额。
10. 新安装必须产生新的安装标识和设备密钥，旧安装的密钥材料不得静默恢复。

## 威胁模型

优先抵御：

* 普通和专业文件恢复软件。
* App 私有目录、SQLite、WAL、SHM、缓存和日志的离线镜像分析。
* 手机卸载、清除数据或重新安装后尝试恢复旧数据。
* Matrix 事件库、媒体库、备份或 KMS 数据库单点泄露。
* 设备遗失后的远程吊销和有限离线风险。
* 误将图片、语音、视频、通知或编辑临时文件以明文写入磁盘。

作为检测和阻断对象但不承诺绝对抵御：

* Root、调试器、Hook、动态注入和进程内存抓取。
* 已解锁设备上的系统或内核零日漏洞。
* 摄像头拍屏、外部录音和用户主动导出。

## 总体架构

### 模块边界

```text
security:data-protection
├── api   策略、敏感资产清单、删除请求和总协调契约
├── impl  清单实现、删除协调和阶段状态汇总
└── test  跨模块测试替身和故障注入工具

security:secure-storage
├── api   SensitiveFileStore、Vault、设备信任和密钥生命周期契约
├── impl  Android Keystore、StrongBox/TEE、加密文件和数据库密钥实现
└── test  假 Vault、假文件存储和可控密钥状态

security:content-protection
├── api   内层内容信封、内容策略、KMS 释放和离线许可证契约
├── impl  内容格式、份额组合、KMS 客户端和 Matrix 映射
└── test  固定测试向量、假 KMS 和过期/吊销场景
```

依赖方向固定为：

```text
业务模块 ──> data-protection:api
data-protection:impl ──> secure-storage:api + content-protection:api
content-protection:impl ──> secure-storage:api

secure-storage 不依赖 Matrix
content-protection 不向 UI 泄漏 Matrix Rust SDK 类型
```

### 密钥层级

```text
Android StrongBox/TEE
├── DAK：设备证明和签名
└── DRK：设备根包装密钥，不可导出
    └── AVK：每账号 Vault 包装密钥
        ├── Credential Key
        ├── Matrix Store Key
        ├── Database Key
        ├── Media Key
        ├── Cache Key
        └── Log Key
            └── 每对象随机 DEK
```

DRK、AVK 和各域密钥均有独立版本。能独立销毁的对象不得只通过一个永久根密钥确定性派生，必须使用随机 DEK 和可删除的密钥信封。

### 载体与内容流

持久媒体的目标流程：

```text
Matrix 下载/ContentResolver 输入
  -> 流式解密或预处理
  -> SensitiveFileStore 加密写入
  -> 磁盘只保存加密容器
  -> 查看时流式解密到内存/管道/加密 DataSource
  -> 锁屏、退出或到期后释放内存和文件描述符
```

通话的目标流程：

```text
麦克风/摄像头
  -> 内存采集帧
  -> 编码和通话媒体加密
  -> TURN/SFU
  -> 接收端内存解密和播放
```

通话默认不生成持久媒体。通话录音、录像、截图和转码一旦启用，必须作为敏感媒体进入 SensitiveFileStore 和内容密钥生命周期。

完整载体清单见 [sensitive-data-inventory.md](sensitive-data-inventory.md)。

## 第一阶段：关闭持久明文残留

交付范围：

* 建立 SensitiveFileStore 和统一敏感目录。
* 为图片、缩略图、语音、视频和附件建立加密容器。
* 禁止敏感 Coil 普通磁盘缓存，锁屏时清理内存缓存。
* 改造通知媒体、上传、编辑、拍照和分享临时文件。
* 生产构建在编译期禁用 HTTP BODY 日志。
* 日志结构化、脱敏、加密并缩短保留时间。
* 账号退出执行密钥失效、数据库清理和文件清理。
* 删除时执行 `secure_delete`、WAL checkpoint/TRUNCATE 和必要的数据库轮换。
* 建立删除协调器、敏感载体覆盖测试和明文 canary 取证测试。

第一阶段不改变 Matrix 事件格式，但必须保证 SDK 解密输出直接进入加密写入流。若 Rust SDK 只能产生明文临时文件，必须扩展包装层或 SDK 媒体输出接口，不能接受“先生成明文文件再加密”。

第一阶段门槛：查看、上传、下载、播放、通知、编辑、分享、失败和崩溃路径均无法在 App 持久目录中检出 canary 明文。

## 第二阶段：硬件密钥与账号 Vault

交付范围：

* 首次安装生成 installation ID、DAK 和 DRK。
* 检测实际安全等级：StrongBox、TEE、软件或未知。
* 服务端使用 challenge 校验证书链、App 签名、Verified Boot 和安全等级。
* 每账号创建 AVK，并将凭据、Matrix Store、数据库、媒体和日志密钥迁移到独立信封。
* 锁屏、账号切换、后台超时和内存压力时释放业务密钥。
* 推送/传输状态与内容 Vault 分离；锁屏后台只允许无内容通知。
* 使用 shadow database、校验和原子替换迁移现有 SQLCipher 数据库。
* 设备状态支持 pending、trusted、warning、revoked、lost 和 stale。

国产 Android 设备若无法验证硬件证明链，必须通过兼容矩阵、OEM/MDM 证明或策略降级处理，禁止相信客户端自报的安全等级。

第二阶段门槛：复制完整 App 数据目录到另一设备、清除数据或重新安装后，旧密文均无法解密；锁屏后业务密钥不可继续从普通进程缓存获得。

## 第三阶段：内容密钥与 KMS

保留 Matrix Olm/Megolm 外层，在其中增加版本化 YiZheChat Secure Envelope。普通 Matrix 客户端不能解密该内层格式。

### 内容密钥拆分

每条消息或独立附件生成两份随机材料：

```text
CEK = HKDF(DeviceShare || KmsShare, immutableContentContext)
```

* DeviceShare 使用接收设备公钥包装，Matrix/KMS 只能看到密文信封。
* KmsShare 由独立 KMS/HSM 保存并受策略、设备和有效期控制。
* KMS 单独没有 DeviceShare，设备静态存储单独没有 KmsShare。
* CEK、两份 Share 和解密明文只在需要时进入内存并尽快释放。

不可变上下文至少绑定 room、content、sender device、policy、format version 和 expiresAt，防止跨房间替换、降级和重放。

KMS 必须与 Matrix 分离数据库、服务账号、网络权限、备份策略和审计权限。KMS 备份恢复必须先应用不可回退的过期/吊销墓碑，禁止旧备份复活已经销毁的 Share。

### 离线许可证

推荐支持两种策略，而不是宣称同时满足永久离线和即时吊销：

* `ONLINE_REQUIRED`：每次解密请求 KMS，吊销最及时。
* `BOUNDED_OFFLINE`：KMS Share 仅在设备绑定的短期许可证内可用。

许可证绑定设备、账号、内容范围、策略版本、服务端时间、过期时间、nonce 和使用限制。检测重启、时钟回拨、完整性异常或策略版本不匹配时立即失效。远程吊销的最大延迟等于离线许可证剩余时间。

### Matrix Key Backup

Matrix Key Backup 可以继续备份外层 Megolm Room Key，但不得保存 YiZheChat CEK、DeviceShare 私钥、KmsShare 或离线许可证。恢复外层 Key Backup 后，已到期内容仍只有内层密文。

无企业总恢复钥匙时，新设备读取未过期历史需要已有授权设备重新包装 DeviceShare；如果没有已有设备，历史可能不可恢复。这是严格删除与高可用恢复之间的明确取舍。

第三阶段门槛：删除 KmsShare 或吊销设备后，恢复 Matrix Key Backup、数据库备份和媒体备份仍不能解密对应内容。

## 统一删除协议

删除协调器使用可重入状态机：

```text
REQUESTED
  -> MARKED_INACCESSIBLE
  -> STOP_SYNC_AND_UI
  -> PURGE_MEMORY
  -> DESTROY_KEY_ACCESS
  -> CLOSE_DATABASES
  -> SANITIZE_DATABASES
  -> DELETE_FILES
  -> CANCEL_NOTIFICATIONS
  -> REVOKE_REMOTE_ACCESS
  -> WRITE_NON_CONTENT_AUDIT
  -> COMPLETE
```

本地服务器不可达不能阻塞本地毁钥。远程吊销失败时只保留不含令牌和内容的重试任务。审计记录只能包含操作 ID、资产 ID、阶段、策略版本、时间和结果码。

文件覆盖是尽力而为的辅助措施。UFS/eMMC、FTL、磨损均衡和 Copy-on-Write 使 App 无法证明覆盖到原物理页；主保证来自从首次落盘即加密及删除时销毁密钥。

## 卸载、清除数据和重新安装

不能依赖 App 收到卸载回调。卸载保护由两份独立材料同时丢失形成：

```text
App 私有目录中的密钥信封消失
+ Android Keystore 中旧安装硬件密钥失效
= 旧密文失去解密链
```

服务器只能根据心跳、KMS 活动和推送端点判断设备 stale，不能立即区分关机、离线和卸载。重新安装必须创建新的 installation ID、DAK、DRK 和设备加密密钥，并作为新设备重新审批。

禁止：

* 从系统备份或 D2D 迁移恢复旧 Vault。
* 因账号相同而重新发放已过期 KmsShare。
* 把旧安装设备 ID 绑定到新设备密钥。
* 将推送 token 视为设备身份或解密凭据。

系统相册、下载目录、第三方分享目标、截图、录屏和外部录音不随 YiZheChat 卸载消失，属于后续 DLP 管控范围。

## 迁移和兼容策略

* 第一阶段先建立最终接口，不把业务模块直接绑定到具体 Keystore 或 KMS 客户端。
* 第二阶段数据库逐库迁移，使用 shadow copy、完整性校验和原子替换。
* 第三阶段使用版本化新 Matrix 内层格式，并设置明确 cutover 时间。
* 旧消息可以加强本地加密，但不能凭空获得原先不存在的 KMS 密钥到期能力。
* cutover 后不支持新格式的客户端不得继续发送受保护消息。
* 协议降级、旧策略恢复和旧 KMS 备份恢复必须默认拒绝。

## 验收门槛

每一阶段除单元和集成测试外，还必须有对应安全证据：

* Root 实验设备上的 App 数据目录镜像。
* SQLite、WAL、SHM、缓存、通知、媒体和日志 canary 扫描。
* 查看、上传、失败、崩溃、杀进程、重启、锁屏和低内存路径。
* 清除数据、卸载、重新安装、OEM 换机和 D2D 迁移。
* Matrix Key Backup 恢复、KMS 备份恢复、过期和远程吊销。
* BFU/AFU 边界记录；编译通过不能替代真机和取证验证。
* 第三阶段正式上线前完成独立密码学和移动安全评审。

## 推荐默认策略

* 业务内容保留：24 小时，可由企业策略缩短。
* 离线许可证：1 小时，重启立即失效。
* 通知正文：默认隐藏。
* 日志：脱敏、加密、24 小时轮换，生产禁止 HTTP BODY。
* 硬件最低要求：TEE；StrongBox 优先；软件级只允许在线受限模式或拒绝登录。
* 导出：默认禁止，后续由 DLP 策略显式授权。
* 录音录像：默认禁止；启用时进入独立内容密钥和保留策略。

## 当前实施状态

截至 2026-08-30：

* 本文和敏感载体清单：`DESIGNED`。
* security 三子系统模块和 App/Metro 接入：`CONNECTED`。
* 41 项载体责任归属、`DataProtectionPolicyV1` 和安全 CI：`CONNECTED`。
* 当前 SQLCipher、Tink/Keystore 和备份排除：既有基础，需要纳入新 Vault 迁移。
* `SensitiveFileStore` 加密容器、Keystore Vault 包装密钥、原子写入、对象/Vault 删除和恢复清理：`CONNECTED`；
* Matrix 接收媒体、图片/视频查看、语音播放和通知媒体：`CONNECTED`，尚待真机和取证验收；
* 通知默认隐藏、Release 日志/崩溃栈禁用、App SQLCipher 加固、WAL truncate 和设备保险库退出毁钥：`CONNECTED`，尚待真机、升级和取证验收；
* 账号级删除持久任务、逐步提交、有界非内容审计、幂等重试和启动续跑：`CONNECTED`，尚待强杀、断电、低存储和文件系统镜像验收；
* 旧接收缓存、上传/编辑/录音 scratch、明文日志、截图和旧崩溃详情的持久迁移清理、不可降级安全媒体 cutover 与冷启动重复清理：
  `CONNECTED`，尚待升级、强杀、低存储和文件系统镜像验收；
* 256 位 installation ID、不可导出 Android Keystore DRK、实际硬件等级检测、DRK 包装的每账号随机 AVK、新对象 DEK 层级、旧对象信封
  按读取迁移、整机/账号密码学毁钥和冷启动备份断链检查：`CONNECTED`，尚待 StrongBox/TEE、升级、卸载/D2D 和 Root 镜像真机验收；
* 账号 Vault 清单、七个随机域密钥（含独立 `OFFLINE_LICENSE` 域）、显式锁定内存状态、前后台/应用锁/账号切换协调、MEDIA 域对象包装、域版本轮换、毁钥墓碑和批次 6
  信封升级：`CONNECTED`，尚待国产 Android 真机生命周期和取证验收；
* 安装绑定 EC DAK、StrongBox/TEE 实际等级、challenge statement 签名、证书链导出、App 签名/Root/调试风险、持久可信状态、Vault 失信门禁和
  no-backup/D2D/重装断链：Android 客户端 `CONNECTED`；服务端 Device Registry 已校验证书链、Android Key Attestation 扩展、Verified Boot、
  包名/签名、根链和吊销状态，并接入人工审批、签名信任快照、推送命令、前台周期调度及失败关闭；真实生产证明根/吊销源和第二厂商真机仍待验收；
* 设备访问票据和轻心跳：客户端/服务端均 `CONNECTED / AUTOMATED_TESTED`。票据 TTL 可在 5–60 分钟配置，前台心跳可在 30–300 秒配置；默认
  1 小时票据、60 秒心跳、120 秒宽限。客户端要求 `ACTIVE` 签名回执严格保持票据内的间隔、宽限和失效时间，并绑定 installation、证明版本/公钥
  与正数策略版本；本地 PostgreSQL/Redis/三副本和千人负载已验证，远端旧单副本尚未部署这一协议；
* 版本化内层内容信封、双份额 HKDF、Android Keystore DeviceShare RSA 包装、字节/分块流式 AEAD、有界解析、独立无日志 HTTPS KMS 客户端、
  ECDSA 签名授权响应和 KMS 销毁协议：Android 客户端、服务端短期授权、Device Registry 公钥发布、固定大小房间 epoch 信封和 Matrix 映射均
  `CONNECTED / AUTOMATED_TESTED`；腾讯云 KMS EncryptionContext 接口和 Key Broker 已实现，本地候选镜像/HA/压测已通过，真实 CMK/CAM 尚未部署；
* 有界离线许可证的账号 Vault 加密持久化、设备/策略/内容/boot 绑定、服务端与单调时间锚、使用次数先扣减、时钟回拨检测、永久吊销集合和
  设备远程毁钥：Android 客户端、协议 v2 签发端、主动同步/重试调度和冷启动恢复均 `CONNECTED / AUTOMATED_TESTED`；长期离线、回拨、旧备份、
  卸载重装及生产签名包的完整真机矩阵尚未完成；
* 语音和视频通话的加密房间/内置来源/设备信任/Account Vault 强制门禁、活动会话吊销、WebView 同源消息与媒体权限、无持久缓存、
  通知元数据隐藏、日志去内容、`FLAG_SECURE` 和 playback capture 阻断：Android 客户端 `CONNECTED`；真实 WebRTC E2EE/DTLS-SRTP、
  TURN/SFU/LiveKit 禁录制配置、国产 Android 真机和文件系统取证仍未 `VERIFIED`，详见 `batch-12-voice-video-calls.md`；
* 上传预处理、编辑/拍照暂存、保护媒体路由、Matrix Rust SDK 内部数据库轮换和单内容密钥索引已接入并有自动化门禁；图片、语音、视频、附件
  的双真机真实发送/接收、低存储、强杀和文件系统取证仍未完成，因此不能仅凭构建标记为生产验收。
* 当前剩余边界是部署与外部验收，不是继续用本地占位替代生产：真实腾讯云 CMK/CAM、COS WORM、托管 PostgreSQL/Redis 灾备、远端新版三副本、
  生产签名/SPKI、第二厂商设备、完整媒体/RTC/网络切换和独立安全审计仍为 `PRODUCTION_DEPLOYMENT_PENDING`。

任何后续变更都必须同步更新本文、敏感载体清单和阶段验收状态。
