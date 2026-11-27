# Keep kotlinx.serialization serializers & @Serializable models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class tech.codesky.dockdeploy.**$$serializer { *; }
-keepclassmembers class tech.codesky.dockdeploy.** {
    *** Companion;
}
-keepclasseswithmembers class tech.codesky.dockdeploy.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio optional platform classes
-dontwarn okio.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
