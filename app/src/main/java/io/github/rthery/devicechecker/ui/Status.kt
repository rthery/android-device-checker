package io.github.rthery.devicechecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.catalog.Level
import io.github.rthery.devicechecker.data.TestState

data class StateColors(val container: Color, val content: Color, val border: Color)

private val LightPass = Color(0xFF1E7041)
private val LightFail = Color(0xFFB3261E)
private val LightWarn = Color(0xFF7A5000)
private val LightNeeds = Color(0xFF2155C4)
private val DarkPass = Color(0xFF7DDBA0)
private val DarkFail = Color(0xFFFFB4AB)
private val DarkWarn = Color(0xFFF5C065)
private val DarkNeeds = Color(0xFFB0C6FF)

/** Fixed hues for meaning; everything else comes from the system color scheme. */
@Composable
fun TestState.colors(): StateColors {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    fun solid(light: Color, darkColor: Color, onDark: Color) =
        if (dark) StateColors(darkColor, onDark, darkColor) else StateColors(light, Color.White, light)
    return when (this) {
        TestState.PASS -> solid(LightPass, DarkPass, Color(0xFF00391B))
        TestState.FAIL -> solid(LightFail, DarkFail, Color(0xFF690005))
        TestState.WARN -> solid(LightWarn, DarkWarn, Color(0xFF422C00))
        TestState.NEEDS -> solid(LightNeeds, DarkNeeds, Color(0xFF002D6E))
        TestState.RUNNING, TestState.QUEUED -> StateColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primaryContainer)
        TestState.NA, TestState.WAIVED -> StateColors(Color.Transparent, scheme.onSurfaceVariant, scheme.outline)
        TestState.NOT_RUN -> StateColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
    }
}

/** Shape and color both carry the state, so it reads without color too. */
@Composable
fun StateDot(state: TestState, modifier: Modifier = Modifier) {
    val colors = state.colors()
    val label = stringResource(state.labelRes)
    Box(
        modifier
            .size(32.dp)
            .background(colors.container, CircleShape)
            .border(1.5.dp, colors.border, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TestState.PASS -> Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = colors.content)
            TestState.FAIL -> Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = colors.content)
            TestState.WARN -> Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = colors.content)
            TestState.NEEDS -> Icon(Icons.Filled.Person, null, Modifier.size(18.dp), tint = colors.content)
            TestState.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), color = colors.content, strokeWidth = 2.5.dp)
            TestState.QUEUED -> Text("…", color = colors.content, fontWeight = FontWeight.Bold)
            TestState.NA -> Text("/", color = colors.content, fontWeight = FontWeight.Bold)
            TestState.WAIVED -> Text(stringResource(R.string.tests_state_waived_glyph), color = colors.content, fontWeight = FontWeight.Bold)
            TestState.NOT_RUN -> Text("–", color = colors.content, fontWeight = FontWeight.Bold)
        }
    }
}

/** MUST and MUST NOT are filled, SHOULD is outlined, MAY shows nothing. */
@Composable
fun LevelPill(level: Level?, modifier: Modifier = Modifier) {
    if (level == null || level == Level.MAY) return
    val scheme = MaterialTheme.colorScheme
    val strong = level == Level.MUST || level == Level.MUST_NOT
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = level.label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        color = if (strong) scheme.surface else scheme.onSurfaceVariant,
        modifier = modifier
            .background(if (strong) scheme.onSurfaceVariant else Color.Transparent, shape)
            .border(1.dp, if (strong) scheme.onSurfaceVariant else scheme.outline, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
