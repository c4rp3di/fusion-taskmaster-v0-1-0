package com.vigia.taskmaster

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.AccesoUso
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.BarraView
import com.vigia.taskmaster.core.DonutView
import com.vigia.taskmaster.core.Formato
import com.vigia.taskmaster.core.Portapapeles
import com.vigia.taskmaster.core.Sistema
import com.vigia.taskmaster.core.Tema
import com.vigia.taskmaster.core.Ui
import com.vigia.taskmaster.datos.AlmacenRepo
import com.vigia.taskmaster.datos.AppAlmacen
import com.vigia.taskmaster.datos.ResumenAlmacen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Almacenamiento: reparto del espacio (apps, fotos, vídeos, audio, otros), caché de las apps, las apps que más ocupan
 * (separando app, datos y caché) y opciones para liberar espacio.
 */
class AlmacenamientoActivity : ActividadBase() {

    private var datos: ResumenAlmacen? = null
    private var orden = 0
    private var filtro = 0
    private lateinit var contLista: LinearLayout
    private var cargando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usarPantallaScroll(R.string.sec_almacenamiento, R.string.ayuda_almacen_titulo, R.string.ayuda_almacen)
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    private fun cargar() {
        if (cargando) return
        contenido.removeAllViews()
        if (!AccesoUso.concedido(this)) {
            val t = AccesoUso.tarjeta(this) { cargar() }
            if (t != null) contenido.addView(t)
            pintarBasico()
            return
        }
        cargando = true
        contenido.addView(Ui.texto(this, getString(R.string.alm_calculando), 13f))
        lifecycleScope.launch {
            try {
                datos = AlmacenRepo.leer(this@AlmacenamientoActivity)
                pintar()
            } catch (e: Exception) {
                contenido.removeAllViews()
                contenido.addView(Ui.texto(this@AlmacenamientoActivity, getString(R.string.error_shell, e.message ?: e.javaClass.simpleName), 13f))
            }
            cargando = false
        }
    }

