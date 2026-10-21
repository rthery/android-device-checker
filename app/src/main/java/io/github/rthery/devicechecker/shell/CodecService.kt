package io.github.rthery.devicechecker.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import io.github.rthery.devicechecker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Binds [CodecUserService] through Shizuku for the length of one test. */
object CodecService {

    private const val BIND_TIMEOUT_MS = 10_000L

    private val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, CodecUserService::class.java.name))
        .daemon(false)
        .processNameSuffix("codec")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    /** Returns null when Shizuku does not bind the service in time. */
    suspend fun <T> use(block: suspend (ICodecService) -> T): T? {
        var connection: ServiceConnection? = null
        val service = withContext(Dispatchers.Main) {
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val bound = binder?.takeIf { it.pingBinder() }?.let(ICodecService.Stub::asInterface)
                            if (cont.isActive) cont.resume(bound)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }
                    connection = conn
                    Shizuku.bindUserService(args, conn)
                }
            }
        }
        return try {
            service?.let { withContext(Dispatchers.IO) { block(it) } }
        } finally {
            connection?.let { conn -> withContext(Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, conn, true) } } }
        }
    }
}

/** What [ICodecService.state] reports, parsed on the app side. */
class CodecState(
    val error: String?,
    val device: String?,
    val currentType: Int?,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val selectable: List<Int>,
    val local: List<Int>,
) {
    companion object {
        fun parse(json: String): CodecState {
            val root = JSONObject(json)
            val current = root.optJSONObject("current")
            fun ints(key: String) = root.optJSONArray(key)?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
            return CodecState(
                error = root.optString("error").ifEmpty { null },
                device = root.optString("device").ifEmpty { null },
                currentType = current?.optInt("type"),
                sampleRate = current?.optInt("sampleRate") ?: 0,
                bitsPerSample = current?.optInt("bitsPerSample") ?: 0,
                selectable = ints("selectable"),
                local = ints("local"),
            )
        }
    }
}
