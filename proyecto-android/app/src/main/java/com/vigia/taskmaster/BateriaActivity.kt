package com.vigia.taskmaster

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.AccesoUso
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.BarraView
import com.vigia.taskmaster.core.DonutView
import com.vigia.taskmaster.core.Formato
import com.vigia.taskmaster.core.LineaView
import com.vigia.taskmaster.core.Portapapeles
import com.vigia.taskmaster.core.Sistema
import com.vigia.taskmaster.core.Tema
import com.vigia.taskmaster.core.Ui
import com.vigia.taskmaster.datos.BateriaApps
import com.vigia.taskmaster.datos.Uids
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Batería: nivel y estado, detalles técnicos, gráficas en vivo de corriente y temperatura, apps que más se usan (tiempo en
 * pantalla), consumo estimado por app (con el Modo Avanzado) y atajos a los ajustes de energía de Android.
 */
class BateriaActivity : ActividadBase() {

    private class Lectura(
        val pct: Int,
        val estado: String,
        val cargando: Boolean,
        val temperatura: Double,
        val milivoltios: Int,
        val salud: String,
        val tecnologia: String,
        val ciclos: Int?,
        val miliamperios: Int?,
        val capacidadMah: Double?,
        val restante: String?
    )

    private val corrientes = ArrayList<Float>()
    private val temperaturas = ArrayList<Float>()
    private var lineaCorriente: LineaView? = null
    private var lineaTemperatura: LineaView? = null
    private var textoCorriente: android.widget.TextView? = null
    private var textoTemperatura: android.widget.TextView? = null
    private var muestreo: Job? = null
    private var periodoUso = 0
    private lateinit var contUso: LinearLayout
    private lateinit var contConsumo: LinearLayout
    
