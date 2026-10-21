package io.github.rthery.devicechecker.catalog

import io.github.rthery.devicechecker.catalog.Level.MUST
import io.github.rthery.devicechecker.catalog.Level.MUST_NOT
import io.github.rthery.devicechecker.catalog.Level.SHOULD
import io.github.rthery.devicechecker.catalog.TestMode.ASSISTED
import io.github.rthery.devicechecker.catalog.TestMode.AUTOMATED
import io.github.rthery.devicechecker.catalog.TestMode.INTERACTIVE
import io.github.rthery.devicechecker.catalog.TestMode.MANUAL
import io.github.rthery.devicechecker.catalog.TestMode.OUT_OF_SCOPE

/**
 * Paraphrased and adapted from the LineageOS Device Support Requirements (CC BY 3.0),
 * which itself adapts parts of the Android Compatibility Definition Document.
 */
object RequirementCatalog {

    const val SOURCE_TITLE = "LineageOS Device Support Requirements"
    const val SOURCE_URL = "https://github.com/LineageOS/charter/blob/main/device-support-requirements.md"
    const val SOURCE_LICENSE = "CC BY 3.0"
    const val SOURCE_LICENSE_URL = "https://creativecommons.org/licenses/by/3.0/"


    val all: List<Requirement> = listOf(
        Requirement("general.ownership", Section.GENERAL, MUST, "The maintainer owns the device or a variant of it.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("general.exceptions", Section.GENERAL, MUST, "Any granted exception is documented on the wiki for every affected device.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("qa.factoryreset", Section.GENERAL, SHOULD, "Factory reset wipes the device and boots back to setup.", MANUAL, testIds = listOf("factory.reset")),

        Requirement("hw.audio.media", Section.AUDIO, MUST, "Media playback works.", INTERACTIVE, listOf("audio.devices"), listOf("audio.output", "audio.stress")),
        Requirement("hw.audio.incall", Section.AUDIO, MUST, "Phones (and tablets that can) support in-call audio and speakerphone.", INTERACTIVE, listOf("audio.devices"), listOf("audio.output", "call.audio")),
        Requirement("hw.audio.extras", Section.AUDIO, SHOULD, "Inherent audio features such as echo cancellation and extra microphones work.", ASSISTED, listOf("audio.effects"), listOf("audio.input")),
        Requirement("hw.audio.outputs", Section.AUDIO, MUST, "Every stock audio output works (headphone jack, USB-C audio, Bluetooth).", INTERACTIVE, testIds = listOf("audio.output")),
        Requirement("hw.audio.fm", Section.AUDIO, SHOULD, "FM radio works if stock has it.", MANUAL, testIds = listOf("fm.radio")),

        Requirement("hw.ril.calls", Section.RIL, MUST, "Phone calls and mobile data work if stock has RIL. Data-only devices are exempt from telephony and dialing.", INTERACTIVE, listOf("telephony", "network.cellular"), listOf("call.audio")),
        Requirement(
            "hw.ril.emergency.sim", Section.RIL, MUST, "Emergency calling works with a SIM inserted.", MANUAL, testIds = listOf("emergency.call"),
            note = "Never dial a real emergency number just to test. Use your carrier or emergency service test procedure if one exists.",
        ),
        Requirement("hw.ril.emergency.nosim", Section.RIL, SHOULD, "Emergency calling works without a SIM.", MANUAL, testIds = listOf("emergency.call")),
        Requirement("qa.messaging", Section.RIL, SHOULD, "SMS and MMS send and receive work on devices with RIL.", INTERACTIVE, testIds = listOf("messaging")),
        Requirement("qa.ims", Section.RIL, SHOULD, "VoLTE and VoWiFi calls work where the carrier supports them.", INTERACTIVE, testIds = listOf("ims.calls")),
        Requirement("qa.multisim", Section.RIL, SHOULD, "Dual SIM and eSIM work if stock supports them.", INTERACTIVE, testIds = listOf("sim.multi")),

        Requirement("hw.crypto.hardware", Section.ENCRYPTION, MUST, "Hardware-backed encryption works if stock had it.", ASSISTED, listOf("crypto.status", "keystore.hardware")),
        Requirement("hw.crypto.forced", Section.ENCRYPTION, SHOULD, "Devices that shipped with forced encryption are encrypted by default.", AUTOMATED, listOf("crypto.status")),
        Requirement("hw.crypto.software", Section.ENCRYPTION, MUST, "Software encryption works.", ASSISTED, listOf("crypto.status")),

        Requirement("hw.wifi.works", Section.WIFI, MUST, "Wi-Fi works if stock has it.", AUTOMATED, listOf("wifi.present", "network.wifi", "network.trafficstats")),
        Requirement(
            "hw.wifi.mac", Section.WIFI, MUST, "Wi-Fi uses the same MAC address as stock.", AUTOMATED, listOf("wifi.mac"),
            note = "Android randomizes the MAC per network. Forget saved networks or disable randomization before comparing.",
        ),
        Requirement("hw.wifi.tether", Section.WIFI, MUST, "Hotspot-capable devices support Wi-Fi tethering.", INTERACTIVE, testIds = listOf("tether.wifi")),

        Requirement("hw.usb.mtp", Section.USB, MUST, "MTP file access works on every USB port.", INTERACTIVE, testIds = listOf("usb.mtp")),
        Requirement("hw.usb.tether", Section.USB, MUST, "USB tethering works if stock has it.", INTERACTIVE, testIds = listOf("tether.usb")),
        Requirement("hw.usb.tether.anyport", Section.USB, SHOULD, "USB tethering works on any USB port with data.", INTERACTIVE, testIds = listOf("tether.usb")),
        Requirement("qa.usb.otg", Section.USB, SHOULD, "USB OTG storage and USB Ethernet adapters work if stock supports them.", INTERACTIVE, testIds = listOf("usb.otg")),

        Requirement("hw.gps", Section.GPS, MUST, "GPS works if stock has it.", INTERACTIVE, listOf("gnss.present"), listOf("gnss.fix")),

        Requirement("hw.bt.works", Section.BLUETOOTH, MUST, "Bluetooth works if stock has it.", INTERACTIVE, listOf("bluetooth.present"), listOf("bluetooth.scan")),
        Requirement("hw.bt.mac", Section.BLUETOOTH, MUST, "Bluetooth uses the same MAC address as stock.", AUTOMATED, listOf("bluetooth.mac")),
        Requirement("hw.bt.tether", Section.BLUETOOTH, SHOULD, "Bluetooth tethering works.", INTERACTIVE, testIds = listOf("tether.bluetooth")),
        Requirement(
            "hw.bt.aptx", Section.BLUETOOTH, SHOULD,
            "Newer aptX variants are supported if stock supports them. Older variants MAY be added. aptX Adaptive MUST NOT be added if stock lacks it.",
            ASSISTED, listOf("bluetooth.codecs"),
        ),

        Requirement("hw.camera.frontrear", Section.CAMERA, MUST, "Front and rear cameras work if stock has them.", INTERACTIVE, listOf("camera.list"), listOf("camera.capture")),
        Requirement("hw.camera.all", Section.CAMERA, SHOULD, "On multi-camera devices, every front and rear camera works.", INTERACTIVE, listOf("camera.list"), listOf("camera.capture", "camera.stress")),
        Requirement("hw.camera.hal", Section.CAMERA, MUST, "Every accessible Camera HAL version meets the camera and video requirements.", INTERACTIVE, testIds = listOf("hw.camera.hal")),
        Requirement("hw.video", Section.CAMERA, MUST, "Video recording works on front and rear cameras if stock has it.", INTERACTIVE, testIds = listOf("video.record", "camera.stress")),

        Requirement("hw.codecs", Section.CODECS, MUST, "Hardware encode and decode work for every non-proprietary codec stock supports.", ASSISTED, listOf("codecs.hardware", "codecs.roundtrip")),

        Requirement("hw.display.resolution", Section.DISPLAY, MUST, "Built-in displays run at stock resolution.", AUTOMATED, listOf("display.resolution")),
        Requirement("hw.display.density", Section.DISPLAY, SHOULD, "Built-in displays use stock density.", AUTOMATED, listOf("display.density")),
        Requirement("hw.display.external", Section.DISPLAY, MUST, "Devices without a built-in display support their outputs (for example HDMI).", MANUAL),
        Requirement(
            "hw.display.extra", Section.DISPLAY, SHOULD,
            "Extra non-USB display interfaces, USB display out and HDR10 playback work if stock supports them.",
            ASSISTED, listOf("display.hdr"),
        ),
        Requirement("qa.multitouch", Section.DISPLAY, SHOULD, "The touchscreen tracks every finger and shows no ghost touches.", INTERACTIVE, testIds = listOf("touch.multi")),

        Requirement("hw.nfc", Section.NFC, MUST, "NFC works if stock has it.", INTERACTIVE, listOf("nfc.present"), listOf("nfc.tag")),

        Requirement(
            "hw.fingerprint", Section.FINGERPRINT, MUST,
            "The fingerprint sensor works if stock supports it on Marshmallow or later (SHOULD on older versions).",
            INTERACTIVE, listOf("fingerprint.present"), listOf("fingerprint.auth"),
        ),

        Requirement("hw.ir", Section.IR, SHOULD, "The IR blaster works.", INTERACTIVE, listOf("ir.present"), listOf("ir.transmit")),

        Requirement("hw.sd", Section.SD_CARD, MUST, "SD cards work.", AUTOMATED, listOf("sd.volumes", "sd.readwrite")),
        Requirement("hw.sd.adoptable", Section.SD_CARD, SHOULD, "Adoptable storage works.", INTERACTIVE, testIds = listOf("sd.adoptable")),

        Requirement("hw.sensors.core", Section.SENSORS, MUST, "Accelerometer, gyroscope, proximity and light sensors work if present.", INTERACTIVE, listOf("sensors.core"), listOf("sensors.motion")),
        Requirement("hw.sensors.other", Section.SENSORS, SHOULD, "Other sensors supported by stock work.", ASSISTED, listOf("sensors.list")),

        Requirement("qa.battery.capacity", Section.BATTERY, SHOULD, "The battery reports a capacity in line with the device spec.", ASSISTED, listOf("battery.capacity")),
        Requirement("qa.charging.offmode", Section.BATTERY, SHOULD, "Off-mode charging shows the battery state while the device is off.", INTERACTIVE, testIds = listOf("charging.offmode")),
        Requirement("qa.charging.cables", Section.BATTERY, SHOULD, "Charging works with USB-A to C and C to C cables.", INTERACTIVE, testIds = listOf("charging.cables")),

        Requirement("hw.accessories", Section.ACCESSORIES, SHOULD, "Proprietary accessories work.", MANUAL),
        Requirement("hw.deviations", Section.ACCESSORIES, MUST, "Every hardware deviation from stock is documented on the wiki with a justification.", OUT_OF_SCOPE, rom = Rom.LINEAGE),

        Requirement("sw.tree.codename", Section.DEVICE_TREE, MUST, "The codename is unique among officially supported devices.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("sw.tree.makefile", Section.DEVICE_TREE, MUST, "The tree has a Lineage makefile declaring lineage_<device>.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("sw.tree.dependencies", Section.DEVICE_TREE, MUST, "lineage.dependencies is supported and only references LineageOS repositories.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("sw.buildtype", Section.DEVICE_TREE, MUST, "The build type is userdebug.", AUTOMATED, listOf("build.type"), rom = Rom.LINEAGE),
        Requirement("sw.binder64", Section.DEVICE_TREE, MUST, "The 64-bit Binder API is used.", AUTOMATED, listOf("binder.64bit", "binder.vndbinder")),
        Requirement("sw.pie", Section.DEVICE_TREE, MUST_NOT, "No non-PIE binaries are used.", OUT_OF_SCOPE),

        Requirement("sw.kernel.prebuilt", Section.KERNEL, MUST_NOT, "Non-GKI devices do not ship a prebuilt kernel. GKI devices build every feasible module from source.", OUT_OF_SCOPE),
        Requirement("sw.kernel.wakegestures", Section.KERNEL, MUST_NOT, "No software wake gestures without touchscreen firmware support (software double-tap is fine when firmware supports single-tap).", MANUAL),
        Requirement("sw.kernel.fastcharge", Section.KERNEL, MUST_NOT, "No forced fast charging that violates the USB specification.", ASSISTED, listOf("charging.usb")),
        Requirement("sw.kernel.clocks", Section.KERNEL, MUST, "CPU and GPU clocks and voltages match stock binning or SoC vendor documentation.", ASSISTED, listOf("kernel.clocks")),
        Requirement("sw.kernel.registers", Section.KERNEL, MUST_NOT, "No hardware register manipulation that stock does not do.", OUT_OF_SCOPE),
        Requirement("sw.kernel.ksm", Section.KERNEL, MUST_NOT, "No custom KSM drivers.", OUT_OF_SCOPE),
        Requirement("sw.kernel.governors", Section.KERNEL, MUST, "Only approved CPU governors ship.", AUTOMATED, listOf("kernel.governors")),
        Requirement("sw.kernel.iosched", Section.KERNEL, MUST, "Only approved I/O schedulers ship.", AUTOMATED, listOf("kernel.ioschedulers")),
        Requirement("sw.kernel.hotplug", Section.KERNEL, MUST, "Hotplug drivers come from the OEM or SoC vendor.", OUT_OF_SCOPE),
        Requirement(
            "sw.exfat", Section.KERNEL, MUST,
            "exFAT is only supported on kernels below 5.7 when stock had it, or on 5.7+ through the mainline or vendor driver.",
            AUTOMATED, listOf("kernel.exfat"),
        ),
        Requirement(
            "sw.kernel.release", Section.KERNEL, SHOULD,
            "The kernel is accepted for the LineageOS release: 4.4 and 4.9 only up to 22.2, and 4.14 or later with eBPF backports equivalent to 5.4 from 23.0.",
            AUTOMATED, listOf("kernel.release"), lineageOnly = true,
        ),

        Requirement("sw.selinux", Section.SECURITY, MUST, "SELinux is Enforcing.", AUTOMATED, listOf("selinux", "selinux.denials")),
        Requirement("sw.verity.system", Section.SECURITY, MUST, "Verity is disabled on the system image for userdebug builds.", AUTOMATED, listOf("verity")),
        Requirement("sw.verity.vendor", Section.SECURITY, SHOULD, "Vendor verity is supported.", MANUAL),
        Requirement("sw.su", Section.SECURITY, MUST_NOT, "The build does not ship su.", AUTOMATED, listOf("su.absent")),
        Requirement("sw.frp", Section.SECURITY, SHOULD, "Factory Reset Protection works with Google apps installed, if stock supports FRP.", ASSISTED, listOf("frp")),
        Requirement("sw.playintegrity", Section.SECURITY, MUST_NOT, "Play Integrity responses are not altered.", ASSISTED, listOf("build.fingerprint")),
        Requirement("eos.buildtype", Section.DEVICE_TREE, SHOULD, "Builds released to users are of type user. userdebug is for validation.", AUTOMATED, listOf("eos.buildtype"), rom = Rom.EOS),
        Requirement("eos.dns", Section.SECURITY, SHOULD, "Google's DNS servers are not used by default.", AUTOMATED, listOf("eos.dns"), rom = Rom.EOS),
        Requirement("eos.integrity", Section.SECURITY, SHOULD, "Play Integrity reaches at least basic integrity.", INTERACTIVE, testIds = listOf("integrity.basic"), rom = Rom.EOS),
        Requirement(
            "eos.verifiedboot", Section.SECURITY, SHOULD, "Official builds on lockable devices boot with the bootloader relocked and verity on.",
            AUTOMATED, listOf("eos.verifiedboot"), rom = Rom.EOS,
        ),
        Requirement("sw.cve", Section.SECURITY, MUST, "High-profile publicized vulnerabilities are patched. Regular kernel and dependency patches SHOULD be applied.", ASSISTED, listOf("security.patch")),

        Requirement("sw.updater", Section.UPDATES, MUST, "Upgrades work through the LineageOS Updater and the documented recovery method.", INTERACTIVE, listOf("updater.present"), listOf("ota.update", "recovery.boot"), rom = Rom.LINEAGE),
        Requirement("qol.recovery", Section.UPDATES, MUST, "Official LineageOS Recovery works (17.0+) and is the documented default (18.1+), or another zip install method is documented.", INTERACTIVE, testIds = listOf("recovery.boot"), rom = Rom.LINEAGE),
        Requirement("qol.addons", Section.UPDATES, MUST, "Add-ons listed on the wiki install through LineageOS Recovery (19.0+).", INTERACTIVE, testIds = listOf("recovery.addons"), rom = Rom.LINEAGE),

        Requirement("eos.ota", Section.UPDATES, SHOULD, "Updates install over the air through the /e/OS updater.", INTERACTIVE, testIds = listOf("ota.update"), rom = Rom.EOS),
        Requirement("eos.install", Section.UPDATES, SHOULD, "A fresh install that follows the device's install guide boots.", INTERACTIVE, testIds = listOf("recovery.boot"), rom = Rom.EOS),

        Requirement("sw.blobs.extract", Section.BLOBS, MUST, "The extraction script reproduces the exact binaries from an existing LineageOS install.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("sw.blobs.sources", Section.BLOBS, MUST, "Blob sources follow the preference order and proprietary file lists note where files come from.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("sw.blobs.megvii", Section.BLOBS, MUST_NOT, "No blobs from Megvii Technology or SenseTime are included.", AUTOMATED, listOf("blobs.megvii")),

        Requirement("sw.firmware", Section.VENDOR, MUST, "Known-working firmware versions are asserted when some versions fail. A/B devices keep both slots on good firmware.", MANUAL),
        Requirement("sw.vendor.assert", Section.VENDOR, MUST, "Non-A/B devices with a vendor partition assert vendor versions at flash time.", OUT_OF_SCOPE),
        Requirement("sw.vendor.ab", Section.VENDOR, MUST, "A/B devices with a vendor partition build a vendor image or document firmware and slot sync on the wiki.", OUT_OF_SCOPE),
        Requirement("sw.vendor.modified", Section.VENDOR, MUST_NOT, "Modified prebuilt vendor images are not required.", OUT_OF_SCOPE),
        Requirement("sw.vendor.gsi", Section.VENDOR, SHOULD, "Treble devices verify basic hardware with an AOSP GSI.", MANUAL),

        Requirement("sw.livedisplay", Section.FEATURES, SHOULD, "In-kernel LiveDisplay color adjustment is supported.", AUTOMATED, listOf("livedisplay"), lineageOnly = true),
        Requirement("sw.browser", Section.FEATURES, MUST, "Jelly or another LineageOS-sourced browser ships.", AUTOMATED, listOf("browser.jelly"), rom = Rom.LINEAGE),
        Requirement("eos.apps", Section.FEATURES, MUST, "App Lounge and Advanced Privacy ship, as in every /e/OS build.", AUTOMATED, listOf("eos.apps"), rom = Rom.EOS),
        Requirement("eos.browser", Section.FEATURES, MUST, "The /e/OS browser ships.", AUTOMATED, listOf("eos.browser"), rom = Rom.EOS),
        Requirement("eos.privacy", Section.FEATURES, SHOULD, "Advanced Privacy opens from the privacy settings.", MANUAL, testIds = listOf("privacy.dashboard"), rom = Rom.EOS),
        Requirement(
            "eos.microg", Section.FEATURES, SHOULD, "microG ships, its self-check is all green, and Exposure Notifications is off by default.",
            INTERACTIVE, listOf("eos.microg"), listOf("microg.selfcheck"), rom = Rom.EOS,
        ),
        Requirement("sw.deviations", Section.FEATURES, MUST, "Software deviations from same-type devices are approved and documented on the wiki.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("qol.stability", Section.FEATURES, MUST, "No screen-of-death issues and no abnormal battery drain.", INTERACTIVE, listOf("crashes.boot"), listOf("stability", "display.extradim")),

        Requirement("qol.authorship", Section.PROJECT, MUST, "Adapted or copied commits credit their original authors.", OUT_OF_SCOPE, lineageOnly = true),
        Requirement("qol.copyright", Section.PROJECT, MUST, "Copyright headers follow the LineageOS rules and upstream copyrights are kept.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("qol.workflow", Section.PROJECT, SHOULD, "Force pushes are avoided, and a backup branch is created before any force push.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("qol.gitlab", Section.PROJECT, MUST, "The maintainer has a GitLab account and triages bug reports.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
        Requirement("qol.licensing", Section.PROJECT, MUST, "Kernel contributions are GPLv2, Android contributions SHOULD be Apache 2.0.", OUT_OF_SCOPE, lineageOnly = true),
        Requirement("qol.wiki", Section.PROJECT, MUST, "The wiki has valid install instructions and lists hardware and software deviations.", OUT_OF_SCOPE, rom = Rom.LINEAGE),
    )

    val byId: Map<String, Requirement> = all.associateBy { it.id }

    init {
        check(byId.size == all.size) { "Duplicate requirement id" }
    }
}
