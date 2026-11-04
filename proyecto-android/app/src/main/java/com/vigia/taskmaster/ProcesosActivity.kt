package com.vigia.taskmaster

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.adb.ModoAvanzadoActivity
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.BarraView
import com.vigia.taskmaster.core.Esenciales
import com.vigia.taskmaster.core.Formato
import com.vigia.taskmaster.core.Portapapeles
import com.vigia.taskmaster.core.Tema
import com.vigia.taskmaster.core.Ui
import com.vigia.taskmaster.datos.AppProc
import com.vigia.taskmaster.datos.CargaProcesos
import com.vigia.taskmaster.datos.ProcInfo
import com.vigia.taskmaster.datos.ProcesosRepo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gestor de procesos al estilo htop: cabecera con CPU, RAM y swap; lista por app que se despliega en sus procesos; barras de
 * memoria y CPU; ordenación y filtros. Los datos se guardan en memoria y solo se vuelven a leer al pulsar «Actualizar» o con
 * la actualización automática (desactivada por defecto), para no gastar de más.
 */
class ProcesosActivity : ActividadBase() {

    private sealed class Fila
    private class FilaApp(val app: AppProc) : Fila()
    private class FilaProceso(val app: AppProc, val proceso: ProcInfo) : Fila()

    private lateinit var lista: ListView
    private lateinit var etBuscar: EditText
    private lateinit var spOrden: Spinner
    private lateinit var spFiltro: Spinner
    private lateinit var chkBarras: CheckBox
    private lateinit var chkAuto: CheckBox
    private lateinit var resumen: TextView
    private lateinit var avisoShell: TextView
    private lateinit var btnModo: MaterialButton
    private lateinit var cabecera: LinearLayout
    private lateinit var adaptador: ArrayAdapter<Fila>

