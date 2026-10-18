package io.github.rthery.devicechecker.pack.ui

import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.pack.ThemeHints

/** Applies a pack's accent over the dynamic scheme; containers are blends so text contrast stays close to Material's. */
fun ColorScheme.withHints(hints: ThemeHints?, dark: Boolean): ColorScheme {
    val seed = hints?.accentSeed?.let { Color(android.graphics.Color.parseColor(it)) } ?: return this
    val primary = if (dark) lerp(seed, Color.White, 0.35f) else seed
    val container = lerp(seed, surface, if (dark) 0.65f else 0.8f)
    return copy(
        primary = primary,
        onPrimary = contentColorOn(primary),
        primaryContainer = container,
        onPrimaryContainer = contentColorOn(container),
        secondaryContainer = lerp(seed, surface, if (dark) 0.75f else 0.85f),
        onSecondaryContainer = onSurface,
        surfaceTint = primary,
    )
}

fun shapesFor(hints: ThemeHints?): Shapes {
    val scale = hints?.cornerScale ?: return Shapes()
    fun corner(base: Int) = RoundedCornerShape((base * scale).dp)
    return Shapes(extraSmall = corner(4), small = corner(8), medium = corner(12), large = corner(16), extraLarge = corner(28))
}

fun Typography.withHints(hints: ThemeHints?): Typography {
    val name = hints?.fontFamily ?: return this
    val family = FontFamily(Typeface.create(name, Typeface.NORMAL))
    fun TextStyle.f() = copy(fontFamily = family)
    return Typography(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

private fun contentColorOn(background: Color) = if (background.luminance() > 0.5f) Color.Black else Color.White
