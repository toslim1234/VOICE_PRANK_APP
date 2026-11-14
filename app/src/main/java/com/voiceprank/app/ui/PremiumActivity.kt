package com.voiceprank.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.voiceprank.app.core.Identity
import com.voiceprank.app.core.Prefs
import com.voiceprank.app.core.Premium
import com.voiceprank.app.databinding.ActivityPremiumBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Premium screen.
 *
 * The original app sells access through coupon codes redeemed against its
 * backend, and it also unlocks "own API" mode for users who have a coupon.
 *
 * We offer the same three ways to become premium:
 *  - redeem a coupon (needs a backend)
 *  - enter an offline license key
 *  - add your own Cartesia API key (instant, no server)
 */
class PremiumActivity : AppCompatActivity() {

    private lateinit var b: ActivityPremiumBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPremiumBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnOwnApi.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_OPEN_OWN_API, true)
            })
        }
        b.btnRedeem.setOnClickListener { redeem() }
        b.btnLicense.setOnClickListener { enterLicense() }

        updateState()
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    private fun updateState() {
        val premium = Premium.isPremium(this)
        b.stateText.text = if (premium) {
            "Premium active ✅\nUnlimited voices, bundles and TTS."
        } else {
            "Free tier\n${Premium.FREE_MAX_CARDS} voice cards, ${Premium.FREE_DAILY_CHARS} characters per day."
        }
        b.ownApiState.text = if (Prefs.ownApiEnabled && Prefs.cartesiaKey.isNotBlank()) {
            "Own API mode is on ✅"
        } else {
            "Own API mode is off"
        }
    }

    private fun redeem() {
        val coupon = b.couponInput.text?.toString()?.trim().orEmpty()
        if (coupon.isBlank()) {
            b.stateText.text = "Enter a coupon code"
            return
        }
        val base = Prefs.serverBase
        if (base.isBlank()) {
            b.stateText.text = "No backend configured - add your own Cartesia API key instead."
            return
        }
        b.btnRedeem.isEnabled = false
        b.stateText.text = "Redeeming..."
        lifecycleScope.launch {
            try {
                val resp = com.voiceprank.app.net.ApiClient(this@PremiumActivity)
                    .redeem(coupon, Prefs.licenseKey)
                val json = resp.json()
                if (resp.code in 200..299 && json != null) {
                    val out = com.voiceprank.app.core.Gate.classify(com.voiceprank.app.core.Gate.toMap(json))
                    if (out.r == com.voiceprank.app.core.Gate.R.OK) {
                        json.optLong("premium_until", 0L).takeIf { it > 0 }?.let {
                            Prefs.premiumUntil = it
                        }
                        Prefs.setEnabled(this@PremiumActivity, true)
                        b.stateText.text = "Redeemed ✅"
                    } else {
                        b.stateText.text = out.msg.ifBlank { "Could not redeem that code." }
                    }
                } else {
                    b.stateText.text = "Could not redeem that code."
                }
            } catch (t: Throwable) {
                b.stateText.text = t.message ?: "Network error"
            } finally {
                b.btnRedeem.isEnabled = true
            }
        }
    }

    private fun enterLicense() {
        val key = b.licenseInput.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) {
            b.stateText.text = "Enter a license key"
            return
        }
        val res = Premium.validateLocal(key, Identity.deviceId(this))
        if (res.r == com.voiceprank.app.core.Gate.R.OK) {
            Prefs.licenseKey = key
            Prefs.setEnabled(this, true)
            val info = Premium.parseLicenseKey(key)
            info?.let { Prefs.premiumUntil = it.premiumUntil }
            b.stateText.text = "License accepted ✅"
            updateState()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("License rejected")
                .setMessage(res.msg.ifBlank { "Invalid key" })
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
