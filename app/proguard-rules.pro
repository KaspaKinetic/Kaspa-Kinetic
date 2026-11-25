-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Kotlinx Serialization (protects .kinetic, KKCV1, .kpass, SilAbiArtifact, DataStore models)
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Kaspa Kinetic domain, covenant, and explorer models
-keep class com.kaspakinetic.wallet.core.model.** { *; }
-keep class com.kaspakinetic.wallet.core.covenants.** { *; }
-keep class com.kaspakinetic.wallet.core.explore.** { *; }

# Native JNI QR scanner (io.github.zxing-cpp:android:2.3.0)
-keep class zxingcpp.** { *; }

# CameraX & Camera2 interop
-keep class androidx.camera.** { *; }
-keep class * extends androidx.camera.core.impl.ConfigProvider { *; }

# gRPC / OkHttp / Protobuf & BouncyCastle cryptography
-keep class io.grpc.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class org.bouncycastle.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn io.grpc.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn org.codehaus.mojo.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
-dontwarn okio.**
