package com.lightagent.tts

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 智能音频路由管理器 v4.4.3
 *
 * 职责：播放前强制校正路由 + 设备热插拔自动切换
 * ├── 无外设 → 扬声器
 * ├── 有线耳机 → 走系统自动
 * └── 蓝牙 A2DP → 启动 BluetoothSco
 */
class AudioRouteManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AudioRouteManager"

        @Volatile
        private var instance: AudioRouteManager? = null

        fun getInstance(context: Context): AudioRouteManager =
            instance ?: synchronized(this) {
                instance ?: AudioRouteManager(context.applicationContext).also { instance = it }
            }
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val broadcastReceiver = DeviceChangeReceiver()

    private var isRegistered = false

    // ── 初始化 ──────────────────────────────────────────────────────────
    fun init() {
        audioManager.mode = AudioManager.MODE_NORMAL
        updateRoute()
        registerReceiver()
        Log.d(TAG, "AudioRouteManager 初始化完成")
    }

    // ── 播放前强制修复 ──────────────────────────────────────────────────
    fun forceBeforePlay() {
        audioManager.mode = AudioManager.MODE_NORMAL
        updateRoute()

        KokoroTTS.writeDebug(
            "路由强制: speaker=${audioManager.isSpeakerphoneOn}, " +
            "mode=${audioManager.mode}, " +
            "BT SCO=${audioManager.isBluetoothScoOn}"
        )
    }

    // ── 核心路由选择逻辑 ───────────────────────────────────────────────
    fun updateRoute() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // API < 23: 只控制扬声器
            audioManager.isSpeakerphoneOn = true
            return
        }

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btA2dp = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val bleHeadset = devices.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        val btSco = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val wired = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        val hasBluetooth = btA2dp || bleHeadset || btSco
        val hasWired = wired

        audioManager.mode = AudioManager.MODE_NORMAL

        when {
            hasBluetooth -> {
                audioManager.isBluetoothScoOn = true
                try { audioManager.startBluetoothSco() } catch (_: Exception) {}
                audioManager.isSpeakerphoneOn = false
                Log.d(TAG, "路由 → 蓝牙 A2DP/SCO")
                KokoroTTS.writeDebug("路由 → 蓝牙")
            }

            hasWired -> {
                audioManager.isBluetoothScoOn = false
                try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                audioManager.isSpeakerphoneOn = false
                Log.d(TAG, "路由 → 有线耳机")
                KokoroTTS.writeDebug("路由 → 有线")
            }

            else -> {
                audioManager.isBluetoothScoOn = false
                try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                audioManager.isSpeakerphoneOn = true
                Log.d(TAG, "路由 → 扬声器")
                KokoroTTS.writeDebug("路由 → 扬声器")
            }
        }
    }

    // ── 设备变化监听 ───────────────────────────────────────────────────
    private fun registerReceiver() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)         // 耳机拔出
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) // 蓝牙连接/断开
            addAction(AudioManager.ACTION_HEADSET_PLUG)                 // 有线插拔
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addAction(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
            }
        }
        context.registerReceiver(broadcastReceiver, filter)
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        try { context.unregisterReceiver(broadcastReceiver) } catch (_: Exception) {}
        isRegistered = false
    }

    // ── 路由状态快照（日志用）─────────────────────────────────────────
    fun snapshot(): String {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString(" | ") { d ->
                    "${d.productName ?: "?"}(${deviceTypeName(d.type)})"
                }
        } else "N/A"
        return "speaker=${audioManager.isSpeakerphoneOn} mode=${audioManager.mode} btSCO=${audioManager.isBluetoothScoOn} devices=[$devices]"
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "头戴"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        else -> "t$type"
    }

    // ── BroadcastReceiver ──────────────────────────────────────────────
    private inner class DeviceChangeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "设备变化: ${intent.action}")
            KokoroTTS.writeDebug("设备变化: ${intent.action}")
            updateRoute()
        }
    }
}
