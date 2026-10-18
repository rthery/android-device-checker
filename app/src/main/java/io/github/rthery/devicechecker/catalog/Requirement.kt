package io.github.rthery.devicechecker.catalog

enum class Level(val label: String) {
    MUST("MUST"),
    MUST_NOT("MUST NOT"),
    SHOULD("SHOULD"),
    SHOULD_NOT("SHOULD NOT"),
    MAY("MAY"),
}

enum class TestMode(val label: String) {
    /** The app decides pass or fail on its own. */
    AUTOMATED("Automated"),

    /** The app gathers data, a human compares it with stock. */
    ASSISTED("Assisted"),

    /** Needs the tester to do something on the device (play audio, take a photo, place a call). */
    INTERACTIVE("Interactive"),

    MANUAL("Manual"),

    /** About the source tree, the wiki or the maintainer, not the running build. */
    OUT_OF_SCOPE("Not on device"),
}

enum class Section(val title: String) {
    GENERAL("General"),
    AUDIO("Audio"),
    RIL("RIL"),
    ENCRYPTION("Encryption"),
    WIFI("Wi-Fi"),
    USB("USB"),
    GPS("GPS"),
    BLUETOOTH("Bluetooth"),
    CAMERA("Camera and video"),
    CODECS("Codecs"),
    DISPLAY("Display"),
    NFC("NFC"),
    FINGERPRINT("Fingerprint"),
    IR("IR"),
    SD_CARD("SD card"),
    SENSORS("Sensors"),
    ACCESSORIES("Accessories and deviations"),
    DEVICE_TREE("Device tree and build"),
    KERNEL("Kernel"),
    SECURITY("Security"),
    UPDATES("Updates and recovery"),
    BLOBS("Proprietary files"),
    VENDOR("Firmware and vendor images"),
    FEATURES("Features"),
    PROJECT("Project hygiene"),
}

data class Requirement(
    val id: String,
    val section: Section,
    val level: Level,
    val text: String,
    val mode: TestMode,
    val checkIds: List<String> = emptyList(),
    val testIds: List<String> = emptyList(),
    val note: String? = null,
    /** Only meaningful on LineageOS-based builds; N/A elsewhere. */
    val lineageOnly: Boolean = false,
    /** Name of the device pack that added it; null for built-in requirements. */
    val source: String? = null,
)
