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

private class SensorGoal(val type: Int, val name: String, val instruction: String, val reached: (FloatArray, SensorState) -> Boolean)

private class SensorState {
    var first: FloatArray? = null
    var sawNear = false
    var sawDrop = false
}

private val SENSOR_GOALS = listOf(
    SensorGoal(Sensor.TYPE_PROXIMITY, "Proximity", "Cover the top of the screen with your hand, then uncover it.") { values, state ->
        val near = values[0] < 1f
        if (near) state.sawNear = true
        state.sawNear && !near
    },
    SensorGoal(Sensor.TYPE_LIGHT, "Light", "Cover the light sensor (near the earpiece), then uncover it.") { values, state ->
        val baseline = state.first?.get(0) ?: return@SensorGoal false
        if (values[0] < baseline * LIGHT_DROP_RATIO) state.sawDrop = true
        state.sawDrop && values[0] > baseline * 0.7f
    },
    SensorGoal(Sensor.TYPE_ACCELEROMETER, "Accelerometer", "Turn the phone face down, then face up.") { values, state ->
        if (values[2] < -7f) state.sawDrop = true
        state.sawDrop && values[2] > 7f
    },
    SensorGoal(Sensor.TYPE_GYROSCOPE, "Gyroscope", "Rotate the phone quickly.") { values, _ ->
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
        Instructions("Do each action. A sensor turns green when it reacts.")
        goals.forEach { goal ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${if (reached[goal.type] == true) "✓" else "…"} ${goal.name}", style = MaterialTheme.typography.titleSmall)
                    Text(goal.instruction, style = MaterialTheme.typography.bodyMedium)
                    Mono(live[goal.type] ?: "no event yet")
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
        }, modifier = Modifier.fillMaxWidth()) { Text(if (reached.size == goals.size) "Save result" else "Save (unreached count as failed)") }
    }
}

@Composable
private fun FingerprintTest(scope: TestScope) {
    if (!scope.profile.stockHasFingerprint) {
        NotApplicable(scope, "Stock has no fingerprint sensor")
        return
    }
    val (status, code) = remember { Biometrics.status(scope.activity) }
    var rejections by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var matched by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            BiometricStatus.NONE_ENROLLED -> {
                Instructions("No fingerprint is enrolled. Enrolling one is itself a good test of the sensor.")
                ShortcutButton(Shortcuts.fingerprintEnroll)
            }
            BiometricStatus.READY -> {
                Instructions(
                    if (Biometrics.hasSystemPrompt) "Tap Start. First touch with an unenrolled finger (should be rejected), then with an enrolled one."
                    else "Tap Start, then touch the sensor: first with an unenrolled finger (should be rejected), then with an enrolled one. This Android version has no system prompt."
                )
                Button(onClick = {
                    Biometrics.authenticate(scope.activity, "Fingerprint test", object : BiometricListener {
                        override fun onMatch() {
                            matched = true
                            outcome = "Matched"
                        }

                        override fun onReject() {
                            rejections++
                        }

                        override fun onError(message: String) {
                            outcome = message
                        }
                    })
                }) { Text("Start") }
            }
            else -> Instructions("Biometric status $status ($code): the sensor is not usable.")
        }
        Mono("Rejections: $rejections\nResult: ${outcome ?: "-"}")
        if (outcome != null) {
            YesNo(
                if (Biometrics.hasSystemPrompt) "Did the prompt describe where the sensor is correctly (rear, front, side or under the screen)?"
                else "Did the sensor respond where you expected it?",
                yes = "Correct", no = "Wrong place",
            ) { hintRight ->
                val details = "Rejections: $rejections\nResult: $outcome\nstatus: $status\nLocation hint: ${if (hintRight) "correct" else "wrong"}"
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
    "LG / NEC TV power" to necPattern(0x04, 0x08),
    "Generic NEC power" to necPattern(0x00, 0x45),
)

@Composable
private fun IrTest(scope: TestScope) {
    if (!scope.profile.stockHasIrBlaster) {
        NotApplicable(scope, "Stock has no IR blaster")
        return
    }
    val ir = remember { scope.activity.getSystemService(ConsumerIrManager::class.java) }
    var log by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ir?.hasIrEmitter() != true) {
            Instructions("No IR emitter is exposed.")
            Button(onClick = { scope.finish(fail("No IR emitter")) }) { Text("Save result") }
            return@Column
        }
        Instructions("Point the phone at a TV, or watch the IR LED through another phone's camera: most cameras show IR as a purple flash.")
        IR_CODES.forEach { (name, pattern) ->
            OutlinedButton(onClick = {
                log = runCatching {
                    ir.transmit(IR_CARRIER_HZ, pattern)
                    "Sent $name (${pattern.size} pulses, total ${pattern.sum() / 1000} ms)"
                }.getOrElse { "Transmit failed: ${it.message}" }
            }) { Text("Send $name") }
        }
        if (log.isNotEmpty()) Mono(log)
        YesNo("Did the TV react or the LED flash?") { ok ->
            scope.finish(if (ok) pass("IR emission confirmed", log) else fail("No IR emission seen", log))
        }
    }
}
