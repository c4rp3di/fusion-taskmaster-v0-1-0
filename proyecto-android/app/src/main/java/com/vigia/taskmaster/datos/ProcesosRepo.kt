package com.vigia.taskmaster.datos

import android.content.Context
import android.content.pm.PackageManager
import com.vigia.taskmaster.adb.ShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcInfo(val pid: Int, val usuario: String, val rssKb: Long, val nombre: String, var cpu: Float = 0f)

class AppProc(val paquete: String, val etiqueta: String, val esApp: Boolean, val procesos: List<ProcInfo>) {
    val rssKb: Long get() = procesos.sumOf { it.rssKb }
    val cpu: Float get() = procesos.sumOf { it.cpu.toDouble() }.toFloat()
}

class ResumenCpu(val nucleos: Int, val usoPct: Float, val usuarioPct: Float, val sistemaPct: Float)

class MemGlobal(val totalKb: Long, val disponibleKb: Long, val swapTotalKb: Long, val swapLibreKb: Long)

class CargaProcesos(val apps: List<AppProc>, val cpu: ResumenCpu?, val mem: MemGlobal?, val instante: Long)

/**
 * Lee los procesos del teléfono con una sola llamada al shell (ps para la lista y la memoria, top para el uso de CPU) y guarda
 * el último resultado en memoria, para no repetir el trabajo cada vez que se abre una pantalla.
 */
object ProcesosRepo {
    @Volatile
    var ultima: CargaProcesos? = null

    private val APP_USUARIO = Regex("^u\\d+_a\\d+$")

    private const val ORDEN =
        "ps -A -o PID,USER,RSS,NAME; echo ===MEM===; " +
            "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo; echo ===TOP===; " +
            "top -b -n 2 -d 1 -m 120"

    suspend fun cargar(contexto: Context): CargaProcesos = withContext(Dispatchers.IO) {
        val salida = ShellManager.ejecutar(ORDEN)
        val r = parsear(contexto.packageManager, contexto.packageName, salida)
        ultima = r
        r
    }

    /** Solo la lista de procesos con su paquete (rápida): sirve para saber qué PIDs tiene una app. */
    suspend fun pidsPorPaquete(contexto: Context): Map<String, List<Int>> = withContext(Dispatchers.IO) {
        val salida = ShellManager.ejecutar("ps -A -o PID,USER,NAME")
        val res = LinkedHashMap<String, ArrayList<Int>>()
        var primera = true
        for (linea in salida.split('\n')) {
            if (primera) {
                primera = false
                continue
            }
            val t = linea.trim().split(Regex("\\s+"))
            if (t.size < 3) continue
            val pid = t[0].toIntOrNull() ?: continue
            val nombre = t.drop(2).joinToString(" ")
            if (nombre.startsWith("[")) continue
            res.getOrPut(nombre.substringBefore(':')) { ArrayList() }.add(pid)
        }
        res
    }

    fun parsear(pm: PackageManager, propio: String, salida: String): CargaProcesos {
        val iMem = salida.indexOf("===MEM===")
        val iTop = salida.indexOf("===TOP===")
        val seccionPs = if (iMem >= 0) salida.substring(0, iMem) else salida
        val seccionMem = if (iMem >= 0 && iTop > iMem) salida.substring(iMem + 9, iTop) else ""
        val seccionTop = if (iTop >= 0) salida.substring(iTop + 9) else ""

        // --- ps: procesos agrupados por app
        val porPid = HashMap<Int, ProcInfo>()
        val porPaquete = LinkedHashMap<String, ArrayList<ProcInfo>>()
        val usuarios = HashMap<String, String>()
        var primera = true
        for (linea in seccionPs.split('\n')) {
            if (primera) {
                primera = false
                continue
            }
            val t = linea.trim().split(Regex("\\s+"))
            if (t.size < 4) continue
            val pid = t[0].toIntOrNull() ?: continue
            val usuario = t[1]
            val rss = t[2].toLongOrNull() ?: 0L
            val nombre = t.drop(3).joinToString(" ")
            if (nombre.startsWith("[")) continue
            val paquete = nombre.substringBefore(':')
            if (paquete == propio) continue
            val p = ProcInfo(pid, usuario, rss, nombre)
            porPid[pid] = p
            porPaquete.getOrPut(paquete) { ArrayList() }.add(p)
            usuarios[paquete] = usuario
        }

        // --- top: CPU por proceso y resumen global (se usa el último de los dos cuadros)
        var cpuResumen: ResumenCpu? = null
        try {
            val lineas = seccionTop.split('\n')
            val h = lineas.indexOfLast { val l = it.trimStart(); l.startsWith("PID") && l.contains("USER") }
            if (h >= 0) {
                val cabecera = lineas[h].replace('[', ' ').replace(']', ' ').trim().split(Regex("\\s+"))
                val iPid = cabecera.indexOf("PID")
                val iCpu = cabecera.indexOf("%CPU")
                if (iPid >= 0 && iCpu >= 0) {
                    for (k in h + 1 until lineas.size) {
                        val t = lineas[k].trim().split(Regex("\\s+"))
                        if (t.size <= iCpu) continue
                        val pid = t[iPid].toIntOrNull() ?: continue
                        val cpu = t[iCpu].replace(',', '.').toFloatOrNull() ?: continue
                        porPid[pid]?.cpu = cpu
                    }
                }
                val lineaCpu = lineas.subList(0, h).lastOrNull { it.contains("%cpu") }
                if (lineaCpu != null) cpuResumen = parsearCpu(lineaCpu)
            }
        } catch (e: Exception) {
        }

        // --- memoria global
        var mem: MemGlobal? = null
        try {
            val valores = HashMap<String, Long>()
            for (l in seccionMem.split('\n')) {
                val m = Regex("^(\\w+):\\s+(\\d+)").find(l.trim()) ?: continue
                valores[m.groupValues[1]] = m.groupValues[2].toLongOrNull() ?: 0L
            }
            val total = valores["MemTotal"] ?: 0L
            if (total > 0L) {
                mem = MemGlobal(total, valores["MemAvailable"] ?: 0L, valores["SwapTotal"] ?: 0L, valores["SwapFree"] ?: 0L)
            }
        } catch (e: Exception) {
        }

        val apps = ArrayList<AppProc>()
        for ((paquete, lista) in porPaquete) {
            var etiqueta = paquete
            if (paquete.contains('.')) {
                try {
                    etiqueta = pm.getApplicationInfo(paquete, 0).loadLabel(pm).toString()
                } catch (e: Exception) {
                }
            }
            apps.add(AppProc(paquete, etiqueta, APP_USUARIO.matches(usuarios[paquete] ?: ""), lista))
        }
        return CargaProcesos(apps, cpuResumen, mem, System.currentTimeMillis())
    }

    /** «800%cpu  23%user  0%nice  18%sys 750%idle ...»: 8 núcleos, cada uno cuenta 100. */
    private fun parsearCpu(linea: String): ResumenCpu? {
        fun valor(clave: String): Float? = Regex("(\\d+(?:\\.\\d+)?)%" + clave).find(linea)?.groupValues?.get(1)?.toFloatOrNull()
        val total = valor("cpu") ?: return null
        if (total <= 0f) return null
        val inactivo = valor("idle") ?: 0f
        val usuario = valor("user") ?: 0f
        val sistema = valor("sys") ?: 0f
        return ResumenCpu(
            Math.round(total / 100f), ((total - inactivo) / total * 100f).coerceIn(0f, 100f),
            (usuario / total * 100f).coerceIn(0f, 100f), (sistema / total * 100f).coerceIn(0f, 100f)
        )
    }
}
