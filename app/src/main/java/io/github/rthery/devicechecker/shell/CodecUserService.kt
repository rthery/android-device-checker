package io.github.rthery.devicechecker.shell

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in a Shizuku-spawned process: its shell or root uid holds BLUETOOTH_PRIVILEGED, which the
 * A2DP codec system APIs need. Methods return JSON so no hidden type crosses the binder.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@SuppressLint("MissingPermission", "PrivateApi", "DiscouragedPrivateApi")
class CodecUserService(private val context: Context?) : ICodecService.Stub() {

    constructor() : this(null)

    private var a2dp: BluetoothA2dp? = null

    override fun destroy() {
        exitProcess(0)
    }

    override fun state(): String = guarded {
        val a2dp = connect()
        val device = activeDevice(a2dp) ?: return@guarded JSONObject().put("error", "No active Bluetooth audio device. Connect a headset and play something once.").toString()
        val status = codecStatus(a2dp, device)
        JSONObject()
            .put("device", device.name ?: device.address)
            .put("current", status?.codecConfig?.let(::describe))
            .put("selectable", JSONArray(status?.codecsSelectableCapabilities.orEmpty().map { it.codecType }.distinct()))
            .put("local", JSONArray(status?.codecsLocalCapabilities.orEmpty().map { it.codecType }.distinct()))
            .toString()
    }

    override fun select(codecType: Int): String = guarded {
        setPreference(codecType, BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
    }

    override fun reset(codecTypes: IntArray): String = guarded {
        codecTypes.joinToString("") { setPreference(it, BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT) }
    }

    private fun setPreference(codecType: Int, priority: Int): String {
        val a2dp = connect()
        val device = activeDevice(a2dp) ?: return "No active Bluetooth audio device"
        val config = BluetoothCodecConfig.Builder().setCodecType(codecType).setCodecPriority(priority).build()
        BluetoothA2dp::class.java.getMethod("setCodecConfigPreference", BluetoothDevice::class.java, BluetoothCodecConfig::class.java)
            .invoke(a2dp, device, config)
        return ""
    }

    private fun guarded(block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        JSONObject().put("error", "${cause.javaClass.simpleName}: ${cause.message}").toString()
    }

    private fun connect(): BluetoothA2dp {
        a2dp?.let { return it }
        ensureMainLooper()
        runCatching { Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null) }
        val uid = Process.myUid()
        val source = AttributionSource.Builder(uid).setPackageName(if (uid == ROOT_UID) "root" else SHELL_PACKAGE).build()
        val adapter = BluetoothAdapter::class.java.getMethod("createAdapter", AttributionSource::class.java).invoke(null, source) as BluetoothAdapter?
            ?: error("Bluetooth service not available")
        val latch = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, connected: BluetoothProfile) {
                proxy = connected
                latch.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
            }
        }
        check(adapter.getProfileProxy(context ?: systemContext(), listener, BluetoothProfile.A2DP)) { "A2DP profile proxy refused" }
        check(latch.await(PROXY_TIMEOUT_S, TimeUnit.SECONDS)) { "A2DP profile did not connect" }
        return (proxy as BluetoothA2dp).also { a2dp = it }
    }

    private fun activeDevice(a2dp: BluetoothA2dp): BluetoothDevice? =
        runCatching { BluetoothA2dp::class.java.getMethod("getActiveDevice").invoke(a2dp) as BluetoothDevice? }.getOrNull()
            ?: a2dp.connectedDevices.firstOrNull()

    private fun codecStatus(a2dp: BluetoothA2dp, device: BluetoothDevice): BluetoothCodecStatus? =
        BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, device) as BluetoothCodecStatus?

    private fun describe(config: BluetoothCodecConfig) = JSONObject()
        .put("type", config.codecType)
        .put("sampleRate", config.sampleRate)
        .put("bitsPerSample", config.bitsPerSample)
        .put("channelMode", config.channelMode)

    /** BluetoothProfileConnector posts to the main looper, which a bare app_process may not run. */
    private fun ensureMainLooper() {
        if (Looper.getMainLooper() != null) return
        val ready = CountDownLatch(1)
        Thread {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            ready.countDown()
            Looper.loop()
        }.apply { isDaemon = true }.start()
        ready.await()
    }

    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null) ?: activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_PACKAGE = "com.android.shell"
        const val PROXY_TIMEOUT_S = 5L
    }
}