    // NUEVO: Estado para el desplegable de consumo
    private var mostrarTodasConsumo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usarPantallaScroll(R.string.sec_bateria, R.string.ayuda_bateria_titulo, R.string.ayuda_bateria)
    }

    override fun onResume() {
        super.onResume()
        construir()
        muestreo?.cancel()
        muestreo = lifecycleScope.launch {
            while (isActive) {
                muestrear()
                delay(3000L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        muestreo?.cancel()
        muestreo = null
    }

    // ------------------------------------------------------------------ lectura

    private fun leer(): Lectura? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (nivel >= 0 && escala > 0) nivel * 100 / escala else 0
        val estadoCodigo = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val estado = when (estadoCodigo) {
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
        val bm = getSystemService(BatteryManager::class.java)
        var ma: Int? = null
        var capacidad: Double? = null
        var restante: String? = null
        try {
            val micro = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (micro != Int.MIN_VALUE) ma = Math.abs(micro) / 1000
            val carga = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (carga > 0 && pct > 0) capacidad = carga / 1000.0 / (pct / 100.0)
            if (Build.VERSION.SDK_INT >= 28) {
                val t = bm.computeChargeTimeRemaining()
                if (t > 0L) restante = Formato.duracion(t)
            }
        } catch (e: Exception) {
        }
        var ciclos: Int? = null
        if (Build.VERSION.SDK_INT >= 34) {
            val c = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (c >= 0) ciclos = c
        }
        return Lectura(
            pct, estado, estadoCodigo == BatteryManager.BATTERY_STATUS_CHARGING,
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0, i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            salud, i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "?", ciclos, ma, capacidad, restante
        )
    }

    private fun muestrear() {
        val l = leer() ?: return
        corrientes.add((l.miliamperios ?: 0).toFloat())
        temperaturas.add(l.temperatura.toFloat())
        if (corrientes.size > 40) corrientes.removeAt(0)
        if (temperaturas.size > 40) temperaturas.removeAt(0)
        lineaCorriente?.serie1 = ArrayList(corrientes)
        lineaTemperatura?.serie1 = ArrayList(temperaturas)
        textoCorriente?.text = getString(R.string.bat_corriente, l.miliamperios ?: 0)
        textoTemperatura?.text = Formato.decimal(l.temperatura, 1) + " °C"
    }

    // ------------------------------------------------------------------ pantalla

    private fun construir() {
        contenido.removeAllViews()
        val l = leer()
        if (l == null) {
            contenido.addView(Ui.texto(this, getString(R.string.no_disponible)))
            return
        }

        // Nivel
        val (t1, c1) = Ui.tarjetaConColumna(this)
        val donut = DonutView(this)
        val color = if (l.pct < 20) Tema.rojo(this) else if (l.pct < 40) Tema.ambar(this) else Tema.verde(this)
        donut.porciones = listOf(DonutView.Porcion(l.pct.toFloat(), color), DonutView.Porcion((100 - l.pct).toFloat(), Tema.gris(this)))
        donut.textoCentro = l.pct.toString() + "%"
        donut.subtexto = l.estado
        val lpDonut = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpDonut.gravity = android.view.Gravity.CENTER_HORIZONTAL
        c1.addView(donut, lpDonut)
        if (l.restante != null) c1.addView(Ui.texto(this, getString(R.string.bat_tiempo_carga, l.restante), 13f))
        contenido.addView(t1)

        // Detalles
        val (t2, c2) = Ui.tarjetaConColumna(this)
        c2.addView(Ui.titulo(this, getString(R.string.bat_detalles)))
        c2.addView(Ui.fila(this, getString(R.string.bat_l_temperatura), Formato.decimal(l.temperatura, 1) + " °C"))
        c2.addView(Ui.fila(this, getString(R.string.bat_l_voltaje), l.milivoltios.toString() + " mV"))
        if (l.miliamperios != null) c2.addView(Ui.fila(this, getString(R.string.bat_l_corriente), l.miliamperios.toString() + " mA"))
        c2.addView(Ui.fila(this, getString(R.string.bat_l_salud), l.salud))
        c2.addView(Ui.fila(this, getString(R.string.bat_l_tecnologia), l.tecnologia))
        if (l.ciclos != null) c2.addView(Ui.fila(this, getString(R.string.bat_l_ciclos), l.ciclos.toString()))
        if (l.capacidadMah != null) c2.addView(Ui.fila(this, getString(R.string.bat_l_capacidad), Formato.decimal(l.capacidadMah, 0) + " mAh"))
        c2.addView(Ui.texto(this, getString(R.string.bat_nota_corriente), 11f, false, Tema.gris(this)))
        contenido.addView(t2)

        // Gráficas en vivo
        val (t3, c3) = Ui.tarjetaConColumna(this)
        c3.addView(Ui.titulo(this, getString(R.string.bat_en_vivo)))
        val tc = Ui.texto(this, "…", 13f)
        textoCorriente = tc
        c3.addView(tc)
        val gc = LineaView(this)
        gc.color1 = Tema.azul(this)
        gc.unidad = "mA"
        lineaCorriente = gc
        c3.addView(gc)
        c3.addView(Ui.espacio(this, 8))
        val tt = Ui.texto(this, "…", 13f)
        textoTemperatura = tt
        c3.addView(tt)
        val gt = LineaView(this)
        gt.color1 = Tema.naranja(this)
        gt.unidad = "°C"
        lineaTemperatura = gt
        c3.addView(gt)
        contenido.addView(t3)

        // Apps más usadas
        val (t4, c4) = Ui.tarjetaConColumna(this)
        c4.addView(Ui.titulo(this, getString(R.string.bat_uso_apps)))
        c4.addView(Ui.texto(this, getString(R.string.bat_uso_apps_info), 12f, false, Tema.gris(this)))
        val botones = LinearLayout(this)
        botones.orientation = LinearLayout.HORIZONTAL
        botones.addView(Ui.botonSecundario(this, getString(R.string.periodo_hoy)) { periodoUso = 0; cargarUso() })
        botones.addView(Ui.botonSecundario(this, getString(R.string.periodo_7)) { periodoUso = 1; cargarUso() })
        c4.addView(botones)
        contUso = Ui.columna(this, 0)
        c4.addView(contUso)
        contenido.addView(t4)
        cargarUso()

        // Consumo por app
        val (t5, c5) = Ui.tarjetaConColumna(this)
        c5.addView(Ui.titulo(this, getString(R.string.sec_bateria_apps)))
        c5.addView(Ui.texto(this, getString(R.string.bateria_apps_info), 12f, false, Tema.gris(this)))
        c5.addView(Ui.boton(this, getString(R.string.leer_bateria_apps)) { leerConsumo() })
        contConsumo = Ui.columna(this, 0)
        c5.addView(contConsumo)
        contenido.addView(t5)

        // Opciones
        val (t6, c6) = Ui.tarjetaConColumna(this)
        c6.addView(Ui.titulo(this, getString(R.string.opciones)))
        c6.addView(Ui.botonSecundario(this, getString(R.string.bat_op_ahorro)) { abrir(Settings.ACTION_BATTERY_SAVER_SETTINGS) })
        c6.addView(Ui.botonSecundario(this, getString(R.string.bat_op_uso_sistema)) { abrir(Intent.ACTION_POWER_USAGE_SUMMARY) })
        c6.addView(Ui.botonSecundario(this, getString(R.string.bat_op_optimizacion)) { abrir(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
        c6.addView(Ui.botonSecundario(this, getString(R.string.bat_op_reiniciar)) { confirmarReinicio() })
        contenido.addView(t6)
    }

    private fun abrir(accion: String) {
        if (!Sistema.abrir(this, accion)) aviso(R.string.no_se_pudo_abrir)
    }

    private fun confirmarReinicio() {
        if (ShellManager.estado() != ShellManager.Estado.CONECTADO) {
            aviso(R.string.requiere_modo_avanzado)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bat_op_reiniciar)
            .setMessage(R.string.bat_reiniciar_aviso)
            .setPositiveButton(R.string.ejecutar) { _, _ ->
                lifecycleScope.launch {
                    try {
                        ShellManager.ejecutar("dumpsys batterystats --reset")
                        aviso(R.string.bat_reiniciado)
                    } catch (e: Exception) {
                        aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ tiempo de uso por app

    private fun cargarUso() {
        contUso.removeAllViews()
        if (!AccesoUso.concedido(this)) {
            val t = AccesoUso.tarjeta(this) { cargarUso() }
            if (t != null) contUso.addView(t)
            return
        }
        val ahora = System.currentTimeMillis()
        val desde = if (periodoUso == 0) {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.timeInMillis
        } else {
            ahora - 7L * 86_400_000L
        }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val mapa: Map<String, UsageStats> = try {
            usm.queryAndAggregateUsageStats(desde, ahora)
        } catch (e: Exception) {
            emptyMap()
        }
        val pm = packageManager
        val filas = mapa.values.filter { it.totalTimeInForeground > 60_000L }.sortedByDescending { it.totalTimeInForeground }.take(15)
        if (filas.isEmpty()) {
            contUso.addView(Ui.texto(this, getString(R.string.bat_uso_vacio), 13f))
            return
        }
        val maximo = filas.first().totalTimeInForeground.toFloat()
        for (f in filas) {
            val etiqueta = try {
                pm.getApplicationInfo(f.packageName, 0).loadLabel(pm).toString()
            } catch (e: Exception) {
                f.packageName
            }
            contUso.addView(Ui.fila(this, etiqueta, Formato.duracion(f.totalTimeInForeground)))
            val b = BarraView(this)
            b.altoDp = 6
            b.simple(f.totalTimeInForeground.toFloat() / maximo, Tema.azul(this))
            contUso.addView(b)
            contUso.addView(Ui.espacio(this, 6))
        }
    }

    // ------------------------------------------------------------------ consumo estimado (dumpsys)

    private fun leerConsumo() {
        contConsumo.removeAllViews()
        if (ShellManager.estado() != ShellManager.Estado.CONECTADO) {
            contConsumo.addView(Ui.texto(this, getString(R.string.requiere_modo_avanzado), 13f))
            return
        }
        contConsumo.addView(Ui.texto(this, getString(R.string.cargando), 13f))
        lifecycleScope.launch {
            val r = try {
                BateriaApps.leer()
            } catch (e: Exception) {
                contConsumo.removeAllViews()
                contConsumo.addView(Ui.texto(this@BateriaActivity, getString(R.string.error_shell, e.message ?: e.javaClass.simpleName), 13f))
                return@launch
            }
            contConsumo.removeAllViews()
            
            // Si la lista está vacía, mostramos diagnóstico (comportamiento original)
            if (r.filas.isEmpty()) {
                contConsumo.addView(Ui.texto(this@BateriaActivity, getString(R.string.bateria_apps_vacio), 13f))
                contConsumo.addView(Ui.monoespaciado(this@BateriaActivity, r.diagnostico, 10f))
                contConsumo.addView(Ui.botonSecundario(this@BateriaActivity, getString(R.string.copiar_diagnostico)) {
                    Portapapeles.copiar(this@BateriaActivity, r.diagnostico)
                    aviso(R.string.copiado)
                })
                return@launch
            }

            // Si SÍ hay datos, mostramos la lista con el desplegable
            contConsumo.addView(Ui.texto(this@BateriaActivity, getString(R.string.bateria_apps_resumen), 12f, false, Tema.gris(this@BateriaActivity)))
            
            // 1. Decidir cuántas filas mostrar
            val filasAMostrar = if (mostrarTodasConsumo) r.filas else r.filas.take(15)
            
            // 2. Calculamos el máximo usando la lista completa original (r.filas) para que las barras mantengan la proporción real
            val maximo = r.filas.firstOrNull()?.mah?.toFloat() ?: 1f 
            
            // 3. Pintamos las filas seleccionadas
            for (f in filasAMostrar) {
                contConsumo.addView(Ui.fila(this@BateriaActivity, Uids.nombre(this@BateriaActivity, f.uid), Formato.decimal(f.mah, 1) + " mAh"))
                val b = BarraView(this@BateriaActivity)
                b.altoDp = 6
                b.simple(f.mah.toFloat() / maximo, Tema.naranja(this@BateriaActivity))
                contConsumo.addView(b)
                contConsumo.addView(Ui.espacio(this@BateriaActivity, 6))
            }

            // 4. Añadir el botón "Mostrar más / Mostrar menos" si hay más de 15
            if (r.filas.size > 15) {
                val textoBoton = if (mostrarTodasConsumo) "Mostrar menos" else "Mostrar todas (${r.filas.size})"
                contConsumo.addView(Ui.botonSecundario(this@BateriaActivity, textoBoton) {
                    mostrarTodasConsumo = !mostrarTodasConsumo // Cambiamos el estado
                    leerConsumo() // Volvemos a dibujar la lista
                })
            }
        }
    }
}