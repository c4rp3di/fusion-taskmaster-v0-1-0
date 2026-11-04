package com.vigia.taskmaster

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.vigia.taskmaster.adb.ModoAvanzadoActivity
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.BarraView
import com.vigia.taskmaster.core.DonutView
import com.vigia.taskmaster.core.Esenciales
import com.vigia.taskmaster.core.Formato
import com.vigia.taskmaster.core.Portapapeles
import com.vigia.taskmaster.core.Sistema
import com.vigia.taskmaster.core.Tema
import com.vigia.taskmaster.core.Ui
import com.vigia.taskmaster.datos.MemoriaRepo
import com.vigia.taskmaster.datos.ProcMem
import com.vigia.taskmaster.datos.ResumenMemoria
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Memoria RAM: reparto (apps, caché, libre), swap, y quién la usa de verdad (PSS, que reparte la memoria compartida entre
 * apps). Con el Modo Avanzado se puede detener una app y liberar los procesos en caché.
 */
class MemoriaActivity : ActividadBase() {

    private var cargando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usarPantallaScroll(R.string.sec_memoria, R.string.ayuda_memoria_titulo, R.string.ayuda_memoria)
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    private fun cargar() {
        if (cargando) return
        cargando = true
        contenido.removeAllViews()
        pintarBasico()
        lifecycleScope.launch {
            val estado = ShellManager.verificar()
            if (estado != ShellManager.Estado.CONECTADO) {
                pintarSinShell()
                cargando = false
                return@launch
            }
            contenido.addView(Ui.texto(this@MemoriaActivity, getString(R.string.cargando), 13f))
            try {
                val r = MemoriaRepo.leer(this@MemoriaActivity)
                pintar(r)
            } catch (e: Exception) {
                contenido.addView(Ui.texto(this@MemoriaActivity, getString(R.string.error_shell, e.message ?: e.javaClass.simpleName), 13f))
            }
            cargando = false
        }
    }

