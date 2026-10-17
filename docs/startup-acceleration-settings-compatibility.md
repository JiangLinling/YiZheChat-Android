# Startup acceleration settings compatibility

Source implementation: 2026-10-17. This document is not device acceptance.

## Scope and lifecycle

LC-03 (OS permission/settings return), LC-23 (settings guidance). The existing
room-list Announcement, optional dismissal, foreground refresh, and actual grant
plus foreground-service checks are reused. A settings launch is only navigation;
it never grants permission or marks startup acceleration ready. No startup gate,
chat, notification transport, management policy, service configuration, or server
is changed. The reminder remains limited to variants declaring the existing service.

## Ordered routes

| Family | Attempt order | Device evidence for this change |
| --- | --- | --- |
| Xiaomi / Redmi | Existing MIUI own-service SubSettings route; details action; list highlighting; plain accessibility home | NOT_RUN; previous 10S proof belongs only to APK `3a2df8df4350f6a878424b5c8645925111c93d6d87687dabb140fe316999a44b` |
| Samsung | Details action; One UI installed-service list with own-service highlight; generic highlighting; plain home | NOT_RUN |
| OPPO / OnePlus / realme | Details action; generic own-service highlighting; plain home | NOT_RUN |
| vivo / iQOO | Same capability-based common routes | NOT_RUN |
| Huawei Android / Honor | Same capability-based common routes | NOT_RUN |
| Motorola / Pixel / other Android | Same capability-based common routes | NOT_RUN |

Huawei here means a system capable of running this Android APK; native HarmonyOS
is outside this implementation. Highlighting is best effort, not a direct switch.
The details action is permission-restricted on AOSP and many OEM versions. The
common route does not establish direct-switch support on those brands. No reliable
ordinary-app private direct-switch route was established for OPPO, vivo, Huawei or
Honor during this research; do not add guessed fragment/activity names.

The Xiaomi route and its parameters are preserved. Its internal fragment is never
sent to another vendor. The Samsung action is a compatibility probe restricted to
Samsung's settings package, not a claim of native acceptance.

## Recovery and final fallback

Missing activities, permission denials and rejected arguments advance immediately
to the next route. First accepted launch stops the sequence: do not open multiple
settings pages. Every sequence ends with plain `ACTION_ACCESSIBILITY_SETTINGS`,
without a vendor package, activity or extras.

An OEM can accept a launch and then silently finish, ignore the parameters or show
a different page. The app cannot establish the destination merely from dispatch.
After an attempted opening, if the service remains disabled, the same reminder's
button becomes **Open accessibility settings**, explicitly bypassing all optimized
routes and directly using the plain home. The guidance names the own service and
installed/downloaded service lists. This is also available after every route fails.
No automatic redirection on resume. If even the system home is unavailable, return
failure and keep the guide; never claim completion or navigate to unrelated settings.

Authorization without a connected foreground service still shows Recovering, with
the existing recheck action. Declining, returning without enabling, and later
revocation retain the existing state logic. No root, Shizuku, permission writes,
automatic switch clicks or system-consent bypass are used.

## Source evidence and limits

- Android public fallback: https://developer.android.com/reference/android/provider/Settings#ACTION_ACCESSIBILITY_SETTINGS
- AOSP permission on the details activity: https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android14-d1-release/AndroidManifest.xml
- AOSP highlight argument definition/forwarding: https://github.com/aosp-mirror/platform_packages_apps_settings/blob/master/src/com/android/settings/SettingsActivity.java
- AOSP own-service preference identity: https://github.com/aosp-mirror/platform_packages_apps_settings/blob/master/src/com/android/settings/accessibility/AccessibilitySettings.java
- Original report of the Samsung action and highlighting parameters (decompilation observation, not vendor API guarantee): https://stackoverflow.com/a/63214655
- Independent framework implementation confirms the details-action restriction:
  https://github.com/getActivity/XXPermissions/blob/master/library/src/main/java/com/hjq/permissions/permission/special/BindAccessibilityServicePermission.java
- GKD and AutoJs6 also use the public accessibility home for ordinary navigation;
  neither establishes missing OEM direct-switch support.

Automated checks cover route isolation, exact service identity, missing/restricted
entries, last fallback, explicit fallback after return, no duplicate navigation,
and unchanged authorization semantics. They cannot prove a system page exists or
displays the switch. Native acceptance must bind brand, model, OS build and APK
SHA256, and cover direct/list/home landing, refusal, enable/return and revocation.

Rollback: revert the scoped OEM adaptation commit. Previous direct-Xiaomi baseline
is `9f313dd157e103f4e9aa1c61dac1f9d26a86fc18`; retained APK and device evidence are
under `.artifacts/startup-acceleration-guide-20261017` in the parent repository.
