package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.compat.BiometricStatus
import io.github.rthery.devicechecker.compat.BiometricListener
import io.github.rthery.devicechecker.compat.Biometrics
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.ui.UiText
import io.github.rthery.devicechecker.ui.englishContext
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.pass
import kotlin.math.sqrt

private const val IR_CARRIER_HZ = 38_000
private const val GYRO_ROTATION_RAD_S = 1.5f
private const val LIGHT_DROP_RATIO = 0.3f

val sensorTests = listOf(
    InteractiveTest("sensors.motion", "Sensor reactions", "Asks you to move or cover the phone and detects each sensor reacting.") { SensorReactionTest(it) },
    InteractiveTest("fingerprint.auth", "Fingerprint unlock", "Shows the system fingerprint prompt and checks a match and a rejection.") { FingerprintTest(it) },
    InteractiveTest("ir.transmit", "IR blaster", "Sends TV power codes so you can see or check the emitter.") { IrTest(it) },
)

private class SensorGoal(val type: Int, val name: String, @StringRes val display: Int, @StringRes val instruction: Int, val reached: (FloatArray, SensorState) -> Boolean)

private class SensorState {
    var first: FloatArray? = null
    var sawNear = false
    var sawDrop = false
}

private val SENSOR_GOALS = listOf(
    SensorGoal(Sensor.TYPE_PROXIMITY, "Proximity", R.string.hw_sensor_proximity, R.string.hw_sensor_proximity_instruction) { values, state ->
        val near = values[0] < 1f
        if (near) state.sawNear = true
        state.sawNear && !near
    },
    SensorGoal(Sensor.TYPE_LIGHT, "Light", R.string.hw_sensor_light, R.string.hw_sensor_light_instruction) { values, state ->
        val baseline = state.first?.get(0) ?: return@SensorGoal false
        if (values[0] < baseline * LIGHT_DROP_RATIO) state.sawDrop = true
        state.sawDrop && values[0] > baseline * 0.7f
    },
    SensorGoal(Sensor.TYPE_ACCELEROMETER, "Accelerometer", R.string.hw_sensor_accelerometer, R.string.hw_sensor_accelerometer_instruction) { values, state ->
        if (values[2] < -7f) state.sawDrop = true
        state.sawDrop && values[2] > 7f
    },
    SensorGoal(Sensor.TYPE_GYROSCOPE, "Gyroscope", R.string.hw_sensor_gyroscope, R.string.hw_sensor_gyroscope_instruction) { values, _ ->
        sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]) > GYRO_ROTATION_RAD_S
    },
)

@Composable
private fun SensorReactionTest(scope: TestScope) {
    val sensors = remember { scope.activity.getSystemService(SensorManager::class.java) }
    val goals = remember { SENSOR_GOALS.filter { sensors.getDefaultSensor(it.type) != null } }
    val reached = remember { mutableStateMapOf<Int, Boolean>() }
    val live = remember { mutableStateMapOf<Int, String>() }

    DisposableEffect(sensors) {
        val listeners = goals.map { goal ->
            val state = SensorState()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (state.first == null) state.first = event.values.copyOf()
                    live[goal.type] = event.values.take(3).joinToString { "%.2f".format(it) }
                    if (reached[goal.type] != true && goal.reached(event.values, state)) reached[goal.type] = true
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensors.registerListener(listener, sensors.getDefaultSensor(goal.type), SensorManager.SENSOR_DELAY_UI)
            listener
        }
        onDispose { listeners.forEach(sensors::unregisterListener) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.hw_sensor_reaction_instructions))
        goals.forEach { goal ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${if (reached[goal.type] == true) "✓" else "…"} ${stringResource(goal.display)}", style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(goal.instruction), style = MaterialTheme.typography.bodyMedium)
                    Mono(live[goal.type] ?: stringResource(R.string.hw_sensor_no_event_yet))
                }
            }
        }
        Button(onClick = {
            val results = ItemResults()
            goals.forEach { goal ->
                if (reached[goal.type] == true) results.add(CheckStatus.PASS, "${goal.name} reacted")
                else results.add(CheckStatus.FAIL, "${goal.name} did not react (last: ${live[goal.type] ?: "no event"})")
            }
            scope.finish(results.toResult("Every core sensor reacted", "No core sensors present"))
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (reached.size == goals.size) R.string.save_result else R.string.hw_sensor_save_unreached)) }
    }
}

