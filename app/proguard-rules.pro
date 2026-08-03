# Keep terminal emulator and proot runner classes
-keep class com.linuxterminal.mobile.terminal.** { *; }
-keep class com.linuxterminal.mobile.proot.** { *; }

# Keep native method declarations
-keepclassmembers class * {
    native <methods>;
}

# Preserve Kotlin metadata
-keep class kotlin.Metadata { *; }
