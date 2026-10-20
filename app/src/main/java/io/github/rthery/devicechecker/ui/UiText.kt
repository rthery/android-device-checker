package io.github.rthery.devicechecker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale

/** Screen text from a string resource, or verbatim text from a device pack. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Raw -> value
    }

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Raw -> value
    }
}

/** Results and reports stay in English whatever the device language. English is the default `values/`, so no bundle split can drop it. */
@SuppressLint("AppBundleLocaleChanges")
fun Context.englishContext(): Context =
    createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) })
