# 批次 2：媒体管道接入

> 历史批次说明：本文记录批次 2 当时的迁移边界。上传、下载、播放、通知、语音和 Matrix 保护媒体的当前闭环状态以仓库根目录 `docs/PRODUCTION_SECURITY_ACCEPTANCE.md` 为准。

<!--- TOC -->

* [状态](#状态)
* [接收媒体主链](#接收媒体主链)
* [安全媒体租约](#安全媒体租约)
* [已接入的消费者](#已接入的消费者)
* [升级与清理](#升级与清理)
* [明确边界](#明确边界)
* [验证要求](#验证要求)

<!--- END -->

## 状态

批次 2 已完成 Matrix **接收媒体主链**的代码接入。批次 5 已将 `security.secure_file_store` 固定为完成能力；既有安装保存的关闭值不再覆盖安全默认值。

图片、视频、语音和通知媒体不再要求 Rust SDK 生成普通临时文件，而是进入批次 1 的 `SensitiveFileStore`。查看和播放只获得进程内登记的随机短期 `content://` URI，不获得加密容器真实路径。

当前状态是 `CONNECTED`，不是 `FORENSICALLY VERIFIED`。模块测试和编译不能替代真机上的图片、视频 seek、语音、锁屏通知、进程死亡、升级清理及 canary 扫描。

## 接收媒体主链

流程如下：

1. `SecureMatrixMediaLoader` 调用 Rust 媒体内容接口，不调用会落地明文的 `downloadMediaFile`；
2. 内容按账号 Vault 和媒体类型写入独立 AES-GCM 加密对象；
3. 写入完成后立即清零 SDK 返回的 `ByteArray`；
4. 生成一小时 TTL 的安全媒体租约；
5. 调用方通过只读 `content://` URI 或 `InputStream` 使用内容；
6. 句柄关闭或 TTL 到期时撤销 URI，并删除密钥信封和密文对象。

Rust SDK 自身的解密媒体缓存上限在安全管道启用时设为 0，避免同一内容又进入 SDK 普通磁盘缓存。

## 安全媒体租约

`SecureMediaContentProvider` 具有以下约束：

* `android:exported=false`，只通过显式 URI 读权限向系统组件或用户选择的目标授权；
* URI 只包含 192 位随机 token，不包含账号、MXC、文件名、MIME 或对象引用；
* 注册表只在当前进程内存在，进程死亡后旧 URI 自动失效；
* 只接受 `r` 模式，不提供 insert、update 或可写文件描述符；
* 使用 reliable pipe 输出，不创建解密临时文件；
* 输出前先做一次全对象认证，再进行第二次读取，篡改或截断对象不会向消费者泄露一个“看似有效”的明文前缀；
* 租约关闭或到期后撤销 token，并异步删除加密对象。

## 已接入的消费者

* 图片和普通媒体查看器：直接使用安全 URI；
* 视频查看器：接收同一 URI，真实设备上仍需验证 Media3 seek 和 OEM 解码器兼容性；
* Coil：Matrix 会话 ImageLoader 禁用普通磁盘缓存，Fetcher 通过媒体句柄输入流读取；
* 语音消息：删除 `cache/temp/voice` 写入，播放器直接读取安全 URI；
* 通知媒体：删除 `cache/temp/notif` 写入，通知使用短期 URI；
* 保存和分享：不再额外复制到 App cache。用户明确“保存到下载目录”仍会产生 MediaStore 明文；分享给第三方后副本超出 App 控制，后续 DLP 必须决定是否允许。

## 升级与清理

创建安全 Matrix 客户端时只清理三处旧接收缓存：

* `cache/temp/media`；
* `cache/temp/voice`；
* `cache/temp/notif`。

清理目标经过 canonical path 校验且必须位于 App cache 根目录内。`cache/uploads` 被刻意排除，避免在上传管道尚未迁移时破坏正在进行的上传。

## 明确边界

本批次没有宣称以下路径已经受保护：

* **上传预处理、转码和缩略图**：现有 Matrix/Rust 上传接口仍以 `File` 为契约，`cache/uploads` 及处理器临时文件尚有明文风险；
* **拍照、裁剪和编辑 scratch**：仍需在后续批次改造为受控输入/输出管道；
* **显式导出**：保存到下载目录、系统相册或第三方 App 后，YiZheChat 无法仅靠本地密钥销毁删除对方副本；
* **真正的网络流式下载**：当前 Rust 内容接口一次性返回完整 `ByteArray`。本实现关闭持久明文，但大媒体仍会短暂存在 App heap，后续应扩展 SDK 为分块输入流或文件描述符回调；
* **内层内容密钥/KMS**：Matrix 服务器侧和备份侧的最终到期撤销属于第三阶段。

## 验证要求

发布前至少完成：

* 图片、GIF、音频、视频首播/暂停/seek/重复播放；
* 通知图片在前台、后台、锁屏和进程被杀后的 fail-closed 行为；
* 分享授权仅对选定目标可读，关闭/TTL/进程死亡后不可再读；
* 升级安装后旧三个目录被清除，上传目录不被误删；
* 篡改 `.yzsf`、`.yzkey`、截断和信封交换时 provider 不输出内容；
* cache、files、noBackup、通知和日志 canary 扫描；
* 低存储、断电、OEM 省电和多账号并发回归。

验收证据必须区分“编译通过”“模块测试通过”“真机链路通过”和“取证验收通过”。
