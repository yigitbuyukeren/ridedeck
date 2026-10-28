# MapLibre ships its own consumer rules. Nothing else here uses reflection:
# JSON is read with org.json, SharedPreferences and SQLite are plain framework calls.

# Keep readable stack traces in crash reports from release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
