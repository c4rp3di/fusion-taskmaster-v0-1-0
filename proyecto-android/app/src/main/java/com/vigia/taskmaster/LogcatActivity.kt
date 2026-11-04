package com.vigia.taskmaster

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.adb.ModoAvanzadoActivity
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.datos.ProcesosRepo
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.Tema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Visor de logcat: vuelca el registro del sistema con privilegios de shell, con filtros rápidos y exportación a texto. */
class LogcatActivity : ActividadBase() {

    private lateinit var lista: ListView
    private lateinit var etFiltro: EditText
    private lateinit var resumen: TextView
    private lateinit var avisoShell: TextView
    private lateinit var btnModo: MaterialButton
    private lateinit var btnApp: MaterialButton
    private lateinit var chkAuto: CheckBox
    private lateinit var chkHora: CheckBox
    private lateinit var chkTodo: CheckBox
    private lateinit var adaptador: LineasAdapter

    private var todas: List<String> = emptyList()
    private var visibles: List<String> = emptyList()
    private var modo = MODO_TODO
    private var cargando = false
    private var trabajoAuto: Job? = null
    private var appFiltro: String? = null
    private var appEtiqueta: String = ""
    private var pidsActuales: Map<String, List<Int>> = emptyMap()

    private val lanzadorExportar = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) escribir(uri)
    }

    private var textoParaExportar: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logcat)
        prepararPantalla(R.string.ayuda_logcat_titulo, R.string.ayuda_logcat)

        lista = findViewById(R.id.listaLog)
        etFiltro = findViewById(R.id.etFiltroLog)
        resumen = findViewById(R.id.logResumen)
        avisoShell = findViewById(R.id.logAviso)
        btnModo = findViewById(R.id.btnLogModoAvanzado)
        btnApp = findViewById(R.id.btnLogApp)
        chkAuto = findViewById(R.id.chkLogAuto)
        chkHora = findViewById(R.id.chkLogHora)
        chkTodo = findViewById(R.id.chkLogTodo)

        adaptador = LineasAdapter()
        lista.adapter = adaptador

        findViewById<MaterialButton>(R.id.btnLogTodo).setOnClickListener { cambiarModo(MODO_TODO) }
        findViewById<MaterialButton>(R.id.btnLogErrores).setOnClickListener { cambiarModo(MODO_ERRORES) }
        findViewById<MaterialButton>(R.id.btnLogAutofill).setOnClickListener { cambiarModo(MODO_AUTOFILL) }
        findViewById<MaterialButton>(R.id.btnLogActualizar).setOnClickListener { actualizar() }
        findViewById<MaterialButton>(R.id.btnLogExportar).setOnClickListener { pedirExportar() }
        findViewById<MaterialButton>(R.id.btnLogLimpiar).setOnClickListener { pedirLimpiar() }
        btnApp.setOnClickListener { elegirApp() }
        btnModo.setOnClickListener { startActivity(android.content.Intent(this, ModoAvanzadoActivity::class.java)) }
        etFiltro.doAfterTextChanged { aplicar(false) }
        chkAuto.setOnCheckedChangeListener { _, marcado -> if (marcado) iniciarAuto() else detenerAuto() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val e = ShellManager.verificar()
            val conectado = e == ShellManager.Estado.CONECTADO
            avisoShell.visibility = if (conectado) View.GONE else View.VISIBLE
            btnModo.visibility = if (conectado) View.GONE else View.VISIBLE
            if (conectado) actualizar()
        }
    }

    override fun onPause() {
        super.onPause()
        chkAuto.isChecked = false
        detenerAuto()
    }

    private fun cambiarModo(nuevo: Int) {
        modo = nuevo
        aplicar(true)
    }

    private fun iniciarAuto() {
        detenerAuto()
        trabajoAuto = lifecycleScope.launch {
            while (isActive) {
                actualizar()
                delay(3000L)
            }
        }
    }

    private fun detenerAuto() {
        trabajoAuto?.cancel()
        trabajoAuto = null
    }

    private fun actualizar() {
        if (cargando) return
        if (ShellManager.estado() == ShellManager.Estado.NO_DISPONIBLE) {
            avisoShell.visibility = View.VISIBLE
            return
        }
        cargando = true
        resumen.setText(R.string.cargando)
        lifecycleScope.launch {
            val formato = if (chkHora.isChecked) "time" else "brief"
            val limite = if (chkTodo.isChecked) "" else " -t 2000"
            val salida = try {
                ShellManager.ejecutar("logcat -d -v " + formato + limite)
            } catch (e: Exception) {
                cargando = false
                resumen.text = getString(R.string.error_shell, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            val lineas = withContext(Dispatchers.Default) {
                salida.split('\n').filter { it.isNotBlank() && !it.startsWith("--------- beginning") }
            }
            todas = lineas
            cargando = false
            aplicar(true)
        }
    }

    /** Líneas que pertenecen a la app elegida: las de sus procesos (por PID) o las que nombran su paquete. */
    private fun pasaApp(linea: String, paquete: String, pids: Set<Int>): Boolean {
        if (linea.contains(paquete)) return true
        val m = PID.find(linea) ?: return false
        val pid = m.groupValues[1].toIntOrNull() ?: return false
        return pids.contains(pid)
    }

    /** PIDs de la app: los procesos que corren ahora y los que se ven arrancar en el propio registro («Start proc 1234:paquete/»). */
    private fun pidsDeApp(paquete: String): Set<Int> {
        val pids = HashSet<Int>()
        pidsActuales[paquete]?.let { pids.addAll(it) }
        val inicio = Regex("Start proc (\\d+):" + Regex.escape(paquete) + "[:/]")
        for (l in todas) {
            if (!l.contains("Start proc")) continue
            val m = inicio.find(l) ?: continue
            m.groupValues[1].toIntOrNull()?.let { pids.add(it) }
        }
        return pids
    }

    private fun elegirApp() {
        lifecycleScope.launch {
            val mapa = try {
                ProcesosRepo.pidsPorPaquete(this@LogcatActivity)
            } catch (e: Exception) {
                aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            pidsActuales = mapa
            val pm = packageManager
            val candidatas = ArrayList<Pair<String, String>>()
            for (paquete in mapa.keys) {
                if (!paquete.contains('.')) continue
                try {
                    val info = pm.getApplicationInfo(paquete, 0)
                    candidatas.add(Pair(paquete, info.loadLabel(pm).toString()))
                } catch (e: Exception) {
                }
            }
            candidatas.sortBy { it.second.lowercase() }
            val nombres = ArrayList<String>()
            nombres.add(getString(R.string.log_sin_filtro_app))
            for (c in candidatas) nombres.add(c.second + "\n" + c.first)
            AlertDialog.Builder(this@LogcatActivity)
                .setTitle(R.string.log_por_app)
                .setItems(nombres.toTypedArray()) { _, cual ->
                    if (cual == 0) {
                        appFiltro = null
                        appEtiqueta = ""
                    } else {
                        appFiltro = candidatas[cual - 1].first
                        appEtiqueta = candidatas[cual - 1].second
                    }
                    btnApp.text = if (appFiltro == null) getString(R.string.log_por_app) else getString(R.string.log_app_activa, appEtiqueta)
                    aplicar(true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun pasa(linea: String, filtro: String): Boolean {
        if (modo == MODO_ERRORES) {
            val n = nivel(linea)
            if (n != 'E' && n != 'F') return false
        }
        if (modo == MODO_AUTOFILL && !linea.contains("Autofill", ignoreCase = true)) return false
        if (filtro.isNotEmpty() && !linea.contains(filtro, ignoreCase = true)) return false
        return true
    }

    private fun aplicar(alFinal: Boolean) {
        val filtro = etFiltro.text.toString().trim()
        val app = appFiltro
        val pidsApp: Set<Int> = if (app == null) emptySet() else pidsDeApp(app)
        val resultado = todas.filter { (app == null || pasaApp(it, app, pidsApp)) && pasa(it, filtro) }.takeLast(MAX_VISIBLES)
        visibles = resultado
        adaptador.setNotifyOnChange(false)
        adaptador.clear()
        adaptador.addAll(resultado)
        adaptador.notifyDataSetChanged()
        val etiqueta = when (modo) {
            MODO_ERRORES -> getString(R.string.filtro_errores)
            MODO_AUTOFILL -> getString(R.string.filtro_autofill)
            else -> getString(R.string.filtro_todo)
        } + (if (app != null) " · " + appEtiqueta else "")
        resumen.text = getString(R.string.log_resumen, resultado.size, todas.size, etiqueta)
        if (alFinal && resultado.isNotEmpty()) lista.setSelection(resultado.size - 1)
    }

    private fun pedirExportar() {
        if (visibles.isEmpty()) {
            aviso(R.string.log_nada_que_exportar)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.log_exportar)
            .setMessage(getString(R.string.aviso_exportar_log, visibles.size))
            .setPositiveButton(R.string.exportar) { _, _ ->
                textoParaExportar = visibles.joinToString("\n").toByteArray(Charsets.UTF_8)
                val marca = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                lanzadorExportar.launch("logcat-" + marca + ".txt")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun escribir(uri: android.net.Uri) {
        val bytes = textoParaExportar ?: return
        textoParaExportar = null
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            aviso(if (ok) R.string.guardado else R.string.no_guardado)
        }
    }

    private fun pedirLimpiar() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_limpiar)
            .setMessage(R.string.confirmar_limpiar_log)
            .setPositiveButton(R.string.limpiar) { _, _ ->
                lifecycleScope.launch {
                    try {
                        ShellManager.ejecutar("logcat -c")
                        todas = emptyList()
                        aplicar(false)
                    } catch (e: Exception) {
                        aviso(getString(R.string.error_shell, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun nivel(linea: String): Char {
        val m = NIVEL.find(linea) ?: return ' '
        return m.groupValues[1][0]
    }

    private inner class LineasAdapter : ArrayAdapter<String>(this@LogcatActivity, android.R.layout.simple_list_item_1) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent) as TextView
            val l = getItem(position) ?: ""
            v.text = l
            v.typeface = Typeface.MONOSPACE
            v.textSize = 11f
            v.minHeight = 0
            v.setPadding(8, 4, 8, 4)
            val n = nivel(l)
            v.setTextColor(
                when (n) {
                    'E', 'F' -> Tema.rojo(context)
                    'W' -> Tema.ambar(context)
                    else -> Tema.texto(context)
                }
            )
            return v
        }
    }

    companion object {
        private const val MODO_TODO = 0
        private const val MODO_ERRORES = 1
        private const val MODO_AUTOFILL = 2
        private const val MAX_VISIBLES = 3000
        private val NIVEL = Regex("(?:^|\\s)([VDIWEF])/")
        private val PID = Regex("\\(\\s*(\\d+)\\)")
    }
}
