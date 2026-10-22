package io.github.rthery.devicechecker.profile

import kotlinx.serialization.Serializable

/**
 * What the stock firmware of this device supports. Checks compare the running build against it,
 * so it must be filled from the stock ROM or the OEM spec sheet, never from this build.
 */
@Serializable
data class DeviceProfile(
    val isPhone: Boolean = true,
    val stockHasTelephony: Boolean = true,
    val stockHasWifi: Boolean = true,
    val stockHasBluetooth: Boolean = true,
    val stockHasGps: Boolean = true,
    val stockHasNfc: Boolean = false,
    val stockHasFingerprint: Boolean = true,
    val stockHasIrBlaster: Boolean = false,
    val stockHasSdSlot: Boolean = false,
    val stockHasFmRadio: Boolean = false,
    val stockHasFrontCamera: Boolean = true,
    val stockHasRearCamera: Boolean = true,
    val stockRearCameraCount: Int? = null,
    val stockFrontCameraCount: Int? = null,
    val stockHasHdr10: Boolean = false,
    val stockHasExfat: Boolean = false,
    val stockHasForcedEncryption: Boolean = true,
    val stockHasHardwareEncryption: Boolean = true,
    val stockHasFrp: Boolean = true,
    val stockWidthPx: Int? = null,
    val stockHeightPx: Int? = null,
    val stockDensityDpi: Int? = null,
    val stockWifiMac: String = "",
    val stockBluetoothMac: String = "",
    /** A2DP source codecs stock offers, as the stack names them (SBC, AAC, aptX, aptX HD, LDAC...). */
    val stockBluetoothCodecs: List<String> = emptyList(),
    /** Battery design capacity in mAh, as stock reports it or as the OEM spec sheet gives it. */
    val stockBatteryDesignMah: Int? = null,
    /** Physical SIM slots on stock; null when not captured. */
    val stockSimSlots: Int? = null,
    val stockHasEsim: Boolean = false,
    /** Device-level IMS support declared by stock (framework config), whatever the carrier enables. */
    val stockHasVolte: Boolean? = null,
    val stockHasWifiCalling: Boolean? = null,
    /** True when the stock touchscreen firmware offers wake gestures (double tap, sweep). */
    val stockHasWakeGestures: Boolean = false,
    /** True when the OEM sold proprietary accessories for this device (dock, keyboard cover, stylus). */
    val stockHasAccessories: Boolean = false,
)
