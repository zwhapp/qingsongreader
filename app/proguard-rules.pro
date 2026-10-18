# OpenReader 混淆规则
# 本应用为阅读器，默认 release 不开启混淆（build.gradle 中 isMinifyEnabled = false）。
# 若你开启混淆，需要注意：
# - Room 实体与 DAO 由注解处理器生成，保持 -keep
# - WebView 的 JavascriptInterface 方法必须保留
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class androidx.room.** { *; }
-keep class com.qingsongreader.data.db.** { *; }
