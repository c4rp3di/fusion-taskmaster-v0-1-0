package com.vigia.taskmaster.datos

import android.content.Context
import com.vigia.taskmaster.adb.ShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcMem(val paquete: String, val etiqueta: String, val pssKb: Long, val pids: List<Int>)

class ResumenMemoria(
    val totalKb: Long,
    val libreKb: Long,
    val disponibleKb: Long,
    val cacheKb: Long,
    val swapTotalKb: Long,
    val swapLibreKb: Long,
    val lineasSistema: List<String>,
    val procesos: List<ProcMem>
)

/** Memoria RAM del teléfono: /proc/meminfo para los totales y «dumpsys meminfo» para el PSS (memoria real) de cada proceso. */
object MemoriaRepo {

    private val LINEA_PROCESO = Regex("^\\s*([0-9.,]+)K:\\s+(\\S+)\\s+\\(pid\\s+(\\d+)")

    private const val ORDEN =
        "grep -E '^(MemTotal|MemFree|MemAvailable|Buffers|Cached|SwapTotal|SwapFree):' /proc/meminfo; echo ==PSS==; " +
            "dumpsys meminfo 2>&1 | grep -E '^ +[0-9.,]+K: .*\\(pid |Total RAM|Free RAM|Used RAM|Lost RAM|ZRAM' | head -n 500"

    suspend fun leer(contexto: Context): ResumenMemoria = withContext(Dispatchers.IO) {
        val salida = ShellManager.ejecutar(ORDEN)
        val i = salida.indexOf("==PSS==")
        val parteMem = if (i >= 0) salida.substring(0, i) else salida
        val partePss = if (i >= 0) salida.substring(i + 7) else ""

        val v = HashMap<String, Long>()
        for (l in parteMem.split('\n')) {
            val m = Regex("^(\\w+):\\s+(\\d+)").find(l.trim()) ?: continue
            v[m.groupValues[1]] = m.groupValues[2].toLongOrNull() ?: 0L
        }

        val pm = contexto.packageManager
        val acumulado = LinkedHashMap<String, Pair<Long, ArrayList<Int>>>()
        val resumen = ArrayList<String>()
        for (l in partePss.split('\n')) {
            val m = LINEA_PROCESO.find(l)
            if (m != null) {
                val pss = m.groupValues[1].replace(".", "").replace(",", "").toLongOrNull() ?: continue
                val paquete = m.groupValues[2].substringBefore(':')
                val pid = m.groupValues[3].toIntOrNull() ?: continue
                val previo = acumulado[paquete]
                if (previo == null) {
                    acumulado[paquete] = Pair(pss, arrayListOf(pid))
                } else {
                    previo.second.add(pid)
                    acumulado[paquete] = Pair(previo.first + pss, previo.second)
                }
            } else if (l.contains("RAM") && resumen.size < 8) {
                resumen.add(l.trim())
            }
        }

        val procesos = ArrayList<ProcMem>()
        for ((paquete, par) in acumulado) {
            var etiqueta = paquete
            if (paquete.contains('.')) {
                try {
                    etiqueta = pm.getApplicationInfo(paquete, 0).loadLabel(pm).toString()
                } catch (e: Exception) {
                }
            }
            procesos.add(ProcMem(paquete, etiqueta, par.first, par.second))
        }
        procesos.sortByDescending { it.pssKb }

        ResumenMemoria(
            v["MemTotal"] ?: 0L, v["MemFree"] ?: 0L, v["MemAvailable"] ?: 0L,
            (v["Cached"] ?: 0L) + (v["Buffers"] ?: 0L), v["SwapTotal"] ?: 0L, v["SwapFree"] ?: 0L,
            resumen, procesos
        )
    }
}
