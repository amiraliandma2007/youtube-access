package com.directaccess.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

object AssetManagerHelper {
    private val REQUIRED_ASSETS = listOf("config.json", "geoip.dat", "geosite.dat")

    fun ensureAssets(context: Context): File {
        val assetDir = File(context.filesDir, "xray_assets").apply { mkdirs() }
        val versionMarker = File(assetDir, ".ver")
        val currentVersion = getAppVersionCode(context).toString()

        val upToDate = versionMarker.exists() &&
            versionMarker.readText().trim() == currentVersion &&
            REQUIRED_ASSETS.all { File(assetDir, it).let { f -> f.exists() && f.length() > 0L } }

        if (upToDate) return assetDir

        for (name in REQUIRED_ASSETS) {
            val target = File(assetDir, name)
            val tmp = File(assetDir, "$name.tmp")
            context.assets.open(name).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "Failed to stage asset $name" }
        }
        versionMarker.writeText(currentVersion)
        return assetDir
    }

    @Suppress("DEPRECATION")
    private fun getAppVersionCode(context: Context): Long {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            pkgInfo.versionCode.toLong()
        }
    }
}
