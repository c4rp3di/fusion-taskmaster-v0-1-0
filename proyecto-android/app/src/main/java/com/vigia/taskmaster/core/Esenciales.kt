package com.vigia.taskmaster.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager

/** Apps que no se deben detener ni quitar: componentes del sistema y las que el usuario necesita ahora mismo. */
object Esenciales {
    private val FIJAS = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.server.telecom",
        "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.google.android.gms",
        "com.google.android.gsf", "com.android.vending", "com.android.nfc", "com.android.bluetooth",
        "com.android.keychain", "com.android.location.fused", "com.android.networkstack", "com.android.networkstack.inprocess",
        "com.android.captiveportallogin", "com.android.cellbroadcastreceiver", "com.android.emergency",
        "com.google.android.webview", "com.android.webview", "com.android.certinstaller", "com.android.se",
        "com.android.stk", "com.android.mms.service", "com.google.android.networkstack"
    )

    /** Devuelve por qué no se debe tocar, o null si se puede. */
    fun motivo(contexto: Context, paquete: String): String? {
        if (paquete == contexto.packageName) return "es esta misma app"
        if (FIJAS.contains(paquete)) return "es un componente esencial del sistema"
        if (paquete.startsWith("com.android.providers.") || paquete.startsWith("com.android.internal.")) {
            return "es un proveedor de datos del sistema que otras apps necesitan"
        }
        try {
            val inicio = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val lanzador = contexto.packageManager.resolveActivity(inicio, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (lanzador == paquete) return "es tu pantalla de inicio actual"
        } catch (e: Exception) {
        }
        try {
            val teclado = Settings.Secure.getString(contexto.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            if (teclado == paquete) return "es tu teclado actual"
        } catch (e: Exception) {
        }
        try {
            val tm = contexto.getSystemService(TelecomManager::class.java)
            if (tm.defaultDialerPackage == paquete) return "es tu app de teléfono"
        } catch (e: Exception) {
        }
        try {
            val sms = android.provider.Telephony.Sms.getDefaultSmsPackage(contexto)
            if (sms == paquete) return "es tu app de mensajes"
        } catch (e: Exception) {
        }
        return null
    }
}
