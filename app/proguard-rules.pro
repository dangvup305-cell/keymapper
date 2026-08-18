# ---------------- KeyMapper 混淆规则 ----------------

# 保留 kotlinx.serialization 生成的序列化器与被标注模型
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 领域模型（JSON 字段名必须稳定，禁止混淆字段名）
-keep class com.keymapper.domain.model.** { *; }
-keepclassmembers class com.keymapper.domain.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.keymapper.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 无障碍服务 / 前台服务由系统按类名实例化，必须保留
-keep class com.keymapper.accessibility.KeyMapperAccessibilityService { *; }
-keep class com.keymapper.service.KeepAliveService { *; }
-keep class com.keymapper.service.FloatingWindowService { *; }
-keep class com.keymapper.MainApplication { *; }

# 自定义 View 由 XML 反射实例化
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Koin
-keep class org.koin.** { *; }
-keepclassmembers class * {
    public <init>(...);
}

# 枚举（序列化依赖 name）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
