package com.vigia.taskmaster

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.adb.ModoAvanzadoActivity
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.ActividadBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * Monitor de recursos: batería, memoria y almacenamiento del teléfono, tráfico de datos por app (necesita el acceso
 * especial «uso de apps») y consumo de batería por app (se lee con dumpsys, así que necesita el Modo Avanzado).
 */
class RecursosActivity : ActividadBase() {

    private class Fila(val etiqueta: String, val detalle: String, val valor: Long)

    private lateinit var txtBateria: TextView
    private lateinit var barraBateria: ProgressBar
    private lateinit var txtMemoria: TextView
    private lateinit var barraMemoria: ProgressBar
    private lateinit var txtAlmacen: TextView
    private lateinit var barraAlmacen: ProgressBar
    private lateinit var txtTrafico: TextView
    private lateinit var contTrafico: LinearLayout
    private lateinit var contBateriaApps: LinearLayout
    private lateinit var txtBateriaApps: TextView
    private lateinit var btnAcceso: MaterialButton
    private lateinit var btnAccesoShell: MaterialButton
    private var periodo = PERIODO_HOY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recursos)
        prepararPantalla(R.string.ayuda_recursos_titulo, R.string.ayuda_recursos)

        txtBateria = findViewById(R.id.txtBateria)
        barraBateria = findViewById(R.id.barraBateria)
        txtMemoria = findViewById(R.id.txtMemoria)
        barraMemoria = findViewById(R.id.barraMemoria)
        txtAlmacen = findViewById(R.id.txtAlmacen)
        barraAlmacen = findViewById(R.id.barraAlmacen)
        txtTrafico = findViewById(R.id.txtTrafico)
        contTrafico = findViewById(R.id.contTrafico)
        contBateriaApps = findViewById(R.id.contBateriaApps)
        txtBateriaApps = findViewById(R.id.txtBateriaApps)
        btnAcceso = findViewById(R.id.btnAccesoUso)
        btnAccesoShell = findViewById(R.id.btnAccesoUsoShell)

        findViewById<MaterialButton>(R.id.btnPeriodoHoy).setOnClickListener { cambiarPeriodo(PERIODO_HOY) }
        findViewById<MaterialButton>(R.id.btnPeriodo7).setOnClickListener { cambiarPeriodo(PERIODO_7) }
        findViewById<MaterialButton>(R.id.btnPeriodo30).setOnClickListener { cambiarPeriodo(PERIODO_30) }
        btnAcceso.setOnClickListener { abrirAccesoUso() }
        btnAccesoShell.setOnClickListener { concederConShell() }
        findViewById<MaterialButton>(R.id.btnLeerBateriaApps).setOnClickListener { leerBateriaApps() }
        findViewById<MaterialButton>(R.id.btnRecursosModoAvanzado).setOnClickListener {
            startActivity(Intent(this, ModoAvanzadoActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        pintarBateria()
        pintarMemoria()
        pintarAlmacenamiento()
        cargarTrafico()
        lifecycleScope.launch {
            ShellManager.verificar()
            btnAccesoShell.visibility = if (!tieneAccesoUso() && ShellManager.estado() == ShellManager.Estado.CONECTADO) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------ batería, memoria y almacenamiento

    private fun pintarBateria() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) {
            txtBateria.setText(R.string.no_disponible)
            return
        }
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (nivel >= 0 && escala > 0) nivel * 100 / escala else 0
        val estado = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> getString(R.string.bat_cargando)
            BatteryManager.BATTERY_STATUS_DISCHARGING -> getString(R.string.bat_descargando)
            BatteryManager.BATTERY_STATUS_FULL -> getString(R.string.bat_llena)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> getString(R.string.bat_sin_cargar)
            else -> getString(R.string.bat_desconocido)
        }
        val salud = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> getString(R.string.bat_salud_buena)
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> getString(R.string.bat_salud_calor)
            BatteryManager.BATTERY_HEALTH_DEAD -> getString(R.string.bat_salud_muerta)
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> getString(R.string.bat_salud_voltaje)
            BatteryManager.BATTERY_HEALTH_COLD -> getString(R.string.bat_salud_fria)
            else -> getString(R.string.bat_desconocido)
        }
        val temperatura = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val voltaje = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        var corriente = ""
        try {
            val bm = getSystemService(BatteryManager::class.java)
            val micro = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (micro != Int.MIN_VALUE && micro != 0) {
                corriente = "\n" + getString(R.string.bat_corriente, Math.abs(micro) / 1000)
            }
        } catch (e: Exception) {
        }
        txtBateria.text = getString(R.string.bat_resumen, pct, estado) + "\n" +
            getString(R.string.bat_detalle, String.format(Locale.US, "%.1f", temperatura), voltaje, salud) + corriente
        barraBateria.max = 100
        barraBateria.progress = pct
    }

    private fun pintarMemoria() {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val usada = info.totalMem - info.availMem
        txtMemoria.text = getString(R.string.mem_resumen, bytes(usada), bytes(info.totalMem), bytes(info.availMem)) +
            if (info.lowMemory) "\n" + getString(R.string.mem_baja) else ""
        barraMemoria.max = 1000
        barraMemoria.progress = if (info.totalMem > 0) (usada * 1000 / info.totalMem).toInt() else 0
    }

    private fun pintarAlmacenamiento() {
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.blockCountLong * st.blockSizeLong
            val libre = st.availableBlocksLong * st.blockSizeLong
            val usado = total - libre
            txtAlmacen.text = getString(R.string.alm_resumen, bytes(usado), bytes(total), bytes(libre))
            barraAlmacen.max = 1000
            barraAlmacen.progress = if (total > 0) (usado * 1000 / total).toInt() else 0
        } catch (e: Exception) {
            txtAlmacen.setText(R.string.no_disponible)
        }
    }

    // ------------------------------------------------------------------ tráfico por app

    private fun cambiarPeriodo(nuevo: Int) {
        periodo = nuevo
        cargarTrafico()
    }

    @Suppress("DEPRECATION")
    private fun tieneAccesoUso(): Boolean {
        return try {
            val ops = getSystemService(AppOpsManager::class.java)
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun abrirAccesoUso() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            aviso(R.string.no_se_pudo_abrir)
        }
    }

    private fun concederConShell() {
        lifecycleScope.launch {
            try {
                ShellManager.ejecutar("cmd appops set " + packageName + " GET_USAGE_STATS allow")
            } catch (e: Exception) {
                aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
            }
            delay(600L)
            cargarTrafico()
            btnAccesoShell.visibility = if (tieneAccesoUso()) View.GONE else View.VISIBLE
        }
    }

    private fun cargarTrafico() {
        contTrafico.removeAllViews()
        if (!tieneAccesoUso()) {
            txtTrafico.setText(R.string.trafico_sin_acceso)
            btnAcceso.visibility = View.VISIBLE
            return
        }
        btnAcceso.visibility = View.GONE
        txtTrafico.setText(R.string.cargando)
        val ahora = System.currentTimeMillis()
        val desde = when (periodo) {
            PERIODO_30 -> ahora - 30L * DIA_MS
            PERIODO_7 -> ahora - 7L * DIA_MS
            else -> inicioDeHoy()
        }
        lifecycleScope.launch {
            val filas = withContext(Dispatchers.IO) { consultarTrafico(desde, ahora) }
            val etiquetaPeriodo = when (periodo) {
                PERIODO_30 -> getString(R.string.periodo_30)
                PERIODO_7 -> getString(R.string.periodo_7)
                else -> getString(R.string.periodo_hoy)
            }
            if (filas.isEmpty()) {
                txtTrafico.text = getString(R.string.trafico_vacio, etiquetaPeriodo)
            } else {
                val total = filas.sumOf { it.valor }
                txtTrafico.text = getString(R.string.trafico_resumen, etiquetaPeriodo, bytes(total))
                pintarFilas(contTrafico, filas.take(15))
            }
        }
    }

    private fun inicioDeHoy(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun consultarTrafico(desde: Long, hasta: Long): List<Fila> {
        val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val porUid = HashMap<Int, LongArray>()
        acumular(nsm, ConnectivityManager.TYPE_WIFI, "", desde, hasta, porUid, 0)
        acumular(nsm, ConnectivityManager.TYPE_MOBILE, null, desde, hasta, porUid, 1)
        val filas = ArrayList<Fila>()
        for ((uid, v) in porUid) {
            val total = v[0] + v[1]
            if (total <= 0L) continue
            val detalle = getString(R.string.trafico_detalle, bytes(v[0]), bytes(v[1]))
            filas.add(Fila(nombreDeUid(uid) + "  ·  " + bytes(total), detalle, total))
        }
        filas.sortByDescending { it.valor }
        return filas
    }

    private fun acumular(nsm: NetworkStatsManager, tipo: Int, suscriptor: String?, desde: Long, hasta: Long, mapa: HashMap<Int, LongArray>, indice: Int) {
        try {
            val stats = nsm.querySummary(tipo, suscriptor, desde, hasta)
            val cubo = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(cubo)
                val a = mapa.getOrPut(cubo.uid) { LongArray(2) }
                a[indice] += cubo.rxBytes + cubo.txBytes
            }
            stats.close()
        } catch (e: Exception) {
            // Sin datos móviles, sin permiso para esa red o red no disponible: se ignora.
        }
    }

    private fun nombreDeUid(uid: Int): String {
        when (uid) {
            0 -> return getString(R.string.uid_root)
            1000 -> return getString(R.string.uid_sistema)
            -4 -> return getString(R.string.uid_eliminadas)
            -5 -> return getString(R.string.uid_anclaje)
        }
        try {
            val pm = packageManager
            val paquetes = pm.getPackagesForUid(uid)
            if (paquetes != null && paquetes.isNotEmpty()) {
                val etiqueta = pm.getApplicationInfo(paquetes[0], 0).loadLabel(pm).toString()
                return if (paquetes.size > 1) etiqueta + " +" + (paquetes.size - 1) else etiqueta
            }
        } catch (e: Exception) {
        }
        return "UID " + uid
    }

    // ------------------------------------------------------------------ batería por app (dumpsys)

    private fun leerBateriaApps() {
        if (ShellManager.estado() != ShellManager.Estado.CONECTADO) {
            txtBateriaApps.setText(R.string.requiere_modo_avanzado)
            return
        }
        txtBateriaApps.setText(R.string.cargando)
        contBateriaApps.removeAllViews()
        lifecycleScope.launch {
            val salida = try {
                ShellManager.ejecutar("dumpsys batterystats | grep -E '^ +Uid [^:]+: [0-9.]+'")
            } catch (e: Exception) {
                txtBateriaApps.text = getString(R.string.error_shell, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            val filas = withContext(Dispatchers.Default) { parsearBateria(salida) }
            if (filas.isEmpty()) {
                txtBateriaApps.setText(R.string.bateria_apps_vacio)
            } else {
                txtBateriaApps.setText(R.string.bateria_apps_resumen)
                pintarFilas(contBateriaApps, filas.take(15))
            }
        }
    }

    private fun parsearBateria(salida: String): List<Fila> {
        val mah = HashMap<Int, Double>()
        for (linea in salida.split('\n')) {
            val m = LINEA_BATERIA.find(linea) ?: continue
            val uid = uidDeTexto(m.groupValues[1]) ?: continue
            val valor = m.groupValues[2].toDoubleOrNull() ?: continue
            val previo = mah[uid]
            if (previo == null || valor > previo) mah[uid] = valor
        }
        val filas = ArrayList<Fila>()
        for ((uid, v) in mah) {
            if (v <= 0.0) continue
            filas.add(Fila(nombreDeUid(uid) + "  ·  " + String.format(Locale.US, "%.1f", v) + " mAh", "", (v * 1000.0).toLong()))
        }
        filas.sortByDescending { it.valor }
        return filas
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

    // ------------------------------------------------------------------ utilidades de pantalla

    private fun pintarFilas(contenedor: LinearLayout, filas: List<Fila>) {
        contenedor.removeAllViews()
        val maximo = filas.maxOfOrNull { it.valor } ?: 1L
        for (f in filas) {
            val v = layoutInflater.inflate(R.layout.item_barra, contenedor, false)
            v.findViewById<TextView>(R.id.barraTitulo).text = f.etiqueta
            val det = v.findViewById<TextView>(R.id.barraDetalle)
            if (f.detalle.isEmpty()) det.visibility = View.GONE else det.text = f.detalle
            val barra = v.findViewById<ProgressBar>(R.id.barraValor)
            barra.max = 1000
            barra.progress = if (maximo > 0) (f.valor * 1000 / maximo).toInt() else 0
            contenedor.addView(v)
        }
    }

    private fun bytes(n: Long): String {
        val kb = 1024.0
        return when {
            n >= kb * kb * kb -> String.format(Locale.US, "%.1f GB", n / (kb * kb * kb))
            n >= kb * kb -> String.format(Locale.US, "%.0f MB", n / (kb * kb))
            n >= kb -> String.format(Locale.US, "%.0f KB", n / kb)
            else -> n.toString() + " B"
        }
    }

    companion object {
        private const val PERIODO_HOY = 0
        private const val PERIODO_7 = 1
        private const val PERIODO_30 = 2
        private const val DIA_MS = 86_400_000L
        private val LINEA_BATERIA = Regex("^\\s*Uid\\s+([^:\\s]+):\\s+([0-9]+(?:\\.[0-9]+)?)")
        private val UID_APP = Regex("^u(\\d+)a(\\d+)$")
    }
}
