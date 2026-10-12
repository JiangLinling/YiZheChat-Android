# HMS Push entry points are instantiated by HMS Core through the manifest.
-keepattributes *Annotation*,Exceptions,InnerClasses,Signature
-keep class com.huawei.hms.** { *; }
-keep class com.huawei.updatesdk.** { *; }
-keep class io.element.android.libraries.pushproviders.huawei.YiZheHuaweiMessagingService { *; }

# HMS checks these optional Huawei-ROM and analytics APIs reflectively. They are absent from the
# public Android SDK and from the minimal Push dependency graph, so R8 must not treat them as
# required application classes. Keep this list exact; do not suppress other missing-class errors.
-dontwarn android.telephony.HwTelephonyManager
-dontwarn com.huawei.android.os.BuildEx$VERSION
-dontwarn com.huawei.hianalytics.process.HiAnalyticsConfig$Builder
-dontwarn com.huawei.hianalytics.process.HiAnalyticsConfig
-dontwarn com.huawei.hianalytics.process.HiAnalyticsInstance$Builder
-dontwarn com.huawei.hianalytics.process.HiAnalyticsInstance
-dontwarn com.huawei.hianalytics.process.HiAnalyticsManager
-dontwarn com.huawei.hianalytics.util.HiAnalyticTools
-dontwarn com.huawei.libcore.io.ExternalStorageFile
-dontwarn com.huawei.libcore.io.ExternalStorageFileInputStream
-dontwarn com.huawei.libcore.io.ExternalStorageFileOutputStream
-dontwarn com.huawei.libcore.io.ExternalStorageRandomAccessFile
