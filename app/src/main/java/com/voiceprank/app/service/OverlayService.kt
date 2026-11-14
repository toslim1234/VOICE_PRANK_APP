package com.voiceprank.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.voiceprank.app.R
import com.voiceprank.app.data.VoiceStore
import com.voiceprank.app.ui.MainActivity

/**
 * Floating overlay - the original app's `OverlayService`.
 *
 * The original runs a foreground service of type `mediaPlayback` that shows a
 * floating bubble over the game. Tapping the bubble opens a panel with the
 * voice list and the TTS controls; the panel can also inject the generated
 * audio so the voice reaches the game's voice chat.
 *
 * We reproduce that structure: a bubble you can drag around, and a panel that
 * lists the voice cards.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "overlay_channel"
        const val NOTIFICATION_ID = 4711

        fun start(context: android.content.Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    // -------------------------------------------------------------- bubble

    private fun showBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val wm = windowManager ?: return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        val button = view.findViewById<ImageButton>(R.id.bubbleButton)
        makeDraggable(view, button, params)

        bubbleView = view
        runCatching { wm.addView(view, params) }
    }

    /** Drag the bubble by its button; a touch that barely moves counts as a tap. */
    private fun makeDraggable(root: View, handle: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager?.updateViewLayout(root, params) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
    }

    // --------------------------------------------------------------- panel

    private fun togglePanel() {
        if (expanded) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panelView != null) return
        val wm = windowManager ?: return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val list = view.findViewById<LinearLayout>(R.id.voiceList)
        val empty = view.findViewById<TextView>(R.id.emptyText)
        val close = view.findViewById<ImageButton>(R.id.closeButton)
        close.setOnClickListener { hidePanel() }

        val cards = VoiceStore.all()
        if (cards.isEmpty()) {
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            cards.forEach { card ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_overlay_voice, list, false)
                row.findViewById<TextView>(R.id.nameText).text = card.name
                row.findViewById<TextView>(R.id.subtitleText).text = card.displaySubtitle
                row.setOnClickListener {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("open_voice", card.id)
                        }
                    )
                }
                list.addView(row)
            }
        }

        panelView = view
        runCatching { wm.addView(view, params) }
        expanded = true
    }

    private fun hidePanel() {
        panelView?.let { runCatching { windowManager?.removeView(it) } }
        panelView = null
        expanded = false
    }

    private fun removeViews() {
        hidePanel()
        bubbleView?.let { runCatching { windowManager?.removeView(it) } }
        bubbleView = null
    }

    // --------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
