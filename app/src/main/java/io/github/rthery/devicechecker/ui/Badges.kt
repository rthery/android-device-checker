package io.github.rthery.devicechecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.data.Outcome

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFEF8F00)
private val Blue = Color(0xFF1565C0)
private val Grey = Color(0xFF757575)

fun Outcome.color(): Color = when (this) {
    Outcome.PASS -> Green
    Outcome.FAIL -> Red
    Outcome.WARN, Outcome.NEEDS_SHELL -> Amber
    Outcome.TO_VERIFY -> Blue
    Outcome.UNTESTED, Outcome.NOT_APPLICABLE, Outcome.NOT_ON_DEVICE, Outcome.WAIVED -> Grey
}

fun CheckStatus.color(): Color = when (this) {
    CheckStatus.PASS -> Green
    CheckStatus.FAIL, CheckStatus.ERROR -> Red
    CheckStatus.WARN, CheckStatus.NEEDS_SHELL -> Amber
    CheckStatus.INFO -> Blue
    CheckStatus.NOT_APPLICABLE -> Grey
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
