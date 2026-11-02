package com.vigia.taskmaster

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.adb.ModoAvanzadoActivity
import com.vigia.taskmaster.adb.ShellManager
import com.vigia.taskmaster.core.ActividadBase
import com.vigia.taskmaster.core.Ayuda
import kotlinx.coroutines.launch

class MainActivity : ActividadBase() {

    private lateinit var estado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estado = findViewById(R.id.estadoShell)

        findViewById<MaterialButton>(R.id.btnLogcat).setOnClickListener { abrir(LogcatActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnProcesos).setOnClickListener { abrir(ProcesosActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnConsola).setOnClickListener { abrir(ConsolaActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnRecursos).setOnClickListener { abrir(RecursosActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnModoAvanzado).setOnClickListener { abrir(ModoAvanzadoActivity::class.java) }

        val cabecera = findViewById<LinearLayout>(R.id.filaCabecera)
        cabecera.addView(Ayuda.enlace(this, "?  Ayuda") { Ayuda.mostrar(this, R.string.ayuda_principal_titulo, R.string.ayuda_principal) })
        pintarEstado(ShellManager.estado())
    }

    override fun onResume() {
        super.onResume()
        pintarEstado(ShellManager.estado())
        lifecycleScope.launch {
            pintarEstado(ShellManager.verificar())
        }
    }

    private fun abrir(clase: Class<*>) {
        startActivity(Intent(this, clase))
    }

    private fun pintarEstado(e: ShellManager.Estado) {
        estado.setText(
            when (e) {
                ShellManager.Estado.CONECTADO -> R.string.estado_conectado
                ShellManager.Estado.DESCONECTADO -> R.string.estado_desconectado
                ShellManager.Estado.NO_DISPONIBLE -> R.string.estado_no_disponible
            }
        )
    }
}
