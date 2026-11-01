package com.vigia.taskmaster

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.adb.ModoAvanzadoActivity
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.Esenciales
import com.vigia.taskmaster.core.Portapapeles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Gestor de procesos: lista lo que está en ejecución (con su memoria) y permite detener apps con «am force-stop». */
class ProcesosActivity : ActividadBase() {

    private class Proceso(
        val paquete: String,
        val etiqueta: String,
        val rssKb: Long,
        val pids: List<Int>,
        val esApp: Boolean
    )

    private class Acumulado(var rssKb: Long, val pids: ArrayList<Int>, val esApp: Boolean)

    private lateinit var lista: ListView
    private lateinit var etBuscar: EditText
    private lateinit var chkSistema: CheckBox
    private lateinit var resumen: TextView
    private lateinit var avisoShell: TextView
    private lateinit var btnModo: MaterialButton
    private lateinit var adaptador: ArrayAdapter<Proceso>

    private var todos: List<Proceso> = emptyList()
    private var cargando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_procesos)
        prepararPantalla(R.string.ayuda_procesos_titulo, R.string.ayuda_procesos)

        lista = findViewById(R.id.listaProcesos)
        etBuscar = findViewById(R.id.etBuscarProc)
        chkSistema = findViewById(R.id.chkProcSistema)
        resumen = findViewById(R.id.procResumen)
        avisoShell = findViewById(R.id.procAviso)
        btnModo = findViewById(R.id.btnProcModoAvanzado)

        adaptador = object : ArrayAdapter<Proceso>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val vista = super.getView(position, convertView, parent)
                val p = getItem(position)
                val t1 = vista.findViewById<TextView>(android.R.id.text1)
                val t2 = vista.findViewById<TextView>(android.R.id.text2)
                if (p != null) {
                    t1.text = p.etiqueta + if (p.esApp) "" else " · sistema"
                    val mb = String.format(Locale.US, "%.0f", p.rssKb / 1024.0)
                    t2.text = p.paquete + " · " + mb + " MB · " + p.pids.size + " proceso(s)"
                }
                return vista
            }
        }
        lista.adapter = adaptador
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val p = adaptador.getItem(posicion)
            if (p != null) mostrarOpciones(p)
        }

        findViewById<MaterialButton>(R.id.btnProcActualizar).setOnClickListener { cargar() }
        btnModo.setOnClickListener { startActivity(Intent(this, ModoAvanzadoActivity::class.java)) }
        etBuscar.doAfterTextChanged { aplicar() }
        chkSistema.setOnCheckedChangeListener { _, _ -> aplicar() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val e = ShellManager.verificar()
            val conectado = e == ShellManager.Estado.CONECTADO
            avisoShell.visibility = if (conectado) View.GONE else View.VISIBLE
            btnModo.visibility = if (conectado) View.GONE else View.VISIBLE
            if (conectado) cargar()
        }
    }

    private fun cargar() {
        if (cargando) return
        cargando = true
        resumen.setText(R.string.cargando)
        lifecycleScope.launch {
            val salida = try {
                ShellManager.ejecutar("ps -A -o PID,USER,RSS,NAME")
            } catch (e: Exception) {
                cargando = false
                resumen.text = getString(R.string.error_shell, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            val procesos = withContext(Dispatchers.Default) { parsear(salida) }
            todos = procesos
            cargando = false
            aplicar()
        }
    }

    private fun parsear(salida: String): List<Proceso> {
        val pm = packageManager
        val porPaquete = LinkedHashMap<String, Acumulado>()
        val propio = packageName
        var primera = true
        for (linea in salida.split('\n')) {
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
            val esApp = APP_USUARIO.matches(usuario)
            val acc = porPaquete.getOrPut(paquete) { Acumulado(0L, ArrayList(), esApp) }
            acc.rssKb += rss
            acc.pids.add(pid)
        }
        val res = ArrayList<Proceso>()
        for ((paquete, acc) in porPaquete) {
            var etiqueta = paquete
            if (paquete.contains('.')) {
                try {
                    etiqueta = pm.getApplicationInfo(paquete, 0).loadLabel(pm).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                } catch (e: Exception) {
                }
            }
            res.add(Proceso(paquete, etiqueta, acc.rssKb, acc.pids, acc.esApp))
        }
        res.sortByDescending { it.rssKb }
        return res
    }

    private fun aplicar() {
        val texto = etBuscar.text.toString().trim().lowercase()
        val verSistema = chkSistema.isChecked
        val filtrados = todos.filter { p ->
            (verSistema || p.esApp) &&
                (texto.isEmpty() || p.etiqueta.lowercase().contains(texto) || p.paquete.lowercase().contains(texto))
        }
        adaptador.setNotifyOnChange(false)
        adaptador.clear()
        adaptador.addAll(filtrados)
        adaptador.notifyDataSetChanged()
        val totalMb = filtrados.sumOf { it.rssKb } / 1024L
        resumen.text = getString(R.string.proc_resumen, filtrados.size, todos.size, totalMb)
    }

    private fun mostrarOpciones(p: Proceso) {
        val opciones = arrayOf(
            getString(R.string.proc_detener),
            getString(R.string.proc_ajustes),
            getString(R.string.copiar_paquete)
        )
        AlertDialog.Builder(this)
            .setTitle(p.etiqueta)
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> confirmarDetener(p)
                    1 -> abrirAjustes(p.paquete)
                    else -> {
                        Portapapeles.copiar(this, p.paquete)
                        aviso(R.string.copiado)
                    }
                }
            }
            .show()
    }

    private fun confirmarDetener(p: Proceso) {
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
        private val APP_USUARIO = Regex("^u\\d+_a\\d+$")
    }
}
