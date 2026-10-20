# 批次 3：通知、日志和数据库

状态：`CONNECTED`（源码与 JVM 测试接入完成；真机、升级安装和移动取证验收未完成）

## 本批次目标

本批次关闭三类容易绕过加密文件仓的旁路：Android 系统通知中的正文/头像/媒体、生产日志与崩溃栈、SQLCipher 删除页和 WAL。它同时把账号退出接到密码学擦除协调器。

## 通知

* 内置策略默认 `NotificationExposure.HIDDEN`。
* 通知解析结果在进入 Android 通知对象前统一经过 `NotificationPrivacySanitizer`。
* `HIDDEN` 只保留通用“有新消息”文案，清除正文、房间名、发送者名、头像、图片 URI、MIME 和 redaction 原因。
* `SENDER_ONLY` 只保留发送者显示名；`CONTENT_ALLOWED` 才允许正文展示和通知媒体预取。
* 隐藏策略下不会下载通知媒体，因此不会为了通知创建图片缓存或短期媒体租约。
* 账号退出后的通知撤销仍由现有 session notification observer 执行；本批次没有把 push payload 变成业务内容载体。

边界：通知到达后 App 进程仍需短暂解析事件才能生成通用通知；本批次保证敏感展示数据不进入 Android 通知对象。Android 历史中已经存在的旧通知必须通过升级/退出/策略切换验收确认清除。

## 日志和崩溃信息

* Release 禁止 Rust 文件 tracing、logcat 输出和 Rust backtrace。
* Release 启动时清除旧明文 tracing 文件，不再创建新的持久 tracing 文件。
* Debug 明文日志是开发逃生口：最多 24 个轮换文件，并删除超过 24 小时的文件。
* Release 崩溃只保存“发生过崩溃”的布尔状态，不持久化线程名、异常消息、堆栈、设备或内存详情。
* Release bug report 不附加 logcat、tracing 文件、旧崩溃栈或 Matrix push rules。用户主动填写的问题描述和主动选择的截图仍会上传，因此属于显式导出边界。
* HTTP BODY 的 Release 硬禁继续由批次 0 的网络拦截器和安全基线检查保护。

旧日志删除中的覆零是升级清理的尽力措施，不作为闪存扇区不可恢复承诺。真正的长期保证来自 Release 不再生成这些明文文件；如果未来需要生产诊断，必须先接入独立 Log Key、结构化脱敏和加密轮换，不能重新启用明文 tracing。

## 数据库

所有经 `SqlCipherDriverFactory` 打开的 App SQLCipher 数据库，在 `onOpen` 统一执行：

* `PRAGMA secure_delete = ON`；
* `PRAGMA cipher_memory_security = ON`；
* `PRAGMA temp_store = MEMORY`；
* `PRAGMA wal_autocheckpoint = 100`。

`DatabaseSecurityMaintenance` 只持有打开数据库的弱引用。账号删除时执行 `PRAGMA wal_checkpoint(TRUNCATE)`，并检查返回的 busy 状态；只有真正获得 checkpoint 锁后才记为成功，随后执行 `PRAGMA optimize`。

边界：Matrix Rust SDK 自己管理的 state/crypto/event-cache 数据库不经过 `SqlCipherDriverFactory`。当前退出流程会关闭 SDK、删除整个 session 目录；但 SDK 内部 PRAGMA、数据库轮换和取证结果仍需单独审计，不能用 App 数据库测试代替。

## 账号退出与密码学擦除

* 媒体保险库改为“Matrix 用户 + Matrix 设备”的 SHA-256 派生作用域，而不是仅按用户 ID；保险库 ID 不含原始用户或设备标识。
* 删除请求只携带用户 ID 的 SHA-256 和显式保险库 ID；日志不记录用户 ID、设备 ID或密钥材料。
* 用户主动退出或账号停用时，顺序为：停止/销毁 Matrix client、删除 session 目录和 session 记录、销毁该设备保险库包装密钥、删除密文文件、checkpoint 并截断所有已打开 App SQLCipher WAL。
* 保险库墓碑使旧保险库不可再次写入；同账号重新登录会获得新的 Matrix 设备作用域，不会复活旧密钥。
* 单内容/单资产删除仍明确返回 `SCOPED_KEY_INDEX_NOT_AVAILABLE`，直到后续内容密钥索引实现，禁止伪报完成。

## 验证证据

自动化覆盖：

* 三种通知曝光策略及默认隐藏；
* SQLCipher 打开加固与 WAL truncate；
* 删除协调器成功、checkpoint 失败重试和未实现的 scoped key index；
* Release 崩溃不保留诊断详情；
* 用户退出提交设备保险库密码学删除请求；
* Release/Debug 日志配置和旧日志清理。

发布前仍必须补：Release 升级安装、通知历史、后台/锁屏通知、账号退出再登录、进程被杀、低存储、数据库 busy/断电、卸载后 Keystore 断链以及文件系统镜像取证测试。
