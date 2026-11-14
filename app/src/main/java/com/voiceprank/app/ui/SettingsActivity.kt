package com.voiceprank.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.voiceprank.app.core.Prefs
import com.voiceprank.app.databinding.ActivitySettingsBinding
import com.voiceprank.app.inject.RootTools
import com.voiceprank.app.inject.ShizukuBridge

/**
 * Settings - own-API mode, backend, Shizuku setup, permissions.
 *
 * The original app has an "own API mode" toggle, an API key field, a model
 * field, an API version field and a Shizuku setup guide. We reproduce that.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_OWN_API = "open_own_api"
    }

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ownApiSwitch.isChecked = Prefs.ownApiEnabled
        b.cartesiaKeyInput.setText(Prefs.cartesiaKey)
        b.modelInput.setText(Prefs.cartesiaModel)
        b.apiVersionInput.setText(Prefs.cartesiaApiVersion)
        b.serverInput.setText(Prefs.serverBase)

        b.btnSave.setOnClickListener { save() }
        b.btnShizuku.setOnClickListener { requestShizuku() }
        b.btnOpenSettings.setOnClickListener { openAppSettings() }
        b.btnOverlay.setOnClickListener { openOverlaySettings() }

        updateStatus()

        if (intent.getBooleanExtra(EXTRA_OPEN_OWN_API, false)) {
            b.ownApiSwitch.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun save() {
        Prefs.ownApiEnabled = b.ownApiSwitch.isChecked
        Prefs.cartesiaKey = b.cartesiaKeyInput.text?.toString()?.trim().orEmpty()
        Prefs.cartesiaModel = b.modelInput.text?.toString()?.trim().orEmpty()
        Prefs.cartesiaApiVersion = b.apiVersionInput.text?.toString()?.trim().orEmpty()
        Prefs.serverBase = b.serverInput.text?.toString()?.trim().orEmpty()
        b.statusText.text = "Saved"
    }

    private fun updateStatus() {
        val root = RootTools.hasRoot()
        val shizuku = ShizukuBridge.statusText()
        b.statusText.text = buildString {
            append("Root: ")
            append(if (root) "available ✅" else "not found")
            append("\n")
            append("Shizuku: ")
            append(shizuku)
            append("\n")
            append("Own API: ")
            append(if (Prefs.ownApiEnabled && Prefs.cartesiaKey.isNotBlank()) "on ✅" else "off")
        }
    }

    private fun requestShizuku() {
        ShizukuBridge.requestPermission { granted ->
            b.statusText.text = if (granted) {
                "Shizuku permission granted ✅"
            } else {
                "Shizuku permission denied"
            }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
        }
    }
}
