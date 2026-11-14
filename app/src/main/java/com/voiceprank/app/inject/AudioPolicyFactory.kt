package com.voiceprank.app.inject

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Reflection bridge to the @SystemApi `android.media.audiopolicy` classes.
 *
 * Injection into the microphone path. A capture-preset rule + ROUTE_FLAG_LOOP_BACK mix has
 * existed since Android 5; Android 13+ additionally wants the explicit INJECTOR role.
 * (Android 8-12 -> no setTargetMixRole call; Android 13+ -> role INJECTOR):
 *
 *   rule  = AudioMixingRule.Builder()
 *             .setTargetMixRole(MIX_ROLE_INJECTOR)
 *             .addMixRule(RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET, attrs(preset))   // per preset
 *             .build()
 *   mix   = AudioMix.Builder(rule).setFormat(fmt).setRouteFlags(ROUTE_FLAG_LOOP_BACK).build()
 *   pol   = AudioPolicy.Builder(ctx).addMix(mix).build()
 *   AudioManager.registerAudioPolicy(pol)            // needs MODIFY_AUDIO_ROUTING (shell/root)
 *   track = pol.createAudioTrackSource(mix)
 *
 * Whatever is written to `track` is delivered to apps recording from one of the presets.
 */
object AudioPolicyFactory {

    private const val TAG = "VoicePrankAudioPolicy"

    const val MIX_ROLE_INJECTOR = 1
    const val RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 1 shl 1
    const val ROUTE_FLAG_RENDER = 1
    const val ROUTE_FLAG_LOOP_BACK = 1 shl 1

    /** MediaRecorder.AudioSource: MIC=1, CAMCORDER=5, VOICE_RECOGNITION=6, VOICE_COMMUNICATION=7 */
    val CAPTURE_PRESETS = intArrayOf(1, 5, 6, 7)

    class Handle(
        val audioManager: Any,
        val policy: Any,
        val track: AudioTrack
    )

    fun create(context: Context, sampleRate: Int): Handle? {
        // Channel mask of the injected stream: try OUT_MONO first, IN_MONO as fallback.
        for (mask in intArrayOf(AudioFormat.CHANNEL_OUT_MONO, AudioFormat.CHANNEL_IN_MONO)) {
            try {
                return build(context, sampleRate, mask)
            } catch (t: Throwable) {
                Log.e(TAG, "create failed (mask=$mask)", unwrap(t))
                lastError = unwrap(t).let { "${it.javaClass.simpleName}: ${it.message}" }
            }
        }
        return null
    }

    @Volatile
    var lastError: String = ""
        private set

    private fun build(context: Context, sampleRate: Int, channelMask: Int): Handle {
        val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")

        // ---- rule
        val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ruleBuilderCls.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                .invoke(ruleBuilder, MIX_ROLE_INJECTOR)
        }
        val addMixRule = ruleBuilderCls.getMethod(
            "addMixRule", Int::class.javaPrimitiveType, Any::class.java
        )
        val setPreset = AudioAttributes.Builder::class.java
            .getMethod("setCapturePreset", Int::class.javaPrimitiveType)
        for (preset in CAPTURE_PRESETS) {
            val attrs = AudioAttributes.Builder().also { setPreset.invoke(it, preset) }.build()
            addMixRule.invoke(ruleBuilder, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET, attrs)
        }
        val rule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)

        // ---- mix
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val mixBuilder = mixBuilderCls.getConstructor(ruleCls).newInstance(rule)
        mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
        val mix = mixBuilderCls.getMethod("build").invoke(mixBuilder)

        // ---- policy
        val policyBuilder = policyBuilderCls.getConstructor(Context::class.java).newInstance(context)
        policyBuilderCls.getMethod("addMix", mixCls).invoke(policyBuilder, mix)
        val policy = policyBuilderCls.getMethod("build").invoke(policyBuilder)

        val am = context.getSystemService(Context.AUDIO_SERVICE)
            ?: throw IllegalStateException("no AudioManager")
        val code = am.javaClass.getMethod("registerAudioPolicy", policyCls).invoke(am, policy) as Int
        if (code != 0) throw IllegalStateException("registerAudioPolicy returned $code")

        val track = policyCls.getMethod("createAudioTrackSource", mixCls).invoke(policy, mix) as AudioTrack
        return Handle(am, policy!!, track)
    }

    fun release(handle: Handle?) {
        if (handle == null) return
        try {
            handle.track.release()
        } catch (_: Throwable) {
        }
        try {
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            handle.audioManager.javaClass.getMethod("unregisterAudioPolicy", policyCls)
                .invoke(handle.audioManager, handle.policy)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister failed", unwrap(t))
        }
    }

    private fun unwrap(t: Throwable): Throwable =
        (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
}
