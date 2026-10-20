# 批次 12：语音和视频通话保护

## 结论和状态

批次 12 已把 Android 端现有 Element Call 真实入口接入统一数据保护基座，当前状态为 `CONNECTED`，不是 `VERIFIED`。

本批次保护的实际链路是：

```text
Matrix 加密房间
  -> Android CallProtectionService 失败关闭门禁
  -> App 内置 Element Call 0.20.0 WebView
  -> Matrix Widget Driver
  -> Element Call / LiveKit WebRTC 实时媒体
  -> TURN/SFU
```

代码和单元测试已证明 Android 入口会实施门禁及本地防泄露控制；尚不能据此宣称 TURN/SFU、服务器配置、运营日志或真实网络包已经完成安全验收。

## 强制安全门

每次建立语音或视频通话前必须同时满足：

1. Matrix 房间已加密；未加密房间不得降级为明文通话。
2. 通话页面必须来自 APK 内置的 `https://appassets.androidplatform.net/element-call/index.html`。
3. 设备状态允许访问本地 Vault，且满足当前企业策略要求的硬件安全等级。
4. 当前 Matrix 账号对应的 Account Vault 已解锁。

门禁是强制基线，不提供本地功能开关。开发者自定义 Element Call URL 在保护链路中也会被拒绝，后续如需自建通话前端，必须新增编译期或签名策略允许清单、资源完整性校验和独立验收，不能恢复任意 URL。

通话建立后持续观察设备信任和 Vault 状态。远程 `STALE/REVOKED/LOST`、本地完整性限制或 Vault 锁定会触发挂断并关闭通话界面。

## 媒体和页面控制

Android 端已实施：

* 加密房间使用 Element Call 的 `PerParticipantKeys` 配置；禁止未加密 fallback。
* WebView 关闭文件访问、ContentProvider 访问、数据库、地理位置、混合内容和普通 HTTP 缓存。
* 仅同源页面可以请求麦克风和摄像头；其他来源或额外 Web 权限全部拒绝。
* 主页面跨源跳转被阻断，TLS 校验错误直接取消连接。
* Widget 消息桥绑定精确 HTTPS origin，并使用每次通话随机 256 位 bridge token；不再接受 `*` 来源。
* 页面释放时停止加载，移除 JS bridge，清理表单、历史、缓存和该 origin 的 WebStorage，再销毁 WebView。
* 应用级 `allowAudioPlaybackCapture=false`，降低其他普通 App 通过 Android playback capture 捕获通话播放音频的风险。
* 通话和来电窗口依据数据保护策略设置 `FLAG_SECURE`，并在支持的系统上禁止最近任务截图。
* 音频通话前台服务只声明麦克风类型；视频通话在摄像头权限已授予时追加摄像头前台服务类型。

实时 PCM、视频帧、WebRTC 编码帧和解密后画面仍会短暂存在于进程、WebView、MediaCodec、GPU 或系统音频链路中。这是运行时系统边界，不能用文件加密消除。

## 通知、日志和录制

* 默认 `HIDDEN` 通知策略会移除来电房间名、头像、正文和真实显示名，只保留通用“受保护通话”提示。
* ActiveCallManager 不再把完整来电通知对象或通话对象写入日志。
* Element Call console 内容不再转发到 Android 日志；WebView、HTTP 和 TLS 错误只记录无 URL、无正文的状态信息。
* Android 客户端没有接入本地通话录音或录像持久化管道，本批次保护会话声明 `allowRecording=false`。

`allowRecording=false` 只是 Android 端安全不变量，不等于已经证明 LiveKit/SFU/运维侧没有启用录制。服务端必须单独禁止 Egress/recording、转码、调试包和媒体对象存储；若未来业务确需录制，必须作为新敏感资产接入每对象 CEK、KMS、保留期、审计和删除协调器，不能复用普通下载目录。

## 与卸载和数据恢复的关系

通话默认不生成可长期恢复的本地媒体文件。本批次进一步阻止 WebView 文件访问和普通缓存，并在释放时清理会话 Web 数据，因此卸载后不应存在由 YiZheChat 主动保存的通话录音录像。

卸载保护仍以密码学断链和“不落盘”为主，不承诺覆盖闪存物理页。若 WebView/OEM、系统组件、Root/Hook 工具、第三方录屏或服务端录制在本批次控制边界之外保存了副本，卸载 YiZheChat 不会删除那些副本。

## 必须完成的验收

上线前至少完成：

1. 两台真实国产 Android 设备的语音、视频、前后摄像头、蓝牙、听筒、扬声器和网络切换通话。
2. 两端确认房间 E2EE，并通过抓包确认媒体不是可直接还原的 RTP 明文；检查 DTLS-SRTP/LiveKit E2EE 实际协商。
3. 自建 TURN/SFU/LiveKit 配置审计：禁录制、禁 Egress、禁媒体 debug dump、日志脱敏、短保留。
4. 锁屏、Vault 锁定、远程吊销、账号退出、强杀、重启和来电转接时，确认通话立即失败关闭或终止。
5. 截图、系统录屏、投屏、普通 playback capture App、通知历史和最近任务缩略图测试。
6. 通话前后对 App 私有目录、WebView 数据目录、cache、日志和 tombstone 做 canary/文件系统镜像扫描。
7. 使用 Root/Hook 实验设备验证运行时风险边界；不得把对普通 App 的阻断表述为能抵御已控制内核或外部摄像设备。

在上述证据完成前，M-005、S-006 和 S-007 仍保留 `运行审计`/服务端待验收状态。
