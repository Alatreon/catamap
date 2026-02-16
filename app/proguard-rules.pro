# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Garder les classes de modeles (pour Gson/serialisation)
-keep class com.brewdog.catamap.data.models.** { *; }
-keep class com.brewdog.catamap.domain.annotation.models.** { *; }

# SubsamplingScaleImageView
-keep class com.davemorrissey.labs.subscaleview.** { *; }

# Material Components
-keep class com.google.android.material.** { *; }

# Supprimer les logs en release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}