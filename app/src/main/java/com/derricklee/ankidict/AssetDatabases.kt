package com.derricklee.ankidict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileNotFoundException

/**
 * Copies [dbName] out of APK `assets/` into the app's internal databases directory on first open
 * AND whenever the installed APK has been updated more recently than the extracted file on disk,
 * then opens it read-only. Returns `null` if the asset is not bundled (e.g. gitignored optional DBs
 * on a fresh checkout).
 */
fun openBundledDatabase(context: Context, dbName: String): SQLiteDatabase? {
    val dbFile = context.getDatabasePath(dbName)
    val apkUpdateTime = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    if (!dbFile.exists() || dbFile.lastModified() < apkUpdateTime) {
        val bundled = try {
            context.assets.open(dbName)
        } catch (e: FileNotFoundException) {
            null
        }
        if (bundled != null) {
            dbFile.parentFile?.mkdirs()
            val tmpFile = File(dbFile.parentFile, "$dbName.tmp")
            bundled.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmpFile.renameTo(dbFile)) {
                tmpFile.copyTo(dbFile, overwrite = true)
                tmpFile.delete()
            }
        } else if (!dbFile.exists()) {
            return null
        }
    }

    return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
}