    private var carga: CargaProcesos? = null
    private val desplegadas = HashSet<String>()
    private var cargando = false
    private var trabajoAuto: Job? = null
    private var maxRss = 1L
    private var maxCpu = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_procesos)
        prepararPantalla(R.string.ayuda_procesos_titulo, R.string.ayuda_procesos)

        lista = findViewById(R.id.listaProcesos)
        etBuscar = findViewById(R.id.etBuscarProc)
        spOrden = findViewById(R.id.spOrden)
        spFiltro = findViewById(R.id.spFiltro)
        chkBarras = findViewById(R.id.chkProcBarras)
        chkAuto = findViewById(R.id.chkProcAuto)
        resumen = findViewById(R.id.procResumen)
        avisoShell = findViewById(R.id.procAviso)
        btnModo = findViewById(R.id.btnProcModoAvanzado)
        cabecera = findViewById(R.id.cabeceraSistema)

        spOrden.adapter = ArrayAdapter.createFromResource(this, R.array.proc_ordenes, android.R.layout.simple_spinner_dropdown_item)
        spFiltro.adapter = ArrayAdapter.createFromResource(this, R.array.proc_filtros, android.R.layout.simple_spinner_dropdown_item)
        val escucha = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(padre: android.widget.AdapterView<*>?, vista: View?, posicion: Int, id: Long) {
                aplicar()
            }

            override fun onNothingSelected(padre: android.widget.AdapterView<*>?) {
            }
        }
        spOrden.onItemSelectedListener = escucha
        spFiltro.onItemSelectedListener = escucha

        adaptador = FilasAdapter()
        lista.adapter = adaptador
        lista.setOnItemClickListener { _, _, posicion, _ ->
            when (val f = adaptador.getItem(posicion)) {
                is FilaApp -> alternar(f.app)
                is FilaProceso -> opcionesProceso(f.app, f.proceso)
                null -> {
                }
            }
        }
        lista.setOnItemLongClickListener { _, _, posicion, _ ->
            val f = adaptador.getItem(posicion)
            if (f is FilaApp) {
                opcionesApp(f.app)
                true
            } else if (f is FilaProceso) {
                opcionesApp(f.app)
                true
            } else {
                false
            }
        }

        findViewById<MaterialButton>(R.id.btnProcActualizar).setOnClickListener { cargar() }
        btnModo.setOnClickListener { startActivity(Intent(this, ModoAvanzadoActivity::class.java)) }
        etBuscar.doAfterTextChanged { aplicar() }
        chkBarras.setOnCheckedChangeListener { _, _ -> adaptador.notifyDataSetChanged() }
        chkAuto.setOnCheckedChangeListener { _, marcado -> if (marcado) iniciarAuto() else detenerAuto() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val e = ShellManager.verificar()
            val conectado = e == ShellManager.Estado.CONECTADO
            avisoShell.visibility = if (conectado) View.GONE else View.VISIBLE
            btnModo.visibility = if (conectado) View.GONE else View.VISIBLE
            if (conectado) {
                // Si ya hay datos guardados se muestran al instante y no se vuelve a leer nada.
                val guardada = ProcesosRepo.ultima
                if (guardada != null) {
                    carga = guardada
                    aplicar()
                } else {
                    cargar()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        chkAuto.isChecked = false
        detenerAuto()
    }

    private fun iniciarAuto() {
        detenerAuto()
        trabajoAuto = lifecycleScope.launch {
            while (isActive) {
                delay(INTERVALO_AUTO_MS)
                cargar()
            }
        }
    }

    private fun detenerAuto() {
        trabajoAuto?.cancel()
        trabajoAuto = null
    }

    private fun cargar() {
        if (cargando) return
        cargando = true
        resumen.setText(R.string.cargando)
        lifecycleScope.launch {
            try {
                carga = ProcesosRepo.cargar(this@ProcesosActivity)
                aplicar()
            } catch (e: Exception) {
                resumen.text = getString(R.string.error_shell, e.message ?: e.javaClass.simpleName)
            }
            cargando = false
        }
    }

    private fun alternar(app: AppProc) {
        if (!desplegadas.add(app.paquete)) desplegadas.remove(app.paquete)
        aplicar()
    }

    // ------------------------------------------------------------------ lista

    private fun aplicar() {
        val c = carga ?: return
        pintarCabecera(c)
        val texto = etBuscar.text.toString().trim().lowercase()
        val filtro = spFiltro.selectedItemPosition
        var apps = c.apps.filter { a ->
            (filtro == 2 || (filtro == 0 && a.esApp) || (filtro == 1 && !a.esApp)) &&
                (texto.isEmpty() || a.etiqueta.lowercase().contains(texto) || a.paquete.lowercase().contains(texto))
        }
        apps = when (spOrden.selectedItemPosition) {
            1 -> apps.sortedByDescending { it.cpu }
            2 -> apps.sortedBy { it.etiqueta.lowercase() }
            3 -> apps.sortedByDescending { it.procesos.size }
            else -> apps.sortedByDescending { it.rssKb }
        }
        maxRss = maxOf(apps.maxOfOrNull { it.rssKb } ?: 1L, 1L)
        maxCpu = maxOf(apps.maxOfOrNull { it.cpu } ?: 1f, 1f)

        val filas = ArrayList<Fila>()
        for (a in apps) {
            filas.add(FilaApp(a))
            if (desplegadas.contains(a.paquete)) {
                for (p in a.procesos.sortedByDescending { it.rssKb }) filas.add(FilaProceso(a, p))
            }
        }
        adaptador.setNotifyOnChange(false)
        adaptador.clear()
        adaptador.addAll(filas)
        adaptador.notifyDataSetChanged()
        val totalMb = apps.sumOf { it.rssKb } / 1024L
        resumen.text = getString(R.string.proc_resumen, apps.size, c.apps.size, totalMb) + " · " +
            getString(R.string.proc_actualizado, Formato.hace(System.currentTimeMillis() - c.instante))
    }

    private fun pintarCabecera(c: CargaProcesos) {
        cabecera.removeAllViews()
        val (tarjeta, col) = Ui.tarjetaConColumna(this)
        val cpu = c.cpu
        if (cpu != null) {
            col.addView(Ui.fila(this, getString(R.string.proc_cpu, cpu.nucleos), Formato.porcentaje(cpu.usoPct.toDouble())))
            val b = BarraView(this)
            b.altoDp = 8
            b.segmentos = listOf(
                BarraView.Segmento(cpu.usuarioPct / 100f, Tema.azul(this)),
                BarraView.Segmento(cpu.sistemaPct / 100f, Tema.naranja(this))
            )
            col.addView(b)
            col.addView(Ui.espacio(this, 6))
        }
        val mem = c.mem
        if (mem != null) {
            val usada = mem.totalKb - mem.disponibleKb
            col.addView(Ui.fila(this, getString(R.string.proc_ram), Formato.kb(usada) + " / " + Formato.kb(mem.totalKb)))
            val b = BarraView(this)
            b.altoDp = 8
            b.simple(usada.toFloat() / mem.totalKb.toFloat(), Tema.verde(this))
            col.addView(b)
            if (mem.swapTotalKb > 0L) {
                col.addView(Ui.espacio(this, 6))
                val swapUsada = mem.swapTotalKb - mem.swapLibreKb
                col.addView(Ui.fila(this, getString(R.string.proc_swap), Formato.kb(swapUsada) + " / " + Formato.kb(mem.swapTotalKb)))
                val s = BarraView(this)
                s.altoDp = 8
                s.simple(swapUsada.toFloat() / mem.swapTotalKb.toFloat(), Tema.morado(this))
                col.addView(s)
            }
        }
        if (cpu == null && mem == null) col.addView(Ui.texto(this, getString(R.string.proc_sin_cabecera), 12f))
        cabecera.addView(tarjeta)
    }

    private inner class FilasAdapter : ArrayAdapter<Fila>(this@ProcesosActivity, R.layout.item_proceso) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_proceso, parent, false)
            val titulo = v.findViewById<TextView>(R.id.procTitulo)
            val valor = v.findViewById<TextView>(R.id.procValor)
            val sub = v.findViewById<TextView>(R.id.procSub)
            val barraRam = v.findViewById<BarraView>(R.id.procBarraRam)
            val barraCpu = v.findViewById<BarraView>(R.id.procBarraCpu)
            barraRam.altoDp = 5
            barraCpu.altoDp = 5
            val hayCpu = carga?.cpu != null
            val f = getItem(position)
            val sangria = Ui.dp(context, 8)
            when (f) {
                is FilaApp -> {
                    val a = f.app
                    val flecha = if (desplegadas.contains(a.paquete)) "▾ " else "▸ "
                    titulo.text = flecha + a.etiqueta + (if (a.esApp) "" else " · sistema")
                    valor.text = valorTexto(a.rssKb, a.cpu, hayCpu)
                    sub.text = a.paquete + " · " + a.procesos.size + " proceso(s)"
                    barraRam.simple(a.rssKb.toFloat() / maxRss.toFloat(), Tema.verde(context))
                    barraCpu.simple(a.cpu / maxCpu, Tema.azul(context))
                    v.setPadding(sangria, v.paddingTop, sangria, v.paddingBottom)
                }
                is FilaProceso -> {
                    val p = f.proceso
                    titulo.text = p.nombre
                    valor.text = valorTexto(p.rssKb, p.cpu, hayCpu)
                    sub.text = "PID " + p.pid
                    barraRam.simple(p.rssKb.toFloat() / maxOf(f.app.rssKb, 1L).toFloat(), Tema.verde(context))
                    barraCpu.simple(p.cpu / maxOf(f.app.cpu, 1f), Tema.azul(context))
                    v.setPadding(sangria * 4, v.paddingTop, sangria, v.paddingBottom)
                }
                null -> {
                }
            }
            val verBarras = chkBarras.isChecked
            barraRam.visibility = if (verBarras) View.VISIBLE else View.GONE
            barraCpu.visibility = if (verBarras && hayCpu) View.VISIBLE else View.GONE
            return v
        }
    }

    private fun valorTexto(rssKb: Long, cpu: Float, hayCpu: Boolean): String {
        val mem = Formato.kb(rssKb)
        return if (hayCpu) mem + " · " + Formato.decimal(cpu.toDouble(), 1) + "%" else mem
    }

    // ------------------------------------------------------------------ opciones

    private fun opcionesApp(a: AppProc) {
        val opciones = arrayOf(
            getString(R.string.proc_detener),
            getString(R.string.proc_detener_fondo),
            getString(R.string.proc_ajustes),
            getString(R.string.copiar_paquete)
        )
        AlertDialog.Builder(this)
            .setTitle(a.etiqueta)
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> confirmarDetener(a, false)
                    1 -> confirmarDetener(a, true)
                    2 -> abrirAjustes(a.paquete)
                    else -> {
                        Portapapeles.copiar(this, a.paquete)
                        aviso(R.string.copiado)
                    }
                }
            }
            .show()
    }

    private fun opcionesProceso(a: AppProc, p: ProcInfo) {
        val opciones = arrayOf(
            getString(R.string.proc_detalle),
            getString(R.string.proc_copiar_pid),
            getString(R.string.proc_opciones_app)
        )
        AlertDialog.Builder(this)
            .setTitle(p.nombre + " · PID " + p.pid)
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> verDetalle(p)
                    1 -> {
                        Portapapeles.copiar(this, p.pid.toString())
                        aviso(R.string.copiado)
                    }
                    else -> opcionesApp(a)
                }
            }
            .show()
    }

    private fun verDetalle(p: ProcInfo) {
        lifecycleScope.launch {
            val orden = "grep -E '^(Name|State|Threads|VmRSS|VmSwap|VmPeak|Uid):' /proc/" + p.pid + "/status; " +
                "echo oom_score_adj=$(cat /proc/" + p.pid + "/oom_score_adj); " +
                "echo cmdline=$(tr '\\0' ' ' < /proc/" + p.pid + "/cmdline)"
            val texto = try {
                ShellManager.ejecutar(orden)
            } catch (e: Exception) {
                getString(R.string.error_shell, e.message ?: e.javaClass.simpleName)
            }
            val vista = Ui.columna(this@ProcesosActivity)
            vista.addView(Ui.monoespaciado(this@ProcesosActivity, if (texto.isEmpty()) getString(R.string.proc_proceso_terminado) else texto, 12f))
            AlertDialog.Builder(this@ProcesosActivity)
                .setTitle(p.nombre + " · PID " + p.pid)
                .setView(vista)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmarDetener(a: AppProc, soloFondo: Boolean) {
        val motivo = Esenciales.motivo(this, a.paquete)
        if (motivo != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_se_puede_detener)
                .setMessage(getString(R.string.motivo_esencial, a.etiqueta, motivo))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(a.etiqueta)
            .setMessage(getString(if (soloFondo) R.string.confirmar_detener_fondo else R.string.confirmar_detener, a.etiqueta))
            .setPositiveButton(R.string.proc_detener_corto) { _, _ ->
                lifecycleScope.launch {
                    try {
                        ShellManager.ejecutar((if (soloFondo) "am kill " else "am force-stop ") + a.paquete)
                    } catch (e: Exception) {
                        aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
                    }
                    delay(800L)
                    cargar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun abrirAjustes(paquete: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + paquete)))
        } catch (e: Exception) {
            aviso(R.string.no_se_pudo_abrir)
        }
    }

    companion object {
        private const val INTERVALO_AUTO_MS = 10_000L
    }
}
