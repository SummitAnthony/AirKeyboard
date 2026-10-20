# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# project.txt file (see the section on Java plugin for details).

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ZXing
-keep class com.google.zxing.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.squareup.okhttp.** { *; }
-keep interface com.squareup.okhttp.** { *; }

# Timber
-keep class timber.** { *; }
-keep interface timber.** { *; }

# Kotlin
-keepclassmembers class kotlin.Metadata {
    *** JvmMetadataVersion();
    *** bytecodeVersion();
    *** extraInt;
    *** extraString;
}
