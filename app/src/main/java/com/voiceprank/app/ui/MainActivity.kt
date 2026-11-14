package com.voiceprank.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.voiceprank.app.R
import com.voiceprank.app.core.Prefs
import com.voiceprank.app.core.Premium
import com.voiceprank.app.data.VoiceCard
import com.voiceprank.app.data.VoiceStore
import com.voiceprank.app.databinding.ActivityMainBinding
import com.voiceprank.app.databinding.ItemVoiceCardBinding
import com.google.android.material.textfield.TextInputEditText

/**
 * Voice library - the original app's `MainActivity`.
 *
 * It shows the list of voice cards, a search field, a "add voice" action and
 * a settings/premium entry point. Tapping a card opens the TTS panel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = CardAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                refresh()
            }
        })

        b.fabAdd.setOnClickListener {
            startActivity(Intent(this, VoiceEditActivity::class.java))
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnPremium.setOnClickListener {
            startActivity(Intent(this, PremiumActivity::class.java))
        }

        adapter.onClick = { card -> openTts(card) }
        adapter.onLongClick = { card -> confirmDelete(card) }
    }

    override fun onResume() {
        super.onResume()
        VoiceStore.reload()
        refresh()
        b.badgePremium.text = if (Premium.isPremium(this)) "PRO" else "FREE"
    }

    private fun refresh() {
        val list = VoiceStore.search(b.searchInput.text?.toString().orEmpty())
        adapter.submit(list)
        b.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.countText.text = "${list.size} voice${if (list.size == 1) "" else "s"}"
    }

    private fun openTts(card: VoiceCard) {
        startActivity(
            Intent(this, VoiceEditActivity::class.java).apply {
                putExtra(VoiceEditActivity.EXTRA_CARD_ID, card.id)
            }
        )
    }

    private fun confirmDelete(card: VoiceCard) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(card.name)
            .setMessage("Delete this voice card?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                VoiceStore.remove(card.id)
                refresh()
            }
            .show()
    }

    // ------------------------------------------------------------- adapter

    inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {

        var onClick: ((VoiceCard) -> Unit)? = null
        var onLongClick: ((VoiceCard) -> Unit)? = null
        private val items = mutableListOf<VoiceCard>()

        fun submit(list: List<VoiceCard>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val binding: ItemVoiceCardBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemVoiceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val card = items[position]
            holder.binding.nameText.text = card.name
            holder.binding.subtitleText.text = card.displaySubtitle
            holder.binding.root.setOnClickListener { onClick?.invoke(card) }
            holder.binding.root.setOnLongClickListener {
                onLongClick?.invoke(card)
                true
            }
        }
    }
}
