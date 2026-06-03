# R Plan Beauty Engine - Consumer ProGuard Rules
# Keep all public APIs of the beauty engine
-keep public class com.picme.beauty.egl.** { public *; }
-keep public interface com.picme.beauty.api.** { *; }

# Keep BeautyLogProxy and BeautyLog interface for reflection binding
-keep class com.picme.beauty.log.BeautyLogProxy { public *; }
-keep interface com.picme.beauty.log.BeautyLog { *; }
-keep class com.picme.beauty.log.BeautyLogExtKt { *; }

# Keep app module Logger methods for reflection (called by BeautyLogProxy)
# Note: This rule must be in the app's proguard-rules.pro, not here.
# The app should add: -keep class com.picme.core.common.Logger { public *; }