@Composable
private fun FingerprintTest(scope: TestScope) {
    if (!scope.profile.stockHasFingerprint) {
        NotApplicable(scope, R.string.hw_sensor_stock_no_fingerprint)
        return
    }
    val (status, code) = remember { Biometrics.status(scope.activity) }
    var rejections by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<UiText?>(null) }
    var matched by remember { mutableStateOf(false) }
    val english = LocalContext.current.englishContext()
    val promptTitle = stringResource(R.string.hw_sensor_fp_prompt_title)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            BiometricStatus.NONE_ENROLLED -> {
                Instructions(stringResource(R.string.hw_sensor_fp_none_enrolled))
                ShortcutButton(Shortcuts.fingerprintEnroll)
            }
            BiometricStatus.READY -> {
                Instructions(
                    stringResource(if (Biometrics.hasSystemPrompt) R.string.hw_sensor_fp_instructions_prompt else R.string.hw_sensor_fp_instructions_legacy)
                )
                Button(onClick = {
                    Biometrics.authenticate(scope.activity, promptTitle, object : BiometricListener {
                        override fun onMatch() {
                            matched = true
                            outcome = UiText.Res(R.string.hw_sensor_fp_matched)
                        }

                        override fun onReject() {
                            rejections++
                        }

                        override fun onError(message: String) {
                            outcome = UiText.Raw(message)
                        }
                    })
                }) { Text(stringResource(R.string.hw_sensor_fp_start)) }
            }
            else -> Instructions(stringResource(R.string.hw_sensor_fp_unusable, status.toString(), code))
        }
        Mono(stringResource(R.string.hw_sensor_fp_status, rejections, outcome?.resolve() ?: "-"))
        outcome?.let { current ->
            YesNo(
                stringResource(if (Biometrics.hasSystemPrompt) R.string.hw_sensor_fp_question_prompt else R.string.hw_sensor_fp_question_legacy),
                yes = stringResource(R.string.hw_sensor_fp_correct), no = stringResource(R.string.hw_sensor_fp_wrong_place),
            ) { hintRight ->
                val details = "Rejections: $rejections\nResult: ${current.resolve(english)}\nstatus: $status\nLocation hint: ${if (hintRight) "correct" else "wrong"}"
                scope.finish(
                    when {
                        !matched -> fail("No successful match", details)
                        !hintRight -> fail("Matches, but the prompt points to the wrong sensor location (check the SystemUI/Settings overlays)", details)
                        rejections > 0 -> pass("Matches enrolled finger and rejects others", details)
                        else -> pass("Matches enrolled finger (rejection not tried)", details)
                    }
                )
            }
        }
    }
}

/** NEC frame (9 ms leader, 562.5 µs units) for an 8-bit address and command. */
private fun necPattern(address: Int, command: Int): IntArray {
    val bits = listOf(address, address.inv() and 0xFF, command, command.inv() and 0xFF)
    val pattern = mutableListOf(9000, 4500)
    bits.forEach { byte ->
        for (bit in 0 until 8) {
            pattern += 562
            pattern += if ((byte shr bit) and 1 == 1) 1687 else 562
        }
    }
    pattern += 562
    return pattern.toIntArray()
}

private val IR_CODES = listOf(
    R.string.hw_sensor_ir_code_lg_nec to necPattern(0x04, 0x08),
    R.string.hw_sensor_ir_code_generic_nec to necPattern(0x00, 0x45),
)

@Composable
private fun IrTest(scope: TestScope) {
    if (!scope.profile.stockHasIrBlaster) {
        NotApplicable(scope, R.string.hw_sensor_stock_no_ir)
        return
    }
    val ir = remember { scope.activity.getSystemService(ConsumerIrManager::class.java) }
    val context = LocalContext.current
    val english = remember(context) { context.englishContext() }
    var log by remember { mutableStateOf<((Context) -> String)?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ir?.hasIrEmitter() != true) {
            Instructions(stringResource(R.string.hw_sensor_ir_no_emitter))
            Button(onClick = { scope.finish(fail("No IR emitter")) }) { Text(stringResource(R.string.save_result)) }
            return@Column
        }
        Instructions(stringResource(R.string.hw_sensor_ir_instructions))
        IR_CODES.forEach { (name, pattern) ->
            OutlinedButton(onClick = {
                log = runCatching { ir.transmit(IR_CARRIER_HZ, pattern) }.fold(
                    onSuccess = { { c: Context -> c.getString(R.string.hw_sensor_ir_sent, c.getString(name), pattern.size, pattern.sum() / 1000) } },
                    onFailure = { error -> { c: Context -> c.getString(R.string.hw_sensor_ir_failed, error.message.toString()) } },
                )
            }) { Text(stringResource(R.string.hw_sensor_ir_send, stringResource(name))) }
        }
        log?.let { Mono(it(context)) }
        YesNo(stringResource(R.string.hw_sensor_ir_question)) { ok ->
            val details = log?.invoke(english).orEmpty()
            scope.finish(if (ok) pass("IR emission confirmed", details) else fail("No IR emission seen", details))
        }
    }
}
