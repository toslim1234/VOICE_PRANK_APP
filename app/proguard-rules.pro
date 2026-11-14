# Keep the injector entry point: it is started through app_process by class name.
-keep class com.voiceprank.app.inject.Injector { *; }
-keep class com.voiceprank.app.inject.ShellService { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }

# Keep the app entry points used by the manifest.
-keep class com.voiceprank.app.App { *; }
-keep class com.voiceprank.app.ui.** { *; }
-keep class com.voiceprank.app.service.** { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# AIDL stub used by the Shizuku user service
-keep class com.voiceprank.app.inject.IShell { *; }
-keep class com.voiceprank.app.inject.IShell$Stub { *; }
-keep class com.voiceprank.app.inject.IShell$Stub$Proxy { *; }
-keepclassmembers class com.voiceprank.app.inject.ShellService { public <init>(); }