    /** Lo que se puede saber sin el Modo Avanzado: totales del sistema. */
    private fun pintarBasico() {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val usada = info.totalMem - info.availMem
        val (t, c) = Ui.tarjetaConColumna(this)
        val donut = DonutView(this)
        donut.porciones = listOf(DonutView.Porcion(usada.toFloat(), Tema.verde(this)), DonutView.Porcion(info.availMem.toFloat(), Tema.gris(this)))
        donut.textoCentro = Formato.porcentaje(usada * 100.0 / maxOf(info.totalMem, 1L))
        donut.subtexto = getString(R.string.mem_en_uso)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(donut, lp)
        c.addView(Ui.leyenda(this, Tema.verde(this), getString(R.string.mem_l_usada), Formato.bytes(usada)))
        c.addView(Ui.leyenda(this, Tema.gris(this), getString(R.string.mem_l_disponible), Formato.bytes(info.availMem)))
        c.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.mem_l_total), Formato.bytes(info.totalMem)))
        if (info.lowMemory) c.addView(Ui.texto(this, getString(R.string.mem_baja), 13f, true, Tema.rojo(this)))
        contenido.addView(t)
    }

    private fun pintarSinShell() {
        val (t, c) = Ui.tarjetaConColumna(this)
        c.addView(Ui.titulo(this, getString(R.string.mem_detalle_titulo)))
        c.addView(Ui.texto(this, getString(R.string.mem_requiere_modo), 13f))
        c.addView(Ui.boton(this, getString(R.string.abrir_modo_avanzado)) {
            startActivity(Intent(this, ModoAvanzadoActivity::class.java))
        })
        contenido.addView(t)
    }

    private fun pintar(r: ResumenMemoria) {
        contenido.removeAllViews()
        if (r.totalKb <= 0L) {
            pintarBasico()
            contenido.addView(Ui.texto(this, getString(R.string.mem_sin_datos), 13f))
            return
        }

        // Reparto de la RAM
        val apps = maxOf(r.totalKb - r.libreKb - r.cacheKb, 0L)
        val (t1, c1) = Ui.tarjetaConColumna(this)
        val donut = DonutView(this)
        donut.porciones = listOf(
            DonutView.Porcion(apps.toFloat(), Tema.verde(this)),
            DonutView.Porcion(r.cacheKb.toFloat(), Tema.azul(this)),
            DonutView.Porcion(r.libreKb.toFloat(), Tema.gris(this))
        )
        donut.textoCentro = Formato.porcentaje((r.totalKb - r.disponibleKb) * 100.0 / r.totalKb)
        donut.subtexto = getString(R.string.mem_en_uso)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        c1.addView(donut, lp)
        c1.addView(Ui.leyenda(this, Tema.verde(this), getString(R.string.mem_l_apps), Formato.kb(apps)))
        c1.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.mem_l_cache), Formato.kb(r.cacheKb)))
        c1.addView(Ui.leyenda(this, Tema.gris(this), getString(R.string.mem_l_libre), Formato.kb(r.libreKb)))
        c1.addView(Ui.texto(this, getString(R.string.mem_nota_cache), 11f, false, Tema.gris(this)))
        if (r.swapTotalKb > 0L) {
            val usadaSwap = r.swapTotalKb - r.swapLibreKb
            c1.addView(Ui.espacio(this, 8))
            c1.addView(Ui.fila(this, getString(R.string.proc_swap), Formato.kb(usadaSwap) + " / " + Formato.kb(r.swapTotalKb)))
            val b = BarraView(this)
            b.simple(usadaSwap.toFloat() / r.swapTotalKb.toFloat(), Tema.morado(this))
            c1.addView(b)
        }
        contenido.addView(t1)

        // Quién la usa
        val (t2, c2) = Ui.tarjetaConColumna(this)
        c2.addView(Ui.titulo(this, getString(R.string.mem_quien)))
        c2.addView(Ui.texto(this, getString(R.string.mem_quien_info), 12f, false, Tema.gris(this)))
        val top = r.procesos.take(25)
        val maximo = (top.firstOrNull()?.pssKb ?: 1L).toFloat()
        for (p in top) {
            val fila = Ui.fila(this, p.etiqueta, Formato.kb(p.pssKb))
            val b = BarraView(this)
            b.altoDp = 6
            b.simple(p.pssKb.toFloat() / maximo, Tema.verde(this))
            val cont = Ui.columna(this, 0)
            cont.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5))
            cont.addView(fila)
            cont.addView(b)
            cont.isClickable = true
            cont.setOnClickListener { opciones(p) }
            c2.addView(cont)
        }
        contenido.addView(t2)

        // Resumen del sistema
        if (r.lineasSistema.isNotEmpty()) {
            val (t3, c3) = Ui.tarjetaConColumna(this)
            c3.addView(Ui.titulo(this, getString(R.string.mem_resumen_sistema)))
            c3.addView(Ui.monoespaciado(this, r.lineasSistema.joinToString("\n"), 11f))
            contenido.addView(t3)
        }

        // Acciones
        val (t4, c4) = Ui.tarjetaConColumna(this)
        c4.addView(Ui.titulo(this, getString(R.string.opciones)))
        c4.addView(Ui.boton(this, getString(R.string.mem_liberar)) { confirmarLiberar(r) })
        c4.addView(Ui.botonSecundario(this, getString(R.string.mem_ver_procesos)) {
            startActivity(Intent(this, ProcesosActivity::class.java))
        })
        c4.addView(Ui.botonSecundario(this, getString(R.string.actualizar)) { cargar() })
        contenido.addView(t4)
    }

    private fun opciones(p: ProcMem) {
        val opciones = arrayOf(getString(R.string.proc_detener), getString(R.string.proc_ajustes), getString(R.string.copiar_paquete))
        AlertDialog.Builder(this)
            .setTitle(p.etiqueta)
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> confirmarDetener(p)
                    1 -> {
                        if (!Sistema.abrirAjustesApp(this, p.paquete)) aviso(R.string.no_se_pudo_abrir)
                    }
                    else -> {
                        Portapapeles.copiar(this, p.paquete)
                        aviso(R.string.copiado)
                    }
                }
            }
            .show()
    }

    private fun confirmarDetener(p: ProcMem) {
        val motivo = Esenciales.motivo(this, p.paquete)
        if (motivo != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_se_puede_detener)
                .setMessage(getString(R.string.motivo_esencial, p.etiqueta, motivo))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(p.etiqueta)
            .setMessage(getString(R.string.confirmar_detener, p.etiqueta))
            .setPositiveButton(R.string.proc_detener_corto) { _, _ ->
                lifecycleScope.launch {
                    try {
                        ShellManager.ejecutar("am force-stop " + p.paquete)
                    } catch (e: Exception) {
                        aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
                    }
                    delay(800L)
                    cargando = false
                    cargar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmarLiberar(antes: ResumenMemoria) {
        AlertDialog.Builder(this)
            .setTitle(R.string.mem_liberar)
            .setMessage(R.string.mem_liberar_aviso)
            .setPositiveButton(R.string.ejecutar) { _, _ ->
                lifecycleScope.launch {
                    try {
                        ShellManager.ejecutar("am kill-all")
                        delay(1500L)
                        val despues = MemoriaRepo.leer(this@MemoriaActivity)
                        val liberado = despues.disponibleKb - antes.disponibleKb
                        aviso(getString(R.string.mem_liberada, Formato.kb(maxOf(liberado, 0L)), Formato.kb(despues.disponibleKb)))
                        cargando = false
                        cargar()
                    } catch (e: Exception) {
                        aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
