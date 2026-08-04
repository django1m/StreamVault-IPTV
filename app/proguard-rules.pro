# StreamVault ProGuard Rules
# ============================================================
# Toutes les règles ciblant Hilt/Dagger, Retrofit, OkHttp, Gson, Room,
# Coroutines, Compose, Coil, Media3, Kotlin sont gérées par les
# consumer-rules bundled de ces bibliothèques (versions du projet :
# Retrofit 2.12, Gson 2.11, Room 2.8.4, Coroutines 1.10.2, AGP 8.10).

# ── Attributs (signatures Retrofit/Gson, stacktraces lisibles, annotations runtime) ──
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ── libVLC — résolution JNI par nom (libvlcjni.so) ──
# Le code natif appelle des classes/méthodes Java par nom exact ;
# renommer ou élider provoque UnsatisfiedLinkError.
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# ── Modèles Gson sans @SerializedName ──
# BackupManagerImpl, RecordingManagerImpl, EntityMappers utilisent Gson
# avec réflexion sur les noms de champs.
-keepclassmembers class com.streamvault.domain.model.** { <fields>; }
-keepclassmembers class com.streamvault.domain.manager.BackupData { <fields>; }
-keepclassmembers class com.streamvault.domain.manager.ProtectedCategoryBackup { <fields>; }
-keepclassmembers class com.streamvault.domain.manager.ScheduledRecordingBackup { <fields>; }
