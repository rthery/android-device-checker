package io.github.rthery.devicechecker.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.rthery.devicechecker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_RUNNING, UNSUPPORTED, PERMISSION_NEEDED, CONNECTING, READY }

object ShizukuShell : Shell {

    private const val PERMISSION_REQUEST_CODE = 4242

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _runsAsRoot = MutableStateFlow(false)
    /** True when the server was started from a root shell, so its reads are root reads, not shell reads. */
    val runsAsRoot: StateFlow<Boolean> = _runsAsRoot.asStateFlow()

    @Volatile
    private var service: IShellService? = null

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(IShellService.Stub::asInterface)
            _state.value = if (service != null) ShizukuState.READY else ShizukuState.NOT_RUNNING
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            _state.value = ShizukuState.NOT_RUNNING
            _runsAsRoot.value = false
        }
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                refresh()
            }
        }
    }

    fun refresh() {
        _runsAsRoot.value = uid == 0
        _state.value = when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.UNSUPPORTED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.PERMISSION_NEEDED
            service != null -> ShizukuState.READY
            _state.value == ShizukuState.CONNECTING -> ShizukuState.CONNECTING
            else -> {
                Shizuku.bindUserService(serviceArgs, connection)
                ShizukuState.CONNECTING
            }
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    val isReady: Boolean get() = service != null

    /** The server's uid: 0 when started from a root shell, 2000 for adb shell; null when not running. */
    val uid: Int? get() = if (Shizuku.pingBinder()) runCatching { Shizuku.getUid() }.getOrNull() else null

    /** Gives a just-started process a moment to reconnect; returns at once when Shizuku is not running. */
    suspend fun awaitReady(timeoutMs: Long = 3_000) {
        refresh()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isReady && _state.value != ShizukuState.NOT_RUNNING && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
            if (_state.value == ShizukuState.PERMISSION_NEEDED) return
        }
    }

    override suspend fun exec(command: String): ShellOutput = withContext(Dispatchers.IO) {
        val current = service ?: return@withContext ShellOutput(-1, "Shizuku service not connected")
        try {
            val raw = current.exec(command)
            val exitCode = raw.substringBefore('\n').toIntOrNull() ?: -1
            ShellOutput(exitCode, raw.substringAfter('\n', ""))
        } catch (e: Exception) {
            ShellOutput(-1, e.message.orEmpty())
        }
    }
}
