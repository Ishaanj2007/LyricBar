# ==============================================================================
# LyricBar R8 / ProGuard Rules (v2.7.0 Production Release)
# Minimal, evidence-based rules for runtime reflection and serialization.
# ==============================================================================

# ------------------------------------------------------------------------------
# Stack Trace & Debuggability
# Preserve line numbers and source file names for crash reports
# ------------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve runtime annotations and generic type signatures for reflection/serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ------------------------------------------------------------------------------
# WorkManager Background Workers
# UpdateCheckWorker is instantiated dynamically via reflection by WorkManager
# ------------------------------------------------------------------------------
-keep class com.example.data.updater.UpdateCheckWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ------------------------------------------------------------------------------
# Room Database & SQLite Entities
# Room generates LyricBarDatabase_Impl and expects entities and DAO interfaces
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.local.LyricBarDatabase_Impl {
    public <init>();
}
-keep class com.example.data.local.entity.** { *; }

# ------------------------------------------------------------------------------
# DataStore Preference Enums
# Deserialized by name at runtime via Enum.valueOf(String)
# ------------------------------------------------------------------------------
-keepclassmembers enum com.example.data.preferences.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ------------------------------------------------------------------------------
# Backup & Restore Data Models
# BackupZipCodec serializes/deserializes backup archive models to/from JSON via Gson.
# Field names must remain intact across versions and migrations.
# ------------------------------------------------------------------------------
-keepclassmembers class com.example.data.backup.** {
    <fields>;
    <init>(...);
}
-keepclassmembers enum com.example.data.backup.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ------------------------------------------------------------------------------
# OkHttp
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
