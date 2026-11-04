package com.vigia.taskmaster.datos

import android.content.Context
import com.vigia.taskmaster.R

object Uids {
    /** Nombre legible de un UID: la app (o «app +N» si comparte UID) o una etiqueta para los UID especiales del sistema. */
    fun nombre(c: Context, uid: Int): String {
        when (uid) {
            0 -> return c.getString(R.string.uid_root)
            1000 -> return c.getString(R.string.uid_sistema)
            -4 -> return c.getString(R.string.uid_eliminadas)
            -5 -> return c.getString(R.string.uid_anclaje)
        }
        try {
            val pm = c.packageManager
            val paquetes = pm.getPackagesForUid(uid)
            if (paquetes != null && paquetes.isNotEmpty()) {
                val etiqueta = pm.getApplicationInfo(paquetes[0], 0).loadLabel(pm).toString()
                return if (paquetes.size > 1) etiqueta + " +" + (paquetes.size - 1) else etiqueta
            }
        } catch (e: Exception) {
        }
        return "UID " + uid
    }
}
