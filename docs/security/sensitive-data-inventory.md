# YiZheChat 敏感数据与载体清单

<!--- TOC -->

* [状态说明](#状态说明)
* [客户端持久载体](#客户端持久载体)
* [客户端内存和系统载体](#客户端内存和系统载体)
* [服务器和网络载体](#服务器和网络载体)
* [删除覆盖规则](#删除覆盖规则)
* [维护要求](#维护要求)

<!--- END -->

## 状态说明

该清单是数据保护工程的覆盖基线。`当前状态`描述 2026-08-24 的代码审计结果，不代表已经完成取证验证。

* `已有加密`：已有明确加密基础，但仍需验证密钥生命周期和残留副本。
* `明文风险`：存在或可能存在可恢复明文。
* `系统边界`：数据位于 Android 系统或其他 App，YiZheChat 不能仅靠本地删文件控制。
* `运行审计`：需要在真实设备和实际通话/媒体路径中确认。
* `计划能力`：只有接口或设计，尚未接入。

阶段：`P1` 关闭持久明文；`P2` 硬件 Vault；`P3` 内容密钥和 KMS；`DLP` 用户使用范围管控。

## 客户端持久载体

| ID | 数据/载体 | 当前代码或位置 | 当前状态 | 目标保护 | 阶段 |
|---|---|---|---|---|---|
| L-001 | Session access/refresh token、Matrix Store passphrase | `libraries/session-storage` 的 SessionData/SQLCipher DB | 已有加密；Credential 域已建立但数据未迁移 | 每账号 Credential Key；退出先毁钥，再删除行和轮换 DB | P1/P2 |
| L-002 | Matrix State/Crypto Store | 每账号 session file/cache directory | 已有加密；Matrix Store 域已建立但 Rust DB 未迁移 | 独立 Matrix Store Key，经 AVK 包装；锁屏释放 | P2 |
| L-003 | Matrix 文字事件及关系数据 | Matrix Store/缓存数据库 | 已有加密 | P1 加固 DB 残页；新消息增加内层 CEK | P1/P3 |
| L-004 | 图片和普通媒体缓存 | `SensitiveFileStore` + 一次性 `content://` 租约；批次 5 冷启动清除旧 `cache/temp/media` | 已接入，待真机/取证验收 | 每对象 DEK；全对象认证后管道读取；SDK 内存返回仍待流式 API | P1 |
| L-005 | 语音消息缓存 | 加密媒体句柄；已移除 `cache/temp/voice`，批次 5 冷启动清旧目录 | 已接入，待真机验收 | 播放器直接读取一次性 URI；关闭/TTL 后删密钥和密文 | P1 |
| L-006 | 通知图片和媒体 | 默认隐藏策略不预取媒体；加密句柄；批次 5 清旧通知媒体目录 | 已接入，待通知历史/真机验收 | 必要媒体用短期 URI；进程死亡或 TTL 后失效 | P1 |
| L-007 | 上传预处理和转码文件 | `cache/uploads` 及 cache 根临时文件；批次 5 清启动遗留 | 启动清理已接入，运行时仍有明文风险 | 加密暂存/内存管道；成功、失败、取消统一登记删除 | P1 |
| L-008 | 图片编辑、裁剪和相机临时文件 | 编辑器、相机和 FileProvider 路径；批次 5 清启动遗留 | 启动清理已接入，运行时仍有明文风险 | 加密 scratch 或受控管道；一次性 URI | P1 |
| L-009 | 对外分享临时文件 | 非导出只读 ContentProvider + 随机短期 URI | 已接入，显式导出仍属系统边界 | 管道分享、TTL/关闭撤销；DLP 决定是否允许导出 | P1/DLP |
| L-010 | Coil 图片磁盘缓存 | Matrix 会话 ImageLoader 已禁用磁盘缓存 | 已接入，待 canary 验收 | 解密输入来自加密句柄；仅保留受控内存缓存 | P1 |
| L-011 | 视频封面和播放器缓存 | 查看器使用加密媒体 `content://` 句柄 | 已接入，待 Media3 真机验收 | 分块容器和受控 URI；播放结束/TTL 清理 | P1 |
| L-012 | 推送历史和账号映射 | push store/push history SQLCipher DB；统一 secure_delete/WAL policy | P1 数据库加固已接入 | 独立 Push Key；推送载荷不含正文 | P1/P2 |
| L-013 | 本地日志和崩溃附件 | Release 不落 tracing/logcat/崩溃栈；批次 5 清旧日志、截图和崩溃详情；Debug 24 小时/24 文件 | P1 已接入，升级/取证待验收 | 生产保持禁用；若恢复诊断必须使用结构化脱敏和 Log Key | P1 |
| L-014 | 数据库 WAL/SHM/删除页 | App SQLCipher 统一 secure_delete 和 checkpoint TRUNCATE；Rust SDK DB 待审计 | 部分接入 | SDK 数据库审计、数据库轮换和账号毁钥 | P1/P2 |
| L-015 | 密钥信封和版本元数据 | `noBackupFilesDir/secure/v1/device-root`；`vault-keys/*.yzvk`；`account-vaults/*/manifest.yzav` 与七域 `.yzdk`；对象 `.yzkey`；`offline-licenses/*.yzol`；删除/迁移台账 | DRK -> 随机 AVK -> 域密钥 -> 对象/许可证 DEK 已接入；离线许可证使用独立 `OFFLINE_LICENSE` 域且只持久化设备包装的 KMS Share；待真机/取证验收 | 凭据/Matrix Store/数据库域迁移和服务端硬件证明 | P1/P2/P3 |
| L-016 | Matrix Key Backup 恢复材料 | Matrix secure backup/recovery 本地状态 | 已有加密 | 只恢复外层 Megolm；禁止备份内层 CEK/Share | P3 |
| L-017 | 位置消息和地图缓存 | Matrix location event、地图 SDK 缓存 | 运行审计 | 坐标按文字事件保护；地图缓存不得绑定用户位置明文 | P1/P3 |
| L-018 | 草稿、回复、搜索和最近项 | 各 feature preference/cache/store | 运行审计 | 纳入 catalog；DB 或加密对象；按账号清理 | P1 |

## 客户端内存和系统载体

| ID | 数据/载体 | 所在边界 | 当前状态 | 目标保护 | 阶段 |
|---|---|---|---|---|---|
| M-001 | 解密文字和 Compose 状态 | App heap | 系统边界 | 最短生命周期；锁屏/账号切换释放；禁止日志和持久快照 | P1/P2 |
| M-002 | 图片 Bitmap、Coil memory cache、GPU texture | App/GPU 内存 | 系统边界 | 锁屏清理内存缓存；释放引用；不承诺已 Root 设备绝对清零 | P1/P2 |
| M-003 | 音频 PCM、编码帧和播放器 buffer | Audio/Media3 内存 | 系统边界 | 播放结束和锁屏释放；不写调试 PCM | P1 |
| M-004 | 视频解码帧、Surface 和 codec buffer | MediaCodec/GPU | 系统边界 | 禁止帧转储；结束和锁屏释放；不生成明文封面 | P1 |
| M-005 | 语音/视频通话实时媒体 | 麦克风、摄像头、WebRTC/MatrixRTC/LiveKit buffer | Android 强制加密房间、内置来源、Vault/设备门禁和媒体权限同源；真实媒体包仍需运行审计 | 审计真实 E2EE/DTLS-SRTP 路径；默认不录制、不落盘 | P2/P3 |
| M-006 | 通话元数据 | Matrix call event、本地日志/状态 | 来电通知默认隐藏、Widget console 和对象日志已关闭；Matrix 事件保留仍需审计 | 按策略加密和保留；日志不含媒体内容 | P1/P3 |
| M-007 | Android 锁屏通知和通知历史 | SystemUI/通知历史 | 默认隐藏已接入，历史清除待真机验收 | 默认仅显示“有新消息”；正文展示属于策略降级 | P1/DLP |
| M-008 | 剪贴板 | Android Clipboard | 系统边界 | 默认禁止敏感复制；必要时标敏感并短时清理 | DLP |
| M-009 | 截图、录屏和最近任务缩略图 | Android/相册 | 系统边界 | FLAG_SECURE/管理策略/水印；不属于单纯存储加密 | DLP |
| M-010 | 下载目录、系统相册和第三方分享副本 | MediaStore/其他 App | 系统边界 | 默认禁止导出；授权导出后无法保证远程删除 | DLP |
| M-011 | 进程内 Vault/CEK/KMS Share | Android Keystore operation；AVK/域密钥/DEK/CEK/Share 局部字节和 `SecretKey` | DRK/DeviceShare 私钥不可导出；AVK 有界缓存；CEK/两份 Share 局部生成并尽力清零，待真机内存验收 | Android 硬件用户认证增强；CEK/KMS Share 最短作用域 | P2/P3 |

## 服务器和网络载体

| ID | 数据/载体 | 所在服务 | 当前状态 | 目标保护 | 阶段 |
|---|---|---|---|---|---|
| S-001 | Matrix 事件 JSON 和关系数据 | Synapse/Matrix DB | 已有 Matrix E2EE | 新消息再加 YiZheChat 内层密文；过期执行服务端清理 | P3 |
| S-002 | 图片、语音、视频、附件和缩略图 | Matrix Media/object storage/CDN | 已有 Matrix 媒体加密 | 内层每对象 CEK；媒体密文和 key share 分离 | P3 |
| S-003 | Matrix Room Key Backup | Matrix key backup | 已有加密 | 仅外层 Megolm；不得恢复已到期内层内容 | P3 |
| S-004 | Synapse/媒体数据库备份 | 备份系统 | 运行审计 | 只含密文；删除墓碑和保留策略必须可恢复重放 | P3 |
| S-005 | ntfy topic、push endpoint 和推送载荷 | 自建 ntfy/UnifiedPush | 运行审计 | 只传唤醒和不敏感 ID；禁止正文、附件和 KMS Share | P1 |
| S-006 | TURN/SFU 实时通话包 | TURN/LiveKit/MatrixRTC | Android 入口失败关闭已接入；服务端、抓包和真实 E2EE 仍需运行审计 | 审计媒体加密、日志、内存和录制；默认不持久化 | P2/P3 |
| S-007 | 通话录音录像 | 录制服务/object storage | Android 无本地录制入口且策略默认禁止；服务端 Egress/recording 未验收 | 默认禁止；启用后每录制对象独立 CEK 和保留策略 | P3/DLP |
| S-008 | KMS Share | 独立 KMS/HSM | Android HTTPS/签名/设备包装客户端协议已接入；生产 KMS/HSM 待部署 | 与 DeviceShare 分离；策略到期毁钥；旧备份不能复活 | P3 |
| S-009 | 设备证明和设备状态 | Android 客户端 / Device Registry | Android 已接入签名吊销快照消费、`STALE/REVOKED` 持久墓碑和 Vault/DeviceShare 毁钥；生产 Device Registry、心跳和推送触发待部署 | DAK challenge、证书链、App 签名、不可升级的限制状态；服务端完成证明校验和序列化吊销 | P2/P3 |
| S-010 | 离线许可证 | KMS 与设备 | Android 已接入 Vault 加密持久化、设备绑定、服务端/单调时间锚、boot count、使用次数、策略/刷新期限、时钟回拨和永久吊销集合；生产签发端与真机待验收 | 短 TTL；重启/回拨/内容或设备吊销失效；磁盘不落明文 KMS Share | P3 |
| S-011 | 删除与安全审计 | 独立审计存储 | 计划能力 | 只含操作和策略元数据，不含正文、密钥或敏感文件名 | P1/P3 |
| S-012 | KMS/Matrix/代理访问日志 | 各服务器 | 运行审计 | 脱敏、短保留、权限隔离；禁止请求正文和 Share | P1/P3 |

## 删除覆盖规则

| 删除范围 | 必须销毁 | 必须清理 | 远程动作 |
|---|---|---|---|
| 单条文字消息 | CEK/DeviceShare envelope/KmsShare | UI、DB 行、WAL、通知和缓存 | KMS 墓碑、Matrix 事件/备份策略 |
| 图片/语音/视频/附件 | 对象 DEK 和内容 Share | 密文文件、缩略图、播放器/解码缓存 | KMS 墓碑、媒体仓库清理 |
| 账号退出 | AVK 和账号全部 domain key envelope | Session DB、Matrix Store、缓存、通知和日志 | Matrix logout、push 注销、KMS 设备/账号授权撤销 |
| 设备吊销/遗失 | 后续 KMS Share 释放权、账号 Vault、DeviceShare 私钥和离线许可证 | 签名吊销快照先写不可放宽墓碑，再锁定/毁掉本地密钥；设备离线时最多暴露到已签租约的最早到期/强制刷新时刻 | Device Registry=REVOKED、KMS 拒绝释放并持续返回单调递增吊销快照 |
| App 卸载/清除数据 | 由 Keystore 密钥和本地信封同时消失形成断链 | 系统删除 App 专属目录 | 心跳超时标记 stale；重装作为新设备审批 |
| 企业保留期到期 | 对应 KmsShare 和本地内容密钥 | 客户端/服务器密文、媒体和通知 | KMS 不可回退墓碑、服务器保留任务 |

## 维护要求

* 新增任何持久化、缓存、通知、媒体、日志、推送、通话或导出路径时，必须先登记新的资产 ID。
* 每个资产必须有 owner、实际路径、密钥域、保留时间、删除动作和验收用例。
* 标记为 `运行审计` 的载体在完成源码和真机检查前不得升级为已保护。
* 安全测试必须验证真实内容路径，不能用模块编译替代媒体、通知、通话、卸载或取证验证。
* 本清单与代码中的 `SensitiveAssetCatalog` 必须保持 ID 一致。
