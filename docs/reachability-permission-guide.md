# 消息和来电提醒设置：品牌路径依据

适用范围：小米／REDMI、vivo／iQOO、OPPO／一加、荣耀、运行 Android／EMUI 的华为手机。三星、中兴及其他 Android 手机使用系统通用设置入口。本页只记录引导路径依据；某个系统版本能否准确跳转、提醒能否实际弹出，仍须用对应真机验证。

| 手机 | 通知、悬浮和锁屏 | 自启动及后台 | 官方依据 |
| --- | --- | --- | --- |
| 小米／REDMI | 设置 → 通知与状态栏 → 悬浮通知／锁屏通知；另在应用权限 → 其他权限里检查独立的“锁屏显示” | 应用信息 → 自启动；“后台弹出页面”是另一项权限，默认可能被拒绝 | [小米悬浮通知](https://www.mi.com/global/support/faq/details/KA-517581/)、[小米锁屏显示权限说明](https://dev.mi.com/docs/appsmarket/technical_docs/adaptation_FAQ/)、[后台弹出页面](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625)、[自启动](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624) |
| vivo／iQOO | 设置 → 通知与状态栏 → 应用通知管理 → 本应用 → 允许通知、悬浮通知、锁屏通知；锁屏通知中可开“收到通知时亮屏” | 设置 → 应用与权限 → 权限管理 → 权限 → 自启动；部分版本另有“关联启动” | [vivo 通知与亮屏](https://kefu.vivo.com.cn/robot/imgmsgData/ed5094a0125a42cbad6c1a117866cc55/index_1.html)、[vivo 自启动和关联启动](https://kefu.vivo.com.cn/robot/imgmsgData/161d9d1d268d44ddad8410a0474bc614/index_1.html) |
| OPPO／一加 | 设置 → 通知与状态栏 → 通知管理 → 本应用 → 横幅、锁屏；“显示在其他应用上层”是单独入口 | 自启动入口随 ColorOS 版本变化，优先试跳转页；找不到时搜索“自启动”，旧版可查手机管家。此项还没有同品牌真机核对 | [OPPO 通知说明](https://support.oppo.com/cn/answer/?aid=2191031)、[OPPO 手机管家](https://support.oppo.com/co/answer/?aid=neu119)、[一加通知手册](https://service.oneplus.com/content/dam/support/user-manuals/cn/OnePlus%209R%20User%20Manual_CN.pdf) |
| 荣耀 | 设置 → 通知和状态栏 → 本应用 → 允许通知、横幅、锁屏 | 设置 → 应用 → 应用启动管理 → 关闭自动管理 → 允许自启动、关联启动、后台活动 | [荣耀消息不提醒](https://www.honor.com/cn/support/content/zh-cn00685514/) |
| 华为 Android／EMUI | 设置 → 应用管理 → 本应用 → 通知管理 → 横幅、锁屏；可搜索“通知亮屏提示” | 设置中搜索“应用启动管理” → 关闭自动管理 → 允许自启动、关联启动、后台活动 | [华为第三方消息不提醒](https://consumer.huawei.com/cn/support/content/zh-cn15872069/)；只采用该页 Android 兼容版本的路径 |

“悬浮通知／横幅通知”是系统消息弹窗，“悬浮窗／显示在其他应用上层”是应用自己显示的浮层，两者不能互相代替。息屏后的消息提醒依赖锁屏通知和设备提供的亮屏开关；来电接听界面还要检查全屏来电及后台弹窗。Android 14 及以上可以检查和打开[全屏来电设置](https://developer.android.com/about/versions/14/behavior-changes-14)。现有来电提醒还会单独引导“勿扰模式下允许来电”，并在系统授权后刷新来电通知通道；不能仅凭进入设置页认定来电已能响铃。

“权限使用记录”是查看过去权限使用情况的记录页，不是一个能打开的提醒权限，因此不把它当成待开启项目。[Android 11 及以后](https://developer.android.com/about/versions/11/privacy/permissions)会先打开悬浮窗应用列表，用户还需选中本应用。厂商私有设置页可能在系统更新后变动：先试系统提供的单应用入口；厂商入口打不开时退回应用信息或系统设置，并在页面写明手动路径。不能把“跳转成功”当成“弹窗已验证”。

## 两台小米真机的验收口径

待占用会话测完并释放 10S、13 后，先核对两台机的系统版本、应用包名与同一 APK SHA。按用户授权，在 13 获取邀请码，让 10S 退出当前账号并注册新账号。新账号进入空聊天列表时，逐项检查提示是否出现、设置按钮实际打开哪一页、返回后能否读到系统授权；无法读取的厂商开关只记录用户确认，不记作系统已授予。随后让 13 向 10S 发消息并发起语音及视频来电，分别在桌面、锁屏、息屏和勿扰状态观察通知、亮屏、铃声与可用的接听/拒绝操作。每一项记录实际画面与结果；仅有通知到达或设置页打开，不算来电弹窗验收通过。

## 2026-10-14 小米实测进度

- 已等其他会话释放设备后取得租约；同一安装包装到 10S、13。10S 退出旧账号，使用 13 生成的邀请码注册新账号并进入聊天列表；13 原账号保留。
- 10S 的应用通知、悬浮通知、锁屏通知步骤能打开乙者专属通知页。实机“响铃通话”分类原本允许通知，但悬浮通知关闭、锁屏完全不显示、声音为“无”；“消息通知”分类虽然允许通知及悬浮通知，声音为“无”、振动关闭、锁屏完全不显示。已开启“通话／响铃通话／消息通知”分类的铃声、振动和锁屏显示，以及来电分类悬浮通知。引导文案已补上必须进入“消息通知”分类逐项检查。
- 10S 的后台弹窗 AppOps 10021、自启动 AppOps 10008、悬浮窗 AppOps 均为允许；引导按系统读数跳过，不要求用户重复打开。厂商私有状态不能读取时才让用户手动确认。
- 真机继续核对发现小米另有独立的“锁屏显示”：10S、13 均能从 MIUI 权限编辑页进入“其他权限”，看到“锁屏显示”“后台弹出界面”“显示悬浮窗”。10S 的“锁屏显示”原本为“始终允许”；临时切换为拒绝时 AppOps 10020 变为 `ignore`，恢复允许后回到 `allow`。因此引导新增独立的“锁屏显示来电界面”一步，能读到 10020 已允许时自动跳过，否则引导用户打开。跳转证据为 `.artifacts/reachability-guide-20261014/10s-other-permissions-bottom.xml` 与 `13-other-permissions.xml`。13 的系统版本为 `OS3.0.305.0.WMACNXM`；两台手机应用信息页都可直接看到“自启动”。
- 13 对 10S 发起视频来电：10S 在桌面自动显示完整接听界面；息屏后再次拨打，10S 唤醒并显示接听／拒绝。最终版装到两台手机后，13 发起语音来电，10S 从桌面自动显示完整的接受／拒绝界面。10S 临时开启勿扰后再接语音来电，桌面顶部出现可直接“接听／拒接”的通知卡片，未自动铺满全屏；此项只记为可操作来电弹窗通过，不能记作全屏通过。对应画面在 `.artifacts/reachability-guide-20261014/`。其他品牌真机尚未验收。
- 13 发出的测试消息确已进入 10S 聊天列表并产生系统通知；锁屏手动亮屏后可看到“你有新消息”的提醒。10S 停在桌面时，13 再次发消息，顶部实际出现乙者悬浮卡片，内容为保护聊天内容的“你有新消息。”；截图为 `.artifacts/reachability-guide-20261014/floating-window-found-202430.png`，同时记录到已绘制且可见的应用悬浮窗 `floating-window-found-202430.txt`。普通消息顶部弹窗已通过真机验证。该版本收到普通消息时仍未观察到自动从息屏唤醒，消息自动亮屏继续列为未通过。
- 10S 为澎湃 OS `V816.0.4.0.TGACNXM`。已逐项检查乙者通知页、系统“锁屏”页及其下方入口，尚未找到独立“收到通知时亮屏”开关；“锁屏”页只有抬起亮屏、双击亮屏等项目，界面记录在 `.artifacts/reachability-guide-20261014/10s-lock-settings.xml` 和 `10s-lock-settings-bottom.xml`。小米官方对部分其他机型给出的路径是“设置 → 锁屏 → 收到通知时亮屏”，不能直接套到这台 10S。引导按钮仍打开乙者通知页，先核对锁屏通知；这台系统找不到独立开关时允许继续。视频来电息屏唤醒已单独通过。
- 前一版 APK 为 `.artifacts/reachability-guide-20261014/build-20261014-195947/YiZheChat-reachability-next-debug.apk`，SHA-256 为 `126230bc2e779bfe75334924924633f57c42652db0c4cac1d3c17c901cca055e`。当时已完成冻结、构建和静态校验，10S、13 安装哈希一致，来电与消息弹窗证据如上。10S 测试时临时关闭的全局静音已恢复为开启；临时开启的勿扰已恢复为关闭。
- 当前最终源码包为 `.artifacts/reachability-guide-20261014/build-20261014-205258/YiZheChat-reachability-next-debug.apk`，SHA-256 为 `5ad67a423906f3dd6da6d4eddfc8a59810806667a5f7ba53d6c316d4eb86a756`，静态源码和制品校验通过；10S、13 安装后的 `base.apk` 哈希均与它完全一致。来电权限补充修复使用户略过一项后仍会看到后续“全屏来电／后台活动／勿扰访问”提醒，专项单测 10 项通过。13 上临时关闭 AppOps 10020 后，最终包出现“允许锁屏显示来电界面”引导；按钮打开小米权限编辑页；恢复“始终允许”后引导自动进入下一项。证据为 `13-final-lock-display-step.xml`、`13-final-lock-display-settings-open.xml`、`13-final-lock-display-granted-next.xml`。
- 当前最终包实测：10S 熄屏时，13 发起视频来电，10S 自动唤醒并展示完整“拒绝／接受”界面，截图为 `.artifacts/reachability-guide-20261014/10s-final-lock-video-call.png`。13 发出的普通消息 `FINAL2115` 在 10S 锁屏上显示“你有新消息”的通知；这次因 10S 仍处于锁屏，不能作为桌面顶部弹窗证据。随后解锁到桌面再发 `FINALDESK2119`，13 显示 `KMS is unavailable or not configured.` 且该消息标记“发送失败”；错误和发送失败证据为 `13-final-desk-message-after.xml`、`13-kms-error-after.xml`。这次无悬浮窗不能算功能失败。
- 改由 10S 向桌面上的 13 发普通消息时，13 的 `SYSTEM_ALERT_WINDOW` 仍是 `ignore`，没有顶部卡片。通过最终包所用的系统悬浮窗入口，13 先进入应用列表，再选中 YiZheChat Next，把“允许显示在其他应用的上层”打开；AppOps 变为 `allow`。10S 再发一条消息后，13 桌面顶部实际出现乙者“你有新消息。”卡片。截图为 `.artifacts/reachability-guide-20261014/floating-window-found-93ca8f11-214409.png`，同名 `.txt` 是可见 `APPLICATION_OVERLAY` 窗口记录。至此，最终包普通消息桌面弹窗在 13 真机通过，10S 的前一版也有同类画面；13 悬浮窗权限保持为用户要求的允许状态。
