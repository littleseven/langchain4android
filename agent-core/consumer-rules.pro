# mamba-agent ProGuard rules
# Keep all public APIs
-keep public class com.mamba.agent.** { *; }
-keep public interface com.mamba.agent.** { *; }

# Keep Jackson annotations for JSON serialization
-keep @com.fasterxml.jackson.annotation.** class * { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}

# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep SLF4J
-keep class org.slf4j.** { *; }

# Keep ServiceLoader SPI files
-keepclassmembers class META-INF.services.** { *; }
