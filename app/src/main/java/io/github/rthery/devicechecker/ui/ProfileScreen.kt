package io.github.rthery.devicechecker.ui

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.profile.DeviceProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var profile by remember { mutableStateOf(viewModel.profile.value) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = {
                        viewModel.saveProfile(profile)
                        onBack()
                    }) { Text("Save") }
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
                "Fill this from the stock ROM or the OEM spec sheet, not from this build, or the checks only compare the build with itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Form factor and radios")
            Toggle("Phone (has an earpiece)", profile.isPhone) { profile = profile.copy(isPhone = it) }
            Toggle("Telephony (RIL)", profile.stockHasTelephony) { profile = profile.copy(stockHasTelephony = it) }
            Toggle("Wi-Fi", profile.stockHasWifi) { profile = profile.copy(stockHasWifi = it) }
            Toggle("Bluetooth", profile.stockHasBluetooth) { profile = profile.copy(stockHasBluetooth = it) }
            Toggle("GPS", profile.stockHasGps) { profile = profile.copy(stockHasGps = it) }
            Toggle("NFC", profile.stockHasNfc) { profile = profile.copy(stockHasNfc = it) }
            Toggle("FM radio", profile.stockHasFmRadio) { profile = profile.copy(stockHasFmRadio = it) }
            Toggle("IR blaster", profile.stockHasIrBlaster) { profile = profile.copy(stockHasIrBlaster = it) }

            SectionTitle("Hardware")
            Toggle("Fingerprint sensor", profile.stockHasFingerprint) { profile = profile.copy(stockHasFingerprint = it) }
            Toggle("SD card slot", profile.stockHasSdSlot) { profile = profile.copy(stockHasSdSlot = it) }
            Toggle("Rear camera", profile.stockHasRearCamera) { profile = profile.copy(stockHasRearCamera = it) }
            Toggle("Front camera", profile.stockHasFrontCamera) { profile = profile.copy(stockHasFrontCamera = it) }
            NumberField("Rear camera sensors", profile.stockRearCameraCount) { profile = profile.copy(stockRearCameraCount = it) }
            NumberField("Front camera sensors", profile.stockFrontCameraCount) { profile = profile.copy(stockFrontCameraCount = it) }

            SectionTitle("Display")
            NumberField("Width (px)", profile.stockWidthPx) { profile = profile.copy(stockWidthPx = it) }
            NumberField("Height (px)", profile.stockHeightPx) { profile = profile.copy(stockHeightPx = it) }
            NumberField("Density (dpi)", profile.stockDensityDpi) { profile = profile.copy(stockDensityDpi = it) }
            Toggle("HDR10", profile.stockHasHdr10) { profile = profile.copy(stockHasHdr10 = it) }

            SectionTitle("Security and storage")
            Toggle("Forced encryption", profile.stockHasForcedEncryption) { profile = profile.copy(stockHasForcedEncryption = it) }
            Toggle("Hardware-backed encryption", profile.stockHasHardwareEncryption) { profile = profile.copy(stockHasHardwareEncryption = it) }
            Toggle("Factory Reset Protection", profile.stockHasFrp) { profile = profile.copy(stockHasFrp = it) }
            Toggle("exFAT", profile.stockHasExfat) { profile = profile.copy(stockHasExfat = it) }

            SectionTitle("MAC addresses")
            OutlinedTextField(
                value = profile.stockWifiMac,
                onValueChange = { profile = profile.copy(stockWifiMac = it) },
                label = { Text("Stock Wi-Fi MAC") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = profile.stockBluetoothMac,
                onValueChange = { profile = profile.copy(stockBluetoothMac = it) },
                label = { Text("Stock Bluetooth MAC") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text -> if (text.all(Char::isDigit)) onChange(text.toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