    private fun pintarBasico() {
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.blockCountLong * st.blockSizeLong
            val libre = st.availableBlocksLong * st.blockSizeLong
            val (t, c) = Ui.tarjetaConColumna(this)
            val donut = DonutView(this)
            donut.porciones = listOf(DonutView.Porcion((total - libre).toFloat(), Tema.azul(this)), DonutView.Porcion(libre.toFloat(), Tema.gris(this)))
            donut.textoCentro = Formato.porcentaje((total - libre) * 100.0 / maxOf(total, 1L))
            donut.subtexto = getString(R.string.alm_ocupado)
            c.addView(donut, centrado())
            c.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.alm_l_usado), Formato.bytes(total - libre)))
            c.addView(Ui.leyenda(this, Tema.gris(this), getString(R.string.alm_l_libre), Formato.bytes(libre)))
            contenido.addView(t)
        } catch (e: Exception) {
        }
    }

    private fun centrado(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        return lp
    }

    private fun pintar() {
        val d = datos ?: return
        contenido.removeAllViews()

        // Reparto del espacio
        val usado = d.totalBytes - d.libreBytes
        val otros = maxOf(usado - d.appsBytes - d.imagenesBytes - d.videosBytes - d.audioBytes, 0L)
        val (t1, c1) = Ui.tarjetaConColumna(this)
        val donut = DonutView(this)
        donut.porciones = listOf(
            DonutView.Porcion(d.appsBytes.toFloat(), Tema.azul(this)),
            DonutView.Porcion(d.imagenesBytes.toFloat(), Tema.verde(this)),
            DonutView.Porcion(d.videosBytes.toFloat(), Tema.morado(this)),
            DonutView.Porcion(d.audioBytes.toFloat(), Tema.naranja(this)),
            DonutView.Porcion(otros.toFloat(), Tema.cian(this)),
            DonutView.Porcion(d.libreBytes.toFloat(), Tema.gris(this))
        )
        donut.textoCentro = Formato.porcentaje(usado * 100.0 / maxOf(d.totalBytes, 1L))
        donut.subtexto = getString(R.string.alm_ocupado)
        c1.addView(donut, centrado())
        c1.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.alm_l_apps), Formato.bytes(d.appsBytes)))
        c1.addView(Ui.leyenda(this, Tema.verde(this), getString(R.string.alm_l_imagenes), Formato.bytes(d.imagenesBytes)))
        c1.addView(Ui.leyenda(this, Tema.morado(this), getString(R.string.alm_l_videos), Formato.bytes(d.videosBytes)))
        c1.addView(Ui.leyenda(this, Tema.naranja(this), getString(R.string.alm_l_audio), Formato.bytes(d.audioBytes)))
        c1.addView(Ui.leyenda(this, Tema.cian(this), getString(R.string.alm_l_otros), Formato.bytes(otros)))
        c1.addView(Ui.leyenda(this, Tema.gris(this), getString(R.string.alm_l_libre), Formato.bytes(d.libreBytes)))
        c1.addView(Ui.texto(this, getString(R.string.alm_total_nota, Formato.bytes(d.totalBytes)), 11f, false, Tema.gris(this)))
        contenido.addView(t1)

        // Caché
        val (t2, c2) = Ui.tarjetaConColumna(this)
        c2.addView(Ui.titulo(this, getString(R.string.alm_cache_titulo)))
        c2.addView(Ui.texto(this, getString(R.string.alm_cache_info, Formato.bytes(d.cacheBytes)), 13f))
        c2.addView(Ui.boton(this, getString(R.string.alm_limpiar_caches)) { confirmarLimpiarCaches(d) })
        contenido.addView(t2)

        // Apps que más ocupan
        val (t3, c3) = Ui.tarjetaConColumna(this)
        c3.addView(Ui.titulo(this, getString(R.string.alm_apps_titulo)))
        val ordenes = LinearLayout(this)
        ordenes.orientation = LinearLayout.HORIZONTAL
        val nombresOrden = arrayOf(R.string.alm_o_total, R.string.alm_o_apps, R.string.alm_o_datos, R.string.alm_o_cache)
        for (i in nombresOrden.indices) {
            ordenes.addView(Ui.botonSecundario(this, getString(nombresOrden[i])) {
                orden = i
                pintarLista()
            })
        }
        c3.addView(ordenes)
        val filtros = LinearLayout(this)
        filtros.orientation = LinearLayout.HORIZONTAL
        val nombresFiltro = arrayOf(R.string.alm_f_todas, R.string.alm_f_usuario, R.string.alm_f_sistema)
        for (i in nombresFiltro.indices) {
            filtros.addView(Ui.botonSecundario(this, getString(nombresFiltro[i])) {
                filtro = i
                pintarLista()
            })
        }
        c3.addView(filtros)
        c3.addView(Ui.leyenda(this, Tema.azul(this), getString(R.string.alm_leg_app)))
        c3.addView(Ui.leyenda(this, Tema.verde(this), getString(R.string.alm_leg_datos)))
        c3.addView(Ui.leyenda(this, Tema.naranja(this), getString(R.string.alm_leg_cache)))
        contLista = Ui.columna(this, 0)
        c3.addView(contLista)
        contenido.addView(t3)
        pintarLista()

        // Opciones
        val (t4, c4) = Ui.tarjetaConColumna(this)
        c4.addView(Ui.titulo(this, getString(R.string.opciones)))
        c4.addView(Ui.botonSecundario(this, getString(R.string.alm_op_ajustes)) {
            if (!Sistema.abrir(this, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) aviso(R.string.no_se_pudo_abrir)
        })
        c4.addView(Ui.botonSecundario(this, getString(R.string.alm_op_liberar)) {
            if (!Sistema.abrir(this, StorageManager.ACTION_MANAGE_STORAGE)) aviso(R.string.no_se_pudo_abrir)
        })
        c4.addView(Ui.botonSecundario(this, getString(R.string.actualizar)) { cargar() })
        contenido.addView(t4)
    }

    private fun pintarLista() {
        val d = datos ?: return
        contLista.removeAllViews()
        var lista = d.apps.filter { (filtro == 0) || (filtro == 1 && !it.esSistema) || (filtro == 2 && it.esSistema) }
        lista = when (orden) {
            1 -> lista.sortedByDescending { it.appBytes }
            2 -> lista.sortedByDescending { it.datosBytes }
            3 -> lista.sortedByDescending { it.cacheBytes }
            else -> lista.sortedByDescending { it.total }
        }
        val top = lista.take(25)
        val maximo = maxOf(top.maxOfOrNull { it.total } ?: 1L, 1L).toFloat()
        for (a in top) {
            val cont = Ui.columna(this, 0)
            cont.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5))
            cont.addView(Ui.fila(this, a.etiqueta, Formato.bytes(a.total)))
            val b = BarraView(this)
            b.altoDp = 7
            b.segmentos = listOf(
                BarraView.Segmento(a.appBytes / maximo, Tema.azul(this)),
                BarraView.Segmento(a.datosBytes / maximo, Tema.verde(this)),
                BarraView.Segmento(a.cacheBytes / maximo, Tema.naranja(this))
            )
            cont.addView(b)
            cont.isClickable = true
            cont.setOnClickListener { opciones(a) }
            contLista.addView(cont)
        }
    }

    private fun opciones(a: AppAlmacen) {
        val etiquetas = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        etiquetas.add(getString(R.string.alm_op_borrar_cache))
        acciones.add { if (!Sistema.abrirAjustesApp(this, a.paquete)) aviso(R.string.no_se_pudo_abrir) }
        etiquetas.add(getString(R.string.copiar_paquete))
        acciones.add {
            Portapapeles.copiar(this, a.paquete)
            aviso(R.string.copiado)
        }
        if (!a.esSistema) {
            etiquetas.add(getString(R.string.desinstalar))
            acciones.add {
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.paquete)))
                } catch (e: Exception) {
                    aviso(R.string.no_se_pudo_abrir)
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(a.etiqueta + " · " + Formato.bytes(a.total))
            .setItems(etiquetas.toTypedArray()) { _, cual -> acciones[cual]() }
            .show()
    }

    private fun confirmarLimpiarCaches(antes: ResumenAlmacen) {
        if (ShellManager.estado() != ShellManager.Estado.CONECTADO) {
            aviso(R.string.requiere_modo_avanzado)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.alm_limpiar_caches)
            .setMessage(R.string.alm_limpiar_aviso)
            .setPositiveButton(R.string.ejecutar) { _, _ ->
                lifecycleScope.launch {
                    try {
                        ShellManager.ejecutar("pm trim-caches 999G")
                        delay(1500L)
                        val despues = AlmacenRepo.leer(this@AlmacenamientoActivity)
                        datos = despues
                        aviso(getString(R.string.alm_liberado, Formato.bytes(maxOf(despues.libreBytes - antes.libreBytes, 0L))))
                        pintar()
                    } catch (e: Exception) {
                        aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
