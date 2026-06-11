# R Plan Beauty Engine - Consumer ProGuard Rules
# Keep all public APIs of the beauty engine
-keep public class com.mamba.picme.beauty.egl.** { public *; }
-keep public interface com.mamba.picme.beauty.api.** { *; }

# Keep BeautyLogProxy and BeautyLog interface for reflection binding
-keep class com.mamba.picme.beauty.log.BeautyLogProxy { public *; }
-keep interface com.mamba.picme.beauty.log.BeautyLog { *; }
-keep class com.mamba.picme.beauty.log.BeautyLogExtKt { *; }

# Keep app module Logger methods for reflection (called by BeautyLogProxy)
# Note: This rule must be in the app's proguard-rules.pro, not here.
# The app should add: -keep class com.mamba.picme.core.common.Logger { public *; }

