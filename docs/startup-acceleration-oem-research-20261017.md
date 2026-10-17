# 启动加速设置入口：跨品牌调查与适配依据

调查日期：2026-10-17。范围：普通 Android App 从用户点击进入系统无障碍服务设置。
**调查发现、源码接入、自动测试、真机通过是四种不同结论。** 本文不宣称全品牌直达。

## 一、已查清的共性

| 实际情况 | 证据 | 适配决定 |
| --- | --- | --- |
| Android 公共 API 只有无障碍设置首页入口，无指定服务参数；甚至该 Activity 也可能不存在 | [Android Settings 文档](https://developer.android.com/reference/android/provider/Settings#ACTION_ACCESSIBILITY_SETTINGS) | 最后保留不带任何厂商参数的 `ACTION_ACCESSIBILITY_SETTINGS`；捕获入口不存在，不把失败当成功 |
| `ACCESSIBILITY_DETAILS_SETTINGS` 确有其事，但 AOSP 给对应 Activity 加了 `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` 权限 | [AOSP Android 14 Manifest](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android14-d1-release/AndroidManifest.xml)；10S 既有系统取证 | 仍可尝试 ROM 暴露的详情入口，拒绝时立刻退到下一条；不能承诺 Android 版本高就可用，也不申请系统权限绕过 |
| AOSP 设置页能接收 `:settings:fragment_args_key` 和参数 Bundle，服务偏好项键由组件名生成 | [SettingsActivity 源码](https://github.com/aosp-mirror/platform_packages_apps_settings/blob/master/src/com/android/settings/SettingsActivity.java)、[AccessibilitySettings 源码](https://github.com/aosp-mirror/platform_packages_apps_settings/blob/master/src/com/android/settings/accessibility/AccessibilitySettings.java) | 传实际安装包的完整服务组件，尝试定位/高亮；这是定位提示，不是自动进入详情或授权 |
| 跳转无异常只证明请求被接受，不能证明页面正确，更不证明权限已打开 | 现有项目 10S 首轮普通无障碍首页失败案例；LC-03 的 OS authority 约束 | 保留真实授权和服务运行检测；给返回后仍未开启的用户一个明确的首页保底按钮 |
| Android 13+ 部分安装来源会触发“受限制的设置” | [Google 官方说明](https://support.google.com/android/answer/12623953?hl=zh-Hans) | 这是开关授权限制，不是跳转故障；保留系统确认，不自动改开关，不把限制误判为入口缺失 |
| 路径会随 ROM 改变，厂商名称本身不证明某个类可用 | 厂商支持资料标注适用机型/版本；既有 MIUI 实测仅一机型 | 只接入有来源的路径，按实际调用结果继续；不猜活动名，不向所有 ROM 注入小米内部 Fragment |

## 二、品牌调查结果

| 品牌/系统 | 找到的实际依据 | 本轮处理 | 证据缺口 |
| --- | --- | --- | --- |
| 小米/红米 MIUI | 本地 10S Settings.apk、导出 Activity、Fragment 参数、安全校验代码及普通 App 点击截图 | 完整保留原有直达服务开关路径，失败后走详情请求、定位、首页 | 既有原包 10S 已通过；本轮新包及其他小米/HyperOS 未实测 |
| 三星 One UI | [三星官方说明](https://www.samsung.com/us/support/answer/ANS10001906/)证明“已安装应用”入口；[开发者原始反编译报告](https://stackoverflow.com/a/63214655)给出 `com.samsung.accessibility.installed_service` 动作 | 详情请求失败后，先尝试三星已安装服务列表并定位乙者，再尝试通用定位和首页 | 专用动作是历史实际观察，不是官方稳定 API；未获得当前 One UI 系统包或真机，不能称直接到开关 |
| vivo/iQOO | [2021 年开发者原始系统取证](https://hjw45611.github.io/blog/2021/07/09/Vivo%E8%B7%B3%E8%BD%AC%E6%97%A0%E9%9A%9C%E7%A2%8D%E8%BE%85%E5%8A%A9%E5%8A%9F%E8%83%BD%E9%A1%B5%E9%9D%A2/)发现 `VivoSubSettings` 与 `extra=fragment:...AccessibilitySettings` | 先请求服务详情，再尝试准确组件定位，最后首页 | 旧资料只证明首页，未给具体 ROM 版本或服务详情允许列表；不把旧内部容器注入推测升级为当前直达方案 |
| OPPO/一加/realme | [OPPO 支持页](https://support.oppo.com/en/answer/?aid=neu4951)、[一加官方用户手册](https://service.oneplus.com/content/dam/support/user-manuals/cn/OnePlus%209%20User%20Manual_CN.pdf)确认系统无障碍功能及菜单 | 服务详情请求、组件定位、首页 | 本次公开资料检索未找到可核验的普通 App 专用直达服务开关 API；没有 ColorOS/OxygenOS/realme UI 真机证据 |
| 华为 Android 兼容系统 | [华为 HarmonyOS 3 适用机型支持文档](https://consumer.huawei.com/cn/support/content/zh-cn15938614/)确认辅助功能下的无障碍入口 | 服务详情请求、组件定位、首页 | 菜单文档不是 Android Intent 合同；纯鸿蒙不在 APK 适配范围；未获得 EMUI/HarmonyOS Android 兼容机型实测 |
| 荣耀 MagicOS | [荣耀支持文档](https://www.honor.com/cn/support/content/zh-cn15869276/)确认无障碍入口；公开 MDM 能力属于管理授权场景 | 服务详情请求、组件定位、首页 | MDM API 不能当普通应用能力；未找到可确认的普通应用直达协议，没有真机 |
| Pixel/摩托罗拉/其他 Android | AOSP 公共入口、详情权限和设置参数的源码 | 服务详情请求、组件定位、首页 | AOSP 源码不能证明厂商的最终设置页；未实测 |

“没找到可靠的专用路径”只表示本次调查结论，不等于证明该厂商永远不能直达。
本轮不将“支持请求详情、定位、兜底”标记为“已验证直接到开关”。

## 三、成熟项目交叉核对

直接读取项目作者源码，未引入这些库或复制其授权流程：

- [XXPermissions](https://github.com/getActivity/XXPermissions/blob/master/library/src/main/java/com/hjq/permissions/permission/special/BindAccessibilityServicePermission.java)：作者明确说明详情 Action 对普通应用不可用，其实现使用公共无障碍首页。
- [GKD](https://github.com/gkd-kit/gkd/blob/main/gkd-app/src/androidMain/kotlin/li/gkd/app/util/IntentUtils.kt)：普通打开操作同样使用无障碍首页。
- [AutoJs6](https://github.com/SuperMonster003/AutoJs6/blob/master/app/src/main/java/org/autojs/autojs/core/accessibility/AccessibilityTool.kt)：`launchSettings` 使用首页，提醒用户选择自己的服务；另有依赖特权的启用方式，未采用。

这说明不能把“网上存在代码”直接等同于“普通 App 在所有 ROM 上可以直达”。
也不能把自启动管理、通知权限、电池白名单的品牌跳转代码当成本任务的无障碍开关入口。

## 四、最终选择及后续验证口径

1. 保留已经由普通 App 证明有效的小米直达实现。
2. 其他品牌优先向系统请求自身服务详情；三星增加更近的已安装服务页。
3. 详情无法打开时，尽量用完整组件身份定位乙者，防止误指其他 flavor 或服务。
4. 前面路径失败才进入不带厂商参数的无障碍首页。对于“接受跳转但没到正确页”，返回后提供用户主动选择的首页保底，不自动循环打开设置。
5. 继续使用现有提醒、拒绝后可关闭、设置返回重查、有限服务恢复重查。账号、管理端、安全准入和聊天链路不参与这次改动。

真机记录至少包含：品牌/型号、Android/ROM 版本、安装 APK SHA256、普通 App 点击后的真实页面、是否仍需选择乙者、拒绝/返回/开启后的状态。只执行 `adb am start` 不能替代普通应用权限下的跳转验收。

若获得其他品牌真机，应先采集该系统 Settings 的导出入口、调用权限和实际导航结果，再接入可证明的更短路径；不能仅因某个 `SubSettings` 存在就尝试未核实的内部 Fragment。
