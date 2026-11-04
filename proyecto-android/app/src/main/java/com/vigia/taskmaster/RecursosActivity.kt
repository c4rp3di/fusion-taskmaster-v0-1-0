package com.vigia.taskmaster

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.vigia.taskmaster.core.AccesoUso
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.BarraView
import com.vigia.taskmaster.core.Formato
import com.vigia.taskmaster.core.Tema
import com.vigia.taskmaster.core.Ui
import com.vigia.taskmaster.datos.TraficoRepo
import kotlinx.coroutines.launch

/** Monitor de recursos: un resumen de cada área; al tocar una tarjeta se abre su pantalla con más datos y opciones. */
class RecursosActivity : ActividadBase() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usarPantallaScroll(R.string.menu_recursos, R.string.ayuda_recursos_titulo, R.string.ayuda_recursos)
    }

    override fun onResume() {
        super.onResume()
        construir()
    }

    private class Tarjeta(val resumen: TextView, val barra: BarraView)

    private fun tarjeta(titulo: String, resumen: String, destino: Class<*>): Tarjeta {
        val (t, col) = Ui.tarjetaConColumna(this)
        col.addView(Ui.titulo(this, titulo + "  ›"))
        val txt = Ui.texto(this, resumen, 13f)
        col.addView(txt)
        val barra = BarraView(this)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 8)
        col.addView(barra, lp)
        t.isClickable = true
        t.setOnClickListener { startActivity(Intent(this, destino)) }
        contenido.addView(t)
        return Tarjeta(txt, barra)
    }

    private fun construir() {
        contenido.removeAllViews()

        // Batería
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val nivel = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (nivel >= 0 && escala > 0) nivel * 100 / escala else 0
        val estado = when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) {
            BatteryManager.BATTERY_STATUS_CHARGING -> getString(R.string.bat_cargando)
            BatteryManager.BATTERY_STATUS_FULL -> getString(R.string.bat_llena)
            BatteryManager.BATTERY_STATUS_DISCHARGING -> getString(R.string.bat_descargando)
            else -> getString(R.string.bat_sin_cargar)
        }
        val colorBat = if (pct < 20) Tema.rojo(this) else if (pct < 40) Tema.ambar(this) else Tema.verde(this)
        val bat = tarjeta(getString(R.string.sec_bateria), getString(R.string.bat_resumen, pct, estado), BateriaActivity::class.java)
        bat.barra.simple(pct / 100f, colorBat)

        // Memoria
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val usada = info.totalMem - info.availMem
        val mem = tarjeta(
            getString(R.string.sec_memoria),
            getString(R.string.mem_resumen, Formato.bytes(usada), Formato.bytes(info.totalMem), Formato.bytes(info.availMem)),
            MemoriaActivity::class.java
        )
        mem.barra.simple(if (info.totalMem > 0) usada.toFloat() / info.totalMem.toFloat() else 0f, Tema.verde(this))

        // Almacenamiento
        var almacen = getString(R.string.no_disponible)
        var fraccion = 0f
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.blockCountLong * st.blockSizeLong
            val libre = st.availableBlocksLong * st.blockSizeLong
            almacen = getString(R.string.alm_resumen, Formato.bytes(total - libre), Formato.bytes(total), Formato.bytes(libre))
            fraccion = if (total > 0L) (total - libre).toFloat() / total.toFloat() else 0f
        } catch (e: Exception) {
        }
        val alm = tarjeta(getString(R.string.sec_almacenamiento), almacen, AlmacenamientoActivity::class.java)
        alm.barra.simple(fraccion, Tema.azul(this))

        // Tráfico de red
        val tra = tarjeta(getString(R.string.sec_trafico), getString(R.string.trafico_toca), TraficoActivity::class.java)
        if (AccesoUso.concedido(this)) {
            lifecycleScope.launch {
                val hoy = TraficoRepo.serie(this@RecursosActivity, TraficoRepo.rangosDias(1))
                val wifi = hoy.sumOf { it.wifi }
                val movil = hoy.sumOf { it.movil }
                val total = wifi + movil
                tra.resumen.text = getString(R.string.trafico_hoy_resumen, Formato.bytes(total), Formato.bytes(wifi), Formato.bytes(movil))
                if (total > 0L) {
                    tra.barra.segmentos = listOf(
                        BarraView.Segmento(wifi.toFloat() / total.toFloat(), Tema.azul(this@RecursosActivity)),
                        BarraView.Segmento(movil.toFloat() / total.toFloat(), Tema.naranja(this@RecursosActivity))
                    )
                }
            }
        }
    }
}
