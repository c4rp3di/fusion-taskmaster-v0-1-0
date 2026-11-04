package com.vigia.taskmaster.datos

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppAlmacen(
    val paquete: String,
    val etiqueta: String,
    val appBytes: Long,
    val datosBytes: Long,
    val cacheBytes: Long,
    val esSistema: Boolean
) {
    val total: Long get() = appBytes + datosBytes + cacheBytes
}

class ResumenAlmacen(
    val totalBytes: Long,
    val libreBytes: Long,
    val appsBytes: Long,
    val cacheBytes: Long,
    val imagenesBytes: Long,
    val videosBytes: Long,
    val audioBytes: Long,
    val apps: List<AppAlmacen>
)

/** Almacenamiento interno con StorageStatsManager (necesita el acceso «uso de apps»). */
object AlmacenRepo {

    @Suppress("DEPRECATION")
    suspend fun leer(c: Context): ResumenAlmacen = withContext(Dispatchers.IO) {
        val ssm = c.getSystemService(StorageStatsManager::class.java)
        val uuid = StorageManager.UUID_DEFAULT
        val usuario = Process.myUserHandle()
        val total = try {
            ssm.getTotalBytes(uuid)
        } catch (e: Exception) {
            0L
        }
        val libre = try {
            ssm.getFreeBytes(uuid)
        } catch (e: Exception) {
            0L
        }
        var apps = 0L
        var cache = 0L
        try {
            val u = ssm.queryStatsForUser(uuid, usuario)
            apps = u.appBytes + u.dataBytes
            cache = u.cacheBytes
        } catch (e: Exception) {
        }
        var imagenes = 0L
        var videos = 0L
        var audio = 0L
        try {
            val x = ssm.queryExternalStatsForUser(uuid, usuario)
            imagenes = x.imageBytes
            videos = x.videoBytes
            audio = x.audioBytes
        } catch (e: Exception) {
        }

        val pm = c.packageManager
        val lista = ArrayList<AppAlmacen>()
        for (info in pm.getInstalledApplications(0)) {
            try {
                val s = ssm.queryStatsForPackage(uuid, info.packageName, usuario)
                val cacheApp = s.cacheBytes
                val datos = maxOf(s.dataBytes - cacheApp, 0L)
                val sistema = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                lista.add(AppAlmacen(info.packageName, info.loadLabel(pm).toString(), s.appBytes, datos, cacheApp, sistema))
            } catch (e: Exception) {
            }
        }
        lista.sortByDescending { it.total }
        ResumenAlmacen(total, libre, apps, cache, imagenes, videos, audio, lista)
    }
}
