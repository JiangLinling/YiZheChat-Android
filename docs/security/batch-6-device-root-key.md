# 批次 6：设备根密钥

> 当前状态：设备证明、服务端审批、远程吊销和硬件 Vault 门禁已经接通，安全功能默认启用；下文保留批次 6 完成时的历史边界。生产环境仍须按仓库根目录 `docs/PRODUCTION_SECURITY_ACCEPTANCE.md` 配置真实证明根、签名指纹和腾讯云资源。

## 实施结论

批次 6 已把真实设备根密钥接入 App 冷启动和 `SensitiveFileStore` 密钥层级。首次启动生成 256 位随机 installation ID 和 Android
Keystore AES-256 DRK；DRK 不可导出，只提供带 AAD 的包装/解包操作。每账号随机 AVK 以 DRK 包装后保存在
`noBackupFilesDir/secure/v1/vault-keys`，新对象的随机 DEK 再由 AVK 包装。

当前真实层级为：

```text
Android Keystore DRK（不可导出，版本 1）
└── 账号随机 AVK（.yzvk，绑定 installation ID 和账号 Vault）
    └── 对象随机 DEK（.yzkey，绑定不可变对象 core）
        └── 图片/语音/视频/附件等 .yzsf 密文
```

本批次是第二阶段的第一个可运行批次，不等于整个第二阶段完成。DAK/服务端证明、账号凭据和数据库密钥迁移、锁屏密钥状态机、远程设备
信任与 KMS 仍未实现。

## 安装身份与失败关闭

installation ID 是 256 位随机值，不使用 Android ID、IMEI、推送 token 或账号标识。记录包含固定 magic/version、长度边界和 SHA-256
损坏校验，以 `fsync` 后重命名发布。DRK 元数据单独保存安装 ID 摘要、DRK 版本、StrongBox 请求状态和 ACTIVE/DESTROYED 状态。

冷启动的 `DeviceRootKeyInitializer` 在账号数据打开前检查：

1. installation ID 记录可验证；
2. DRK 元数据属于同一 installation ID；
3. 元数据为 ACTIVE；
4. 对应 Android Keystore alias 实际存在。

如果 App 私有目录被复制到另一台设备、Android 备份错误恢复了文件、Keystore 条目丢失或元数据被破坏，启动失败关闭，不会在旧元数据下
静默生成新 DRK。首次升级旧版本时没有 DRK 元数据，允许创建一次新层级，并通过兼容路径迁移旧对象信封。

## StrongBox、TEE 和软件等级

Android 9 及以上且系统声明 StrongBox 时优先请求 StrongBox；只在系统明确报告 `StrongBoxUnavailableException` 时退回默认 Android
Keystore。Android 12 及以上读取 `KeyInfo.securityLevel` 区分 StrongBox、TEE、软件和未知；旧系统只能可靠区分“安全硬件内/软件”，因此保守
记录为 TEE，不把“请求过 StrongBox”当成“已经由 StrongBox 保护”。

当前 DRK 等级本身不据此放行企业登录。批次 8 已接入客户端 DAK、challenge statement、证书链和 App 签名证据；Verified Boot、证书链和
OEM 兼容结论仍必须由 Device Registry 验证。因此 `security.hardware_backed_vault` 保持默认关闭和未完成，直至服务端可信判定接通。

## AVK 和对象信封迁移

`.yzvk` 包含版本、账号 Vault 摘要、DRK 版本、installation ID 摘要、GCM nonce、密文和损坏校验；GCM AAD 再绑定固定用途和账号
Vault 摘要，复制到其他账号会认证失败。原始 DRK 从不进入文件或业务 API，AVK 只在一次包装/解包操作的局部作用域出现。

批次 1 的旧 `.yzkey` 格式被明确标记为 `LEGACY_PER_VAULT_KEYSTORE`，新格式标记为 `DEVICE_ROOT_VAULT`，禁止通过“先试新钥、失败再
试旧钥”的模糊降级。成功读取旧对象时，流程在把明文交给调用方前：

1. 用旧 Keystore Vault key 解包 DEK；
2. 创建/加载 DRK 包装的随机 AVK；
3. 将同一 DEK 重包为版本 2 `.yzkey`；
4. 用可恢复的临时文件/备份替换协议提交；
5. 确认该账号已没有旧信封后删除旧 Keystore alias。

迁移被强杀时，下一次读取会恢复旧信封或使用已发布的新信封；失败不返回明文。没有访问到的历史对象保持旧硬件 Keystore 保护，直到读取
迁移或账号删除；账号删除会同时删除 `.yzvk` 和旧 alias。

## 卸载、清除数据和销毁

持久记录位于 `noBackupFilesDir`，不进入 Android Auto Backup。卸载/清除数据由系统删除 installation ID、DRK 元数据、AVK 信封和对象
信封，同时 Android Keystore 删除 App alias；任一侧缺失都足以断开旧密文解密链。重新安装生成新的 installation ID 和 DRK，不能使用复制
回来的旧 `.yzvk/.yzkey/.yzsf`。

显式 `DeviceRootKeyService.destroy()` 先写 DESTROYED 墓碑，再删除 Keystore alias；即使删除调用需要重试，冷启动也不会重新开放旧 Vault。
它是整台安装的密码学擦除能力，当前不直接绑定普通账号退出；账号退出只销毁该账号 AVK。

## 自动验证与剩余真机门槛

JVM 测试覆盖稳定安装身份、用途/账号绑定、信封篡改、复制文件但缺失 Keystore、DRK 销毁、AVK 账号隔离、账号毁钥、整机毁钥和旧对象信封
升级。CI 基线检查 DRK AES-256、随机 GCM、StrongBox 请求、硬件等级分支、冷启动接入、路径登记和备份断链不变量。

仍需在至少两类国产 Android 真机验证：StrongBox/TEE 实际等级、StrongBox 空间耗尽回退、升级旧对象、强杀迁移、低存储、锁屏/重启、清除
数据、卸载重装、OEM 换机/D2D、Root 文件系统镜像和 Keystore alias 缺失。编译和 JVM 测试不能把这些状态提升为 `VERIFIED`。
