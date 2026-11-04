package com.vigia.taskmaster.datos

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DiaTrafico(val etiqueta: String, val wifi: Long, val movil: Long) {
    val total: Long get() = wifi + movil
}

class AppTrafico(val uid: Int, val wifi: Long, val movil: Long) {
    val total: Long get() = wifi + movil
}

/** Tráfico de datos con NetworkStatsManager (necesita el acceso «uso de apps»). Wifi y datos móviles van por separado. */
object TraficoRepo {

    class Rango(val desde: Long, val hasta: Long, val etiqueta: String)

    private fun gestor(c: Context): NetworkStatsManager = c.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

    private fun medianoche(): Calendar {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    /** Un rango por día, del más antiguo al de hoy. Con 7 días la etiqueta es el día de la semana; con más, el día del mes. */
    fun rangosDias(dias: Int): List<Rango> {
        val res = ArrayList<Rango>()
        val formato = if (dias <= 7) SimpleDateFormat("EEE", Locale("es")) else SimpleDateFormat("d", Locale("es"))
        for (i in dias - 1 downTo 0) {
            val ini = medianoche()
            ini.add(Calendar.DAY_OF_YEAR, -i)
            val fin = ini.clone() as Calendar
            fin.add(Calendar.DAY_OF_YEAR, 1)
            res.add(Rango(ini.timeInMillis, fin.timeInMillis, formato.format(ini.time).replace(".", "")))
        }
        return res
    }

    /** Un rango por hora desde la medianoche de hoy hasta ahora. */
    fun rangosHoy(): List<Rango> {
        val res = ArrayList<Rango>()
        val ahora = System.currentTimeMillis()
        val c = medianoche()
        while (c.timeInMillis < ahora) {
            val ini = c.timeInMillis
            c.add(Calendar.HOUR_OF_DAY, 1)
            res.add(Rango(ini, c.timeInMillis, String.format(Locale.US, "%02d", Calendar.getInstance().also { it.timeInMillis = ini }.get(Calendar.HOUR_OF_DAY))))
        }
        if (res.isEmpty()) res.add(Rango(c.timeInMillis, ahora + 1L, "00"))
        return res
    }

    private fun totalDispositivo(g: NetworkStatsManager, tipo: Int, suscriptor: String?, desde: Long, hasta: Long): Long {
        return try {
            val b = g.querySummaryForDevice(tipo, suscriptor, desde, hasta)
            b.rxBytes + b.txBytes
        } catch (e: Exception) {
            0L
        }
    }

    /** Tráfico total del teléfono en cada rango. */
    suspend fun serie(c: Context, rangos: List<Rango>): List<DiaTrafico> = withContext(Dispatchers.IO) {
        val g = gestor(c)
        rangos.map { r ->
            DiaTrafico(
                r.etiqueta,
                totalDispositivo(g, ConnectivityManager.TYPE_WIFI, "", r.desde, r.hasta),
                totalDispositivo(g, ConnectivityManager.TYPE_MOBILE, null, r.desde, r.hasta)
            )
        }
    }

    /** Tráfico de una app (por UID) en cada rango. */
    suspend fun serieUid(c: Context, uid: Int, rangos: List<Rango>): List<DiaTrafico> = withContext(Dispatchers.IO) {
        val g = gestor(c)
        val wifi = LongArray(rangos.size)
        val movil = LongArray(rangos.size)
        val inicio = rangos.first().desde
        val fin = rangos.last().hasta
        for (par in listOf(Pair(ConnectivityManager.TYPE_WIFI, wifi), Pair(ConnectivityManager.TYPE_MOBILE, movil))) {
            try {
                val sub: String? = if (par.first == ConnectivityManager.TYPE_WIFI) "" else null
                val stats = g.queryDetailsForUid(par.first, sub, inicio, fin, uid)
                val cubo = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(cubo)
                    val ts = cubo.startTimeStamp
                    for (i in rangos.indices) {
                        if (ts >= rangos[i].desde && ts < rangos[i].hasta) {
                            par.second[i] += cubo.rxBytes + cubo.txBytes
                            break
                        }
                    }
                }
                stats.close()
            } catch (e: Exception) {
            }
        }
        rangos.indices.map { DiaTrafico(rangos[it].etiqueta, wifi[it], movil[it]) }
    }

    /** Tráfico de todas las apps (por UID) en un periodo, ordenado de mayor a menor. */
    suspend fun porApp(c: Context, desde: Long, hasta: Long): List<AppTrafico> = withContext(Dispatchers.IO) {
        val g = gestor(c)
        val mapa = HashMap<Int, LongArray>()
        acumular(g, ConnectivityManager.TYPE_WIFI, "", desde, hasta, mapa, 0)
        acumular(g, ConnectivityManager.TYPE_MOBILE, null, desde, hasta, mapa, 1)
        mapa.map { AppTrafico(it.key, it.value[0], it.value[1]) }.filter { it.total > 0L }.sortedByDescending { it.total }
    }

    private fun acumular(g: NetworkStatsManager, tipo: Int, suscriptor: String?, desde: Long, hasta: Long, mapa: HashMap<Int, LongArray>, indice: Int) {
        try {
            val stats = g.querySummary(tipo, suscriptor, desde, hasta)
            val cubo = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(cubo)
                val a = mapa.getOrPut(cubo.uid) { LongArray(2) }
                a[indice] += cubo.rxBytes + cubo.txBytes
            }
            stats.close()
        } catch (e: Exception) {
        }
    }
}
