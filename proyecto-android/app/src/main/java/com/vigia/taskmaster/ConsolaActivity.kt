package com.vigia.taskmaster

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.adb.ModoAvanzadoActivity
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.Portapapeles
import kotlinx.coroutines.launch

/** Consola de shell: envía órdenes al shell de este teléfono (UID 2000) y muestra lo que imprimen. */
class ConsolaActivity : ActividadBase() {

    private lateinit var salida: TextView
    private lateinit var desplazable: ScrollView
    private lateinit var etOrden: EditText
    private lateinit var avisoShell: TextView
    private lateinit var btnModo: MaterialButton
    private val historial = ArrayList<String>()
    private var ocupada = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consola)
        prepararPantalla(R.string.ayuda_consola_titulo, R.string.ayuda_consola)

        salida = findViewById(R.id.consolaSalida)
        desplazable = findViewById(R.id.consolaScroll)
        etOrden = findViewById(R.id.etOrden)
        avisoShell = findViewById(R.id.consolaAviso)
        btnModo = findViewById(R.id.btnConsolaModoAvanzado)
        salida.typeface = Typeface.MONOSPACE

        findViewById<MaterialButton>(R.id.btnEjecutar).setOnClickListener { ejecutar() }
        findViewById<MaterialButton>(R.id.btnHistorial).setOnClickListener { mostrarHistorial() }
        findViewById<MaterialButton>(R.id.btnCopiarSalida).setOnClickListener {
            Portapapeles.copiar(this, salida.text.toString())
            aviso(R.string.copiado)
        }
        findViewById<MaterialButton>(R.id.btnLimpiarSalida).setOnClickListener { salida.text = "" }
        btnModo.setOnClickListener { startActivity(Intent(this, ModoAvanzadoActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val e = ShellManager.verificar()
            val conectado = e == ShellManager.Estado.CONECTADO
            avisoShell.visibility = if (conectado) View.GONE else View.VISIBLE
            btnModo.visibility = if (conectado) View.GONE else View.VISIBLE
        }
    }

    private fun ejecutar() {
        val orden = etOrden.text.toString().trim()
        if (orden.isEmpty() || ocupada) return
        if (esPeligrosa(orden)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.consola_peligrosa_titulo)
                .setMessage(getString(R.string.consola_peligrosa, orden))
                .setPositiveButton(R.string.ejecutar) { _, _ -> lanzar(orden) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            lanzar(orden)
        }
    }

    private fun lanzar(orden: String) {
        ocupada = true
        historial.remove(orden)
        historial.add(orden)
        if (historial.size > 30) historial.removeAt(0)
        agregar("$ " + orden + "\n")
        etOrden.setText("")
        lifecycleScope.launch {
            val resultado = try {
                ShellManager.ejecutar(orden)
            } catch (e: Exception) {
                "error: " + (e.message ?: e.javaClass.simpleName)
            }
            agregar((if (resultado.isEmpty()) getString(R.string.consola_sin_salida) else resultado) + "\n\n")
            ocupada = false
        }
    }

    private fun agregar(texto: String) {
        var actual = salida.text.toString() + texto
        if (actual.length > MAX_CARACTERES) actual = actual.substring(actual.length - MAX_CARACTERES)
        salida.text = actual
        desplazable.post { desplazable.fullScroll(View.FOCUS_DOWN) }
    }

    private fun mostrarHistorial() {
        if (historial.isEmpty()) {
            aviso(R.string.consola_sin_historial)
            return
        }
        val orden = historial.reversed().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.consola_historial)
            .setItems(orden) { _, cual ->
                etOrden.setText(orden[cual])
                etOrden.setSelection(etOrden.text.length)
            }
            .show()
    }

    /** Órdenes que pueden borrar datos o cambiar el sistema: se pide confirmación antes de ejecutarlas. */
    private fun esPeligrosa(orden: String): Boolean {
        val o = " " + orden.lowercase() + " "
        if (PALABRAS_PELIGROSAS.containsMatchIn(o)) return true
        for (frase in FRASES_PELIGROSAS) {
            if (o.contains(frase)) return true
        }
        return false
    }

    companion object {
        private const val MAX_CARACTERES = 200_000
        private val PALABRAS_PELIGROSAS = Regex("[\\s;&|]((rm|reboot|shutdown|wipe|mkfs|dd|setprop|su|chmod|chown|mount|umount)\\s)")
        private val FRASES_PELIGROSAS = listOf("pm clear", "pm uninstall", "pm disable", "pm hide", "settings put", "cmd package", "svc ")
    }
}
