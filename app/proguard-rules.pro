# Keep APK signing and binary resource parser metadata intact.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn com.reandroid.**
