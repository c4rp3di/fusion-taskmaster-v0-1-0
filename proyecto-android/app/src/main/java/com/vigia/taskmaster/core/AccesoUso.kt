package com.vigia.taskmaster.core

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import com.vigia.taskmaster.R
import com.vigia.taskmaster.adb.ShellManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Acceso especial «uso de apps» (PACKAGE_USAGE_STATS): hace falta para el tráfico por app, el tiempo de uso y el tamaño de
 * las apps. Se puede conceder desde los ajustes de Android o, con el Modo Avanzado conectado, con un solo toque.
 * Requiere el módulo adb de la suite.
 */
object AccesoUso {

    @Suppress("DEPRECATION")
    fun concedido(c: Context): Boolean {
        return try {
            val ops = c.getSystemService(AppOpsManager::class.java)
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun abrirAjustes(a: Activity): Boolean {
        return try {
            a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun concederConShell(c: Context): Boolean {
        try {
            ShellManager.ejecutar("cmd appops set " + c.packageName + " GET_USAGE_STATS allow")
        } catch (e: Exception) {
            return false
        }
        delay(600L)
        return concedido(c)
    }

    /**
     * Tarjeta que explica el acceso y ofrece concederlo. Devuelve null si ya está concedido. alConcedido se llama cuando
     * se consigue desde el botón del Modo Avanzado.
     */
    fun tarjeta(a: ActividadBase, alConcedido: () -> Unit): View? {
        if (concedido(a)) return null
        val (tarjeta, col) = Ui.tarjetaConColumna(a)
        col.addView(Ui.titulo(a, a.getString(R.string.acceso_uso_titulo)))
        col.addView(Ui.texto(a, a.getString(R.string.acceso_uso_explica), 13f))
        col.addView(Ui.boton(a, a.getString(R.string.acceso_uso_abrir)) { abrirAjustes(a) })
        if (ShellManager.estado() == ShellManager.Estado.CONECTADO) {
            col.addView(Ui.botonSecundario(a, a.getString(R.string.acceso_uso_shell)) {
                a.lifecycleScope.launch {
                    if (concederConShell(a)) alConcedido()
                }
            })
        }
        return tarjeta
    }
}
