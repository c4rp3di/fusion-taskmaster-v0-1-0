package com.vigia.taskmaster.datos

import com.vigia.taskmaster.adb.ShellManager

class ConsumoApp(val uid: Int, val mah: Double)

class ResultadoBateria(val filas: List<ConsumoApp>, val diagnostico: String)

/**
 * Consumo de batería por app. Android no lo ofrece a una app normal y el formato de dumpsys cambia entre versiones, así que
 * se prueban tres formas (checkin, --usage y el volcado completo) y, si ninguna da datos, se devuelve un diagnóstico con lo
 * que respondió el sistema para poder ajustar el lector.
 */
object BateriaApps {

private const val ORDEN =
    "echo ==A==; dumpsys batterystats --charged 2>&1 | grep -E 'Uid [0-9]+: [0-9.]+' | head -n 150; " +
        "echo ==B==; dumpsys batterystats 2>&1 | grep -E 'Uid [0-9]+: [0-9.]+' | head -n 150; " +
        "echo ==C==; dumpsys batterystats --checkin 2>&1 | grep ',pwi,' | head -n 150; " +
        "echo ==D==; dumpsys batterystats 2>&1 | head -n 30"

    private val LINEA_UID = Regex("Uid\\s+([^:\\s]+):\\s+([0-9]+(?:\\.[0-9]+)?)")
    private val UID_APP = Regex("^u(\\d+)a(\\d+)$")

    suspend fun leer(): ResultadoBateria {
        val salida = ShellManager.ejecutar(ORDEN)
        val a = seccion(salida, "==A==", "==B==")
        val b = seccion(salida, "==B==", "==C==")
        val c = seccion(salida, "==C==", "==D==")
        val d = seccion(salida, "==D==", null)

        var filas = parsearCheckin(a)
        if (filas.isEmpty()) filas = parsearTexto(b)
        if (filas.isEmpty()) filas = parsearTexto(c)

        val diag = StringBuilder()
        diag.append("checkin: ").append(a.size).append(" líneas · usage: ").append(b.size)
            .append(" líneas · completo: ").append(c.size).append(" líneas\n\n")
        diag.append("--- cabecera de dumpsys batterystats ---\n").append(d.take(15).joinToString("\n"))
        if (a.isNotEmpty()) diag.append("\n\n--- muestra checkin ---\n").append(a.take(3).joinToString("\n"))
        if (b.isNotEmpty()) diag.append("\n\n--- muestra usage ---\n").append(b.take(3).joinToString("\n"))
        if (c.isNotEmpty()) diag.append("\n\n--- muestra completo ---\n").append(c.take(3).joinToString("\n"))
        return ResultadoBateria(filas.sortedByDescending { it.mah }, diag.toString())
    }

    private fun seccion(texto: String, desde: String, hasta: String?): List<String> {
        val i = texto.indexOf(desde)
        if (i < 0) return emptyList()
        val ini = i + desde.length
        val fin = if (hasta == null) texto.length else texto.indexOf(hasta, ini).let { if (it < 0) texto.length else it }
        return texto.substring(ini, fin).split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
    }

    /** Líneas «9,10149,l,pwi,uid,12.3,...»: el UID va en el segundo campo y los mAh en el sexto. */
    private fun parsearCheckin(lineas: List<String>): List<ConsumoApp> {
        val res = HashMap<Int, Double>()
        for (l in lineas) {
            val t = l.split(',')
            if (t.size < 6 || t[3] != "pwi" || t[4] != "uid") continue
            val uid = t[1].toIntOrNull() ?: continue
            val mah = t[5].toDoubleOrNull() ?: continue
            res[uid] = maxOf(res[uid] ?: 0.0, mah)
        }
        return res.filter { it.value > 0.0 }.map { ConsumoApp(it.key, it.value) }
    }

    private fun parsearTexto(lineas: List<String>): List<ConsumoApp> {
        val res = HashMap<Int, Double>()
        for (l in lineas) {
            val m = LINEA_UID.find(l) ?: continue
            val uid = uidDeTexto(m.groupValues[1]) ?: continue
            val mah = m.groupValues[2].toDoubleOrNull() ?: continue
            res[uid] = maxOf(res[uid] ?: 0.0, mah)
        }
        return res.filter { it.value > 0.0 }.map { ConsumoApp(it.key, it.value) }
    }

    /** «u0a149» es el usuario 0 y la app 149, es decir, el UID 10149. */
    private fun uidDeTexto(texto: String): Int? {
        val m = UID_APP.matchEntire(texto)
        if (m != null) {
            val usuario = m.groupValues[1].toIntOrNull() ?: return null
            val app = m.groupValues[2].toIntOrNull() ?: return null
            return usuario * 100000 + 10000 + app
        }
        return texto.toIntOrNull()
    }
}
