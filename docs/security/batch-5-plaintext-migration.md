# 批次 5：明文迁移

## 实施结论

批次 5 已完成旧版本持久明文的安全切换。迁移在 tracing、Matrix 客户端和账号界面创建前执行；任何注册目标无法清除、迁移台账无法可靠
提交或安全存储维护失败时，App 启动会失败关闭，不允许带着已知旧明文继续进入业务界面。

`security.secure_file_store` 已标记为完成能力并固定为启用。旧安装在实验功能 DataStore 中保存的 `false` 不再覆盖安全默认值，避免升级后回退到
Rust SDK 普通明文媒体文件路径。

## 为什么瞬态文件采用清除而不是复制

旧缓存和 scratch 文件没有稳定的账号、房间、事件、用途、引用计数及 Vault 归属元数据。把它们随意复制进某个 Vault 会产生无法验证归属、
无法由业务继续引用、也无法按账号正确删除的“孤儿密文”。因此本批次采用以下规则：

* 权威业务状态仍在 Matrix/草稿数据库中的内容，由业务重新拉取或重新生成；
* 无可靠账号和业务引用的缓存、上传、编辑、录音、日志及截图暂存直接清除；
* 能确定账号和用途的新接收媒体继续由批次 2 的 `SensitiveFileStore` 写入加密对象；
* 系统相册、下载目录和第三方 App 副本不属于 App 私有迁移范围，不伪装成已清除。

这是一种安全 cutover，而不是保留无归属明文的兼容性搬运。

## 覆盖目标

迁移器只允许以下显式登记目标：

* `cache/temp/media`、`cache/temp/voice`、`cache/temp/notif`；
* `cache/uploads`、`cache/edited-media`、`cache/voice_recordings`；
* cache 根目录直接文件，包括旧媒体处理器、相机 `photo.jpg`/`video.mp4` 和原文件名重命名残留；子目录仍按白名单单独处理；
* `cache/logs`；
* `files/screenshot.png`。

目录目标必须经过 canonical path 校验并保持在指定 cache/files 根目录内。迁移器不会扫描任意目录，不会删除 `sessions`、SQLCipher 数据库、
通知声音、HTTP cache、下载目录、相册或其他 App 文件。Release 启动还会清空旧 `elementx_crash` 明文崩溃详情；Debug 仍遵循批次 3 的
24 小时诊断策略。

## 持久化与恢复

进度保存在 `noBackupFilesDir/secure/v1/plaintext-migration`：

1. 先写新的不可变 revision；
2. `flush` 和 `fsync` 后重命名发布；
3. 每清完一个目标立即提交该步骤；
4. 最新 revision 损坏时报告损坏并回退到上一个可验证版本；
5. 全部目标和 `SensitiveFileStore.runMaintenance()` 成功后写 `complete.yzpm`；
6. 完成后每次冷启动仍重复执行无状态清理，移除上一进程崩溃或强杀遗留的 scratch 明文。

记录只包含格式版本、步骤、尝试次数和错误码，不包含账号、文件名、正文、令牌、路径清单或密钥。

## 明确边界

本批次解决“升级和冷启动时旧明文残留”，没有把仍以 `File` 为契约的运行时上传管道改造成加密流：

* 上传预处理、缩略图、视频转码、图片编辑和语音录制在当前进程运行期间仍可能生成普通文件；
* 这些文件会在成功/取消的既有清理或下次冷启动时清除，但进程存活期间仍有取证窗口；
* 要关闭该窗口，后续批次必须改造 `MediaUploadInfo`、Rust `UploadSource.File`、编辑器和录音器契约，而不是继续扩展启动扫描；
* Matrix Rust SDK 内部数据库、地图缓存和系统通知历史仍按各自批次审计；
* 闪存物理块不依赖覆盖写，本批次以“不再生成 + 密码学存储 + 删除旧逻辑文件”为主，真实设备仍需文件系统镜像验证。

## 验收要求

自动测试覆盖：全部注册目标清除、无关文件保留、进度跨实例恢复、清理失败后续跑、损坏 revision 回退、完成标记篡改拒绝，以及完成后新遗留
scratch 的再次清理。发布前仍需补齐升级安装、各步骤强杀、低存储、只读/异常文件、Debug/Release、多个 OEM、文件系统镜像和 canary 扫描。
