package com.vigia.taskmaster

import android.net.TrafficStats
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vigia.taskmaster.core.AccesoUso
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.BarraView
import com.vigia.taskmaster.core.BarrasDiasView
import com.vigia.taskmaster.core.DonutView
import com.vigia.taskmaster.core.Formato
import com.vigia.taskmaster.core.LineaView
import com.vigia.taskmaster.core.Sistema
import com.vigia.taskmaster.core.Tema
import com.vigia.taskmaster.core.Ui
import com.vigia.taskmaster.datos.AppTrafico
import com.vigia.taskmaster.datos.DiaTrafico
import com.vigia.taskmaster.datos.TraficoRepo
import com.vigia.taskmaster.datos.Uids
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tráfico de red: velocidad en directo, tráfico por periodo (hoy por horas, 7 o 30 días) separado en wifi y datos móviles,
 * y las apps que más gastan; al tocar una app se ve su gráfica por días.
 */
class TraficoActivity : ActividadBase() {

    private var periodo = 1
    private var vivo: Job? = null
    private val bajada = ArrayList<Float>()
    private val subida = ArrayList<Float>()
    private var lineaVivo: LineaView? = null
    private var textoVivo: TextView? = null
    private lateinit var contDatos: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usarPantallaScroll(R.string.sec_trafico, R.string.ayuda_trafico_titulo, R.string.ayuda_trafico)
    }

    override fun onResume() {
        super.onResume()
        construir()
        vivo?.cancel()
        vivo = lifecycleScope.launch {
            var rxAntes = TrafficStats.getTotalRxBytes()
            var txAntes = TrafficStats.getTotalTxBytes()
            while (isActive) {
                delay(1000L)
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                if (rxAntes >= 0L && txAntes >= 0L && rx >= 0L && tx >= 0L) {
                    val kbBajada = (rx - rxAntes) / 1024f
                    val kbSubida = (tx - txAntes) / 1024f
                    bajada.add(kbBajada)
                    subida.add(kbSubida)
                    if (bajada.size > 60) bajada.removeAt(0)
                    if (subida.size > 60) subida.removeAt(0)
                    lineaVivo?.serie1 = ArrayList(bajada)
                    lineaVivo?.serie2 = ArrayList(subida)
                    textoVivo?.text = getString(R.string.tr_velocidad, velocidad(kbBajada), velocidad(kbSubida))
                }
                rxAntes = rx
                txAntes = tx
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vivo?.cancel()
        vivo = null
    }

    private fun velocidad(kbPorSegundo: Float): String {
        return if (kbPorSegundo >= 1024f) Formato.decimal((kbPorSegundo / 1024f).toDouble(), 1) + " MB/s" else Formato.decimal(kbPorSegundo.toDouble(), 0) + " KB/s"
    }

    private fun construir() {
        contenido.removeAllViews()

        // Velocidad en directo
        val (t1, c1) = Ui.tarjetaConColumna(this)
        c1.addView(Ui.titulo(this, getString(R.string.tr_en_vivo)))
        val txt = Ui.texto(this, getString(R.string.tr_midiendo), 13f)
        textoVivo = txt
        c1.addView(txt)
        val linea = LineaView(this)
        linea.color1 = Tema.azul(this)
        linea.color2 = Tema.naranja(this)
        linea.unidad = "KB/s"
        lineaVivo = linea
        c1.addView(linea)
        c1.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.tr_bajada)))
        c1.addView(Ui.leyenda(this, Tema.naranja(this), getString(R.string.tr_subida)))
        contenido.addView(t1)

        // Periodo
        val (t2, c2) = Ui.tarjetaConColumna(this)
        c2.addView(Ui.titulo(this, getString(R.string.tr_consumo)))
        val botones = LinearLayout(this)
        botones.orientation = LinearLayout.HORIZONTAL
        botones.addView(Ui.botonSecundario(this, getString(R.string.periodo_hoy)) { periodo = 0; cargarDatos() })
        botones.addView(Ui.botonSecundario(this, getString(R.string.periodo_7)) { periodo = 1; cargarDatos() })
        botones.addView(Ui.botonSecundario(this, getString(R.string.periodo_30)) { periodo = 2; cargarDatos() })
        c2.addView(botones)
        contDatos = Ui.columna(this, 0)
        c2.addView(contDatos)
        contenido.addView(t2)
        cargarDatos()

        // Opciones
        val (t3, c3) = Ui.tarjetaConColumna(this)
        c3.addView(Ui.titulo(this, getString(R.string.opciones)))
        c3.addView(Ui.botonSecundario(this, getString(R.string.tr_op_uso_datos)) {
            if (!Sistema.abrir(this, "android.settings.DATA_USAGE_SETTINGS")) aviso(R.string.no_se_pudo_abrir)
        })
        c3.addView(Ui.botonSecundario(this, getString(R.string.tr_op_redes)) {
            if (!Sistema.abrir(this, android.provider.Settings.ACTION_WIRELESS_SETTINGS)) aviso(R.string.no_se_pudo_abrir)
        })
        contenido.addView(t3)
    }

    private fun cargarDatos() {
        contDatos.removeAllViews()
        if (!AccesoUso.concedido(this)) {
            val t = AccesoUso.tarjeta(this) { cargarDatos() }
            if (t != null) contDatos.addView(t)
            return
        }
        contDatos.addView(Ui.texto(this, getString(R.string.cargando), 13f))
        val rangos = when (periodo) {
            0 -> TraficoRepo.rangosHoy()
            2 -> TraficoRepo.rangosDias(30)
            else -> TraficoRepo.rangosDias(7)
        }
        lifecycleScope.launch {
            val serie = TraficoRepo.serie(this@TraficoActivity, rangos)
            val apps = TraficoRepo.porApp(this@TraficoActivity, rangos.first().desde, rangos.last().hasta)
            pintar(serie, apps, rangos)
        }
    }

    private fun pintar(serie: List<DiaTrafico>, apps: List<AppTrafico>, rangos: List<TraficoRepo.Rango>) {
        contDatos.removeAllViews()
        val wifi = serie.sumOf { it.wifi }
        val movil = serie.sumOf { it.movil }
        val total = wifi + movil
        if (total <= 0L) {
            contDatos.addView(Ui.texto(this, getString(R.string.trafico_vacio, nombrePeriodo()), 13f))
            return
        }

        val donut = DonutView(this)
        donut.porciones = listOf(DonutView.Porcion(wifi.toFloat(), Tema.azul(this)), DonutView.Porcion(movil.toFloat(), Tema.naranja(this)))
        donut.textoCentro = Formato.bytes(total)
        donut.subtexto = nombrePeriodo()
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        contDatos.addView(donut, lp)
        contDatos.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.tr_wifi), Formato.bytes(wifi)))
        contDatos.addView(Ui.leyenda(this, Tema.naranja(this), getString(R.string.tr_movil), Formato.bytes(movil)))

        contDatos.addView(Ui.seccion(this, getString(if (periodo == 0) R.string.tr_por_horas else R.string.tr_por_dias)))
        val barras = BarrasDiasView(this)
        barras.colorA = Tema.azul(this)
        barras.colorB = Tema.naranja(this)
        barras.dias = serie.map { BarrasDiasView.Dia(it.etiqueta, it.wifi.toFloat(), it.movil.toFloat()) }
        barras.etiquetaMaximo = Formato.bytes(serie.maxOf { it.total })
        contDatos.addView(barras)

        contDatos.addView(Ui.seccion(this, getString(R.string.tr_apps)))
        val top = apps.take(20)
        val maximo = maxOf(top.firstOrNull()?.total ?: 1L, 1L).toFloat()
        for (a in top) {
            val cont = Ui.columna(this, 0)
            cont.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5))
            cont.addView(Ui.fila(this, Uids.nombre(this, a.uid), Formato.bytes(a.total)))
            val b = BarraView(this)
            b.altoDp = 7
            b.segmentos = listOf(
                BarraView.Segmento(a.wifi / maximo, Tema.azul(this)),
                BarraView.Segmento(a.movil / maximo, Tema.naranja(this))
            )
            cont.addView(b)
            cont.isClickable = true
            cont.setOnClickListener { detalleApp(a, rangos) }
            contDatos.addView(cont)
        }
    }

    private fun nombrePeriodo(): String {
        return when (periodo) {
            0 -> getString(R.string.periodo_hoy)
            2 -> getString(R.string.periodo_30)
            else -> getString(R.string.periodo_7)
        }
    }

    private fun detalleApp(a: AppTrafico, rangos: List<TraficoRepo.Rango>) {
        val hoja = BottomSheetDialog(this)
        val col = Ui.columna(this, 18)
        col.addView(Ui.titulo(this, Uids.nombre(this, a.uid)))
        col.addView(Ui.texto(this, getString(R.string.tr_detalle_app, nombrePeriodo(), Formato.bytes(a.total), Formato.bytes(a.wifi), Formato.bytes(a.movil)), 13f))
        val grafica = BarrasDiasView(this)
        grafica.colorA = Tema.azul(this)
        grafica.colorB = Tema.naranja(this)
        col.addView(grafica)
        col.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.tr_wifi)))
        col.addView(Ui.leyenda(this, Tema.naranja(this), getString(R.string.tr_movil)))
        val paquetes = packageManager.getPackagesForUid(a.uid)
        if (paquetes != null && paquetes.isNotEmpty()) {
            val paquete = paquetes[0]
            col.addView(Ui.botonSecundario(this, getString(R.string.proc_ajustes)) {
                if (!Sistema.abrirAjustesApp(this, paquete)) aviso(R.string.no_se_pudo_abrir)
            })
        }
        hoja.setContentView(col)
        hoja.show()
        lifecycleScope.launch {
            val serie = TraficoRepo.serieUid(this@TraficoActivity, a.uid, rangos)
            grafica.dias = serie.map { BarrasDiasView.Dia(it.etiqueta, it.wifi.toFloat(), it.movil.toFloat()) }
            grafica.etiquetaMaximo = Formato.bytes(serie.maxOf { it.total })
        }
    }
}
