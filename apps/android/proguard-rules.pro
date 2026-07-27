# WeChat OpenSDK reflects over its request/response and API implementation classes.
-keep class com.tencent.mm.opensdk.** { *; }

# ML Kit discovers these manifest registrars by class name and invokes
# their public zero-argument constructors.
-keepclassmembers class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
-keepclassmembers class com.google.mlkit.vision.common.internal.VisionCommonRegistrar {
    public <init>();
}
-keepclassmembers class com.google.mlkit.vision.text.internal.TextRegistrar {
    public <init>();
}
