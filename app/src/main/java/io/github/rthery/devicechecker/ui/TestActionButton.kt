package io.github.rthery.devicechecker.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.R

/** An icon and fill distinguish hands-on actions even without color perception. */
@Composable
fun TestActionButton(label: String, handsOn: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(if (handsOn) Icons.Filled.Person else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
    // Material's default button shape is a fixed pill, independent of pack corner hints.
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    if (handsOn) FilledTonalButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
        ),
        content = content,
    ) else OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
        border = BorderStroke(1.dp, if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
        content = content,
    )
}

@StringRes
fun handsOnLabel(id: String, manual: Boolean = false): Int = when (id) {
    "nfc.tag" -> R.string.tests_hands_on_nfc
    "audio.output" -> R.string.tests_hands_on_listen
    "audio.input" -> R.string.tests_hands_on_record_audio
    "camera.capture" -> R.string.tests_hands_on_photos
    "video.record" -> R.string.tests_hands_on_video
    "sensors.motion" -> R.string.tests_hands_on_motion
    "fingerprint.auth" -> R.string.tests_hands_on_fingerprint
    "gnss.fix" -> R.string.tests_hands_on_gps
    "bluetooth.scan" -> R.string.tests_hands_on_bluetooth
    "bluetooth.codecs.headset" -> R.string.tests_hands_on_bluetooth_codecs
    "ir.transmit" -> R.string.tests_hands_on_ir
    else -> if (manual) R.string.tests_hands_on_follow_steps else R.string.tests_hands_on_start
}
