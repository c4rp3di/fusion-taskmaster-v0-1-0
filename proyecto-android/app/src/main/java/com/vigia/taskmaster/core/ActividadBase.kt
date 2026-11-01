package com.vigia.taskmaster.core

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.vigia.taskmaster.R

/**
 * Base de todas las pantallas de la suite: aporta el botón Volver, la ayuda de cada pantalla y los avisos cortos, para que
 * el aspecto y el comportamiento sean los mismos en todas las apps.
 */
abstract class ActividadBase : AppCompatActivity() {

    /** Conecta el botón Volver (id btnVolver) y añade el enlace «? Ayuda». Se llama justo después de setContentView. */
    protected fun prepararPantalla(tituloAyuda: Int, textoAyuda: Int) {
        findViewById<MaterialButton>(R.id.btnVolver)?.setOnClickListener { finish() }
        Ayuda.instalar(this, tituloAyuda, textoAyuda)
    }

    protected fun aviso(texto: Int) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }

    protected fun aviso(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
