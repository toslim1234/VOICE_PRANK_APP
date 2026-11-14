package com.voiceprank.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.voiceprank.app.R
import com.voiceprank.app.core.Gate
import com.voiceprank.app.core.Identity
import com.voiceprank.app.core.Prefs
import com.voiceprank.app.core.Premium
import com.voiceprank.app.databinding.ActivityLoginBinding
import com.voiceprank.app.net.ApiClient
import kotlinx.coroutines.launch

/**
 * Gate screen - the original app's `LoginActivity`.
 *
 * It is the launcher activity and the place where the entitlement is decided.
 * The original shows: a key field, a "redeem coupon" field, a Telegram link,
 * and it routes to `MainActivity` when the gate returns `OK` / `OFFLINE_OK`.
 *
 * We support three ways in:
 *  - own Cartesia API key (works with no backend at all)
 *  - a license key (validated locally, or against a backend if configured)
 *  - the free trial
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.keyInput.setText(Prefs.licenseKey)

        b.btnEnter.setOnClickListener { attemptEnter() }
        b.btnOwnApi.setOnClickListener { openOwnApi() }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnPremium.setOnClickListener {
            startActivity(Intent(this, PremiumActivity::class.java))
        }
        b.linkTelegram.setOnClickListener {
            openLink("https://t.me/voiceprankpro")
        }
        b.linkHelp.setOnClickListener {
            openLink("https://t.me/voiceprankpro")
        }

        // A previously confirmed entitlement goes straight in.
        if (Prefs.isEnabled && Gate.checkLocal(this).isUsable) {
            goMain()
        }
    }

    override fun onResume() {
        super.onResume()
        val gate = Gate.checkLocal(this)
        if (gate.isUsable && Prefs.isEnabled) {
            goMain()
            return
        }
        b.statusText.text = when (gate.r) {
            Gate.R.EXPIRED -> gate.msg.ifBlank { "Your access has expired." }
            Gate.R.LOCKED -> gate.msg.ifBlank { "This device is locked." }
            Gate.R.MAINT -> gate.msg.ifBlank { "The service is under maintenance." }
            Gate.R.UPDATE -> gate.msg.ifBlank { "Please update the app." }
            Gate.R.NEED_LOGIN -> gate.msg.ifBlank {
                "Enter your key, add your own Cartesia API key, or try the trial."
            }
            else -> "Enter your key to continue."
        }
    }

    private fun attemptEnter() {
        val key = b.keyInput.text.toString().trim()
        if (key.isBlank()) {
            b.statusText.text = "Enter a license key, or add your own Cartesia API key."
            return
        }
        b.btnEnter.isEnabled = false
        b.statusText.text = "Checking..."

        lifecycleScope.launch {
            val deviceId = Identity.deviceId(this@LoginActivity)
            // 1. try local validation first (offline-issued keys)
            val local = Premium.validateLocal(key, deviceId)
            if (local.r == Gate.R.OK) {
                Premium.parseLicenseKey(key)?.let { Prefs.premiumUntil = it.premiumUntil }
                accept(key)
                return@launch
            }

            // 2. fall back to the backend when one is configured
            val base = Prefs.serverBase
            if (base.isNotBlank()) {
                val resp = ApiClient(this@LoginActivity).verify(key)
                val json = resp.json()
                if (resp.code in 200..299 && json != null) {
                    val out = Gate.classify(Gate.toMap(json))
                    if (out.r == Gate.R.OK) {
                        accept(key)
                        return@launch
                    }
                    b.statusText.text = out.msg.ifBlank { "Access denied (${out.r})." }
                } else {
                    b.statusText.text = "Could not reach the server. Check your connection."
                }
            } else {
                b.statusText.text = local.msg.ifBlank { "Invalid key." }
            }
            b.btnEnter.isEnabled = true
        }
    }

    private fun accept(key: String) {
        Prefs.licenseKey = key
        Prefs.setEnabled(this, true)
        goMain()
    }

    private fun openOwnApi() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_OPEN_OWN_API, true)
        })
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (t: Throwable) {
        }
    }
}
