package io.github.rthery.devicechecker.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.profile.DeviceProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var profile by remember { mutableStateOf(viewModel.profile.value) }
    var codecsText by remember { mutableStateOf(profile.stockBluetoothCodecs.joinToString(", ")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_profile_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back)) } },
                actions = {
                    TextButton(onClick = {
                        viewModel.saveProfile(profile)
                        onBack()
                    }) { Text(stringResource(R.string.screen_profile_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.screen_profile_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(R.string.screen_profile_section_radios)
            Toggle(R.string.screen_profile_phone, profile.isPhone) { profile = profile.copy(isPhone = it) }
            Toggle(R.string.screen_profile_telephony, profile.stockHasTelephony) { profile = profile.copy(stockHasTelephony = it) }
            Toggle(R.string.screen_profile_wifi, profile.stockHasWifi) { profile = profile.copy(stockHasWifi = it) }
            Toggle(R.string.screen_profile_bluetooth, profile.stockHasBluetooth) { profile = profile.copy(stockHasBluetooth = it) }
            Toggle(R.string.screen_profile_gps, profile.stockHasGps) { profile = profile.copy(stockHasGps = it) }
            Toggle(R.string.screen_profile_nfc, profile.stockHasNfc) { profile = profile.copy(stockHasNfc = it) }
            Toggle(R.string.screen_profile_fm_radio, profile.stockHasFmRadio) { profile = profile.copy(stockHasFmRadio = it) }
            Toggle(R.string.screen_profile_ir_blaster, profile.stockHasIrBlaster) { profile = profile.copy(stockHasIrBlaster = it) }

            SectionTitle(R.string.screen_profile_section_hardware)
            Toggle(R.string.screen_profile_fingerprint, profile.stockHasFingerprint) { profile = profile.copy(stockHasFingerprint = it) }
            Toggle(R.string.screen_profile_sd_slot, profile.stockHasSdSlot) { profile = profile.copy(stockHasSdSlot = it) }
            Toggle(R.string.screen_profile_rear_camera, profile.stockHasRearCamera) { profile = profile.copy(stockHasRearCamera = it) }
            Toggle(R.string.screen_profile_front_camera, profile.stockHasFrontCamera) { profile = profile.copy(stockHasFrontCamera = it) }
            NumberField(R.string.screen_profile_rear_camera_count, profile.stockRearCameraCount) { profile = profile.copy(stockRearCameraCount = it) }
            NumberField(R.string.screen_profile_front_camera_count, profile.stockFrontCameraCount) { profile = profile.copy(stockFrontCameraCount = it) }

            SectionTitle(R.string.screen_profile_section_display)
            NumberField(R.string.screen_profile_width, profile.stockWidthPx) { profile = profile.copy(stockWidthPx = it) }
            NumberField(R.string.screen_profile_height, profile.stockHeightPx) { profile = profile.copy(stockHeightPx = it) }
            NumberField(R.string.screen_profile_density, profile.stockDensityDpi) { profile = profile.copy(stockDensityDpi = it) }
            Toggle(R.string.screen_profile_hdr10, profile.stockHasHdr10) { profile = profile.copy(stockHasHdr10 = it) }

            SectionTitle(R.string.screen_profile_section_security)
            Toggle(R.string.screen_profile_forced_encryption, profile.stockHasForcedEncryption) { profile = profile.copy(stockHasForcedEncryption = it) }
            Toggle(R.string.screen_profile_hardware_encryption, profile.stockHasHardwareEncryption) { profile = profile.copy(stockHasHardwareEncryption = it) }
            Toggle(R.string.screen_profile_frp, profile.stockHasFrp) { profile = profile.copy(stockHasFrp = it) }
            Toggle(R.string.screen_profile_exfat, profile.stockHasExfat) { profile = profile.copy(stockHasExfat = it) }

            SectionTitle(R.string.screen_profile_section_mac)
            OutlinedTextField(
                value = profile.stockWifiMac,
                onValueChange = { profile = profile.copy(stockWifiMac = it) },
                label = { Text(stringResource(R.string.screen_profile_wifi_mac)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = profile.stockBluetoothMac,
                onValueChange = { profile = profile.copy(stockBluetoothMac = it) },
                label = { Text(stringResource(R.string.screen_profile_bluetooth_mac)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(R.string.screen_profile_section_bluetooth_codecs)
            OutlinedTextField(
                value = codecsText,
                onValueChange = {
                    codecsText = it
                    profile = profile.copy(stockBluetoothCodecs = it.split(',').map(String::trim).filter(String::isNotEmpty))
                },
                label = { Text(stringResource(R.string.screen_profile_bluetooth_codecs)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Toggle(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(@StringRes label: Int, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text -> if (text.all(Char::isDigit)) onChange(text.toIntOrNull()) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
