# MapLibre ships its own consumer rules. Nothing else here uses reflection:
# JSON is read with org.json, SharedPreferences and SQLite are plain framework calls.

# Keep readable stack traces in crash reports from release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sherpa-onnx's native library reads these Kotlin classes' fields and methods by name through JNI;
# R8 would otherwise drop or rename the ones Kotlin never touches (NoSuchFieldError: maxActivePaths).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
