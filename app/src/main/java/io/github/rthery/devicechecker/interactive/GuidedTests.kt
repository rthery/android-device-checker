package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.shell.AppShell

/** Runs through Shizuku (shell uid); without it the step says so instead of failing silently. */
private fun shellAction(label: String, command: String) = StepAction(label) {
    val shell = privilegedShell ?: return@StepAction "Needs Shizuku. Connect it from the home screen."
    shell.exec(command).output.ifBlank { "(no output)" }
}

private fun appAction(label: String, command: String) = StepAction(label) {
    AppShell.exec(command).output.ifBlank { "(no output)" }
}

private fun guided(id: String, title: String, summary: String, intro: String, vararg steps: Step) =
    InteractiveTest(id, title, summary, manual = true) { scope -> GuidedSteps(scope, intro, steps.toList()) }

private val usbState = shellAction("Show USB state", "svc usb getFunctions 2>&1; dumpsys usb | grep -iE 'connected|configured|current_functions|data_role' | head -10")
private val tetherState = shellAction("Show tethering state", "dumpsys tethering | grep -iE 'tethered|state|upstream' | head -20")

val guidedTests = listOf(
    guided(
        "call.audio", "Phone call audio", "Walks through a real call: earpiece, microphone, speaker, mute.",
        "Call a real number you can talk to (another phone or a voicemail). Answer each step during the call.",
        Step("Place the call. It connects and the call timer runs.", shortcut = Shortcuts.dialer, action = shellAction("Show call state", "dumpsys telephony.registry | grep -E 'mCallState|mServiceState' | head -4")),
        Step("You hear the other side clearly in the earpiece."),
        Step("The other side hears you clearly."),
        Step("Speakerphone: both directions still work."),
        Step("Mute stops the other side hearing you, unmute restores it."),
        Step("With a wired or Bluetooth headset, call audio goes through it."),
        Step("Receive an incoming call: it rings and can be answered.", action = shellAction("Check rild and binder waits", "logcat -d | grep -cE \"Waiting for service 'media.audio_flinger' on '/dev/vndbinder'\"; pidof rild; dumpsys telephony.registry | grep -m2 mCallState")),
    ),
    guided(
        "emergency.call", "Emergency calling", "Checks emergency calling is reachable, without dialing a real emergency number.",
        "Do not dial a real emergency number to test this. Only place a real call if your emergency service or carrier offers a test procedure.",
        Step("The lock screen shows an Emergency call button and it opens the emergency dialer."),
        Step("With a SIM: the dialer recognizes the local emergency number (it shows the emergency call styling as you type it, do not press call)."),
        Step("Without a SIM: the status bar or lock screen shows \"Emergency calls only\" when a network is in range."),
        Step("Read the emergency numbers the device knows", action = appAction("Show emergency numbers", "getprop ril.ecclist; getprop ril.ecclist1; getprop ro.ril.ecclist")),
    ),
    guided(
        "messaging", "SMS and MMS", "Sends and receives a text and a picture message.",
        "You need a SIM with SMS and mobile data, and another phone. MMS needs mobile data on, even when Wi-Fi is connected.",
        Step("Send an SMS to the other phone: it arrives.", shortcut = Shortcuts.messaging),
        Step("Reply from the other phone: the SMS arrives here and shows a notification."),
        Step("Send a picture message (MMS): it arrives with the picture."),
        Step("Receive a picture message: it downloads and the picture shows."),
    ),
    guided(
        "ims.calls", "VoLTE and VoWiFi", "Checks IMS registers and calls go over LTE and Wi-Fi.",
        "Use a SIM whose carrier offers VoLTE and Wi-Fi calling. Turn both on in the SIM settings first.",
        Step("Read the carrier IMS settings", shortcut = Shortcuts.sims, action = shellAction("Show carrier IMS config", "dumpsys carrier_config | grep -iE 'carrier_volte_available_bool|carrier_wfc_ims_available_bool|carrier_vt_available_bool' | sort -u | head -12")),
        Step("IMS is registered: the status bar or SIM status shows VoLTE, or the state below names IMS.", action = shellAction("Show IMS registration", "dumpsys telephony.registry | grep -iE 'ims|mVoiceRegState|rilVoiceRadioTechnology' | head -12")),
        Step("Place a call on LTE with Wi-Fi off: it connects without falling back to 2G or 3G, and data keeps working during the call.", shortcut = Shortcuts.dialer),
        Step("Turn on airplane mode, then Wi-Fi only, and place a Wi-Fi call: it connects and audio works both ways."),
    ),
    guided(
        "sim.multi", "Dual SIM and eSIM", "Checks calls, data and SMS on each SIM slot and on an eSIM.",
        "Skip the parts stock does not support. You need two SIMs, or one SIM and an eSIM profile.",
        Step("Read the SIM slot setup", shortcut = Shortcuts.sims, action = appAction("Show SIM slots", "getprop persist.radio.multisim.config; getprop ro.telephony.sim_slots.count; getprop ro.telephony.default_network")),
        Step("With both SIMs in: each one registers and shows its own signal."),
        Step("Calls, SMS and mobile data work on SIM 1, then on SIM 2 after switching the defaults."),
        Step("With only a physical SIM, then only an eSIM: calls, SMS and data work on each."),
        Step("Adding or deleting an eSIM profile works from the SIM settings."),
    ),
    guided(
        "tether.wifi", "Wi-Fi hotspot", "Guides hotspot setup and confirms a client gets internet.",
        "You need mobile data, or Ethernet, as the upstream, and a second device.",
        Step("Turn on the Wi-Fi hotspot.", shortcut = Shortcuts.tethering),
        Step("The hotspot appears as tethered", action = tetherState),
        Step("Another device connects to the hotspot and can browse."),
    ),
    guided(
        "tether.usb", "USB tethering", "Switches USB to tethering and checks the computer gets network.",
        "Connect the phone to a computer with a data cable. Mobile data must be the upstream.",
        Step("Turn on USB tethering (via Shizuku or the settings screen).", shortcut = Shortcuts.tethering, action = shellAction("Switch USB to tethering", "svc usb setFunctions rndis 2>&1 || svc usb setFunctions ncm 2>&1; sleep 2; svc usb getFunctions 2>&1")),
        Step("The computer gets a network interface and can browse.", action = tetherState),
        Step("Repeat on every other USB port with data (if the device has several)."),
    ),
    guided(
        "tether.bluetooth", "Bluetooth tethering", "Guides Bluetooth tethering with a paired device.",
        "Pair a computer or another phone first.",
        Step("Turn on Bluetooth tethering.", shortcut = Shortcuts.tethering),
        Step("The paired device connects through Bluetooth and can browse.", action = tetherState),
    ),
    guided(
        "usb.mtp", "USB file transfer (MTP)", "Switches USB to file transfer and checks the computer sees the storage.",
        "Connect the phone to a computer with a data cable.",
        Step("Switch USB to file transfer.", shortcut = Shortcuts.usb, action = shellAction("Switch USB to MTP", "svc usb setFunctions mtp 2>&1; sleep 2; svc usb getFunctions 2>&1")),
        Step("The computer shows the phone storage.", action = usbState),
        Step("Copy a file from the computer to the phone and back."),
        Step("Repeat on every other USB port."),
    ),
    guided(
        "usb.otg", "USB OTG and USB Ethernet", "Checks a USB drive and a USB Ethernet adapter work through an OTG adapter.",
        "You need an OTG adapter (or a USB-C drive) and, for the second part, a USB Ethernet adapter.",
        Step("Plug a USB drive in: it mounts and its files open.", shortcut = Shortcuts.storage, action = shellAction("Show volumes", "sm list-volumes all 2>&1")),
        Step("Copy a file to the drive, eject it from the notification, and the file is there on a computer."),
        Step("Plug a USB Ethernet adapter with a cable: the device gets network and can browse with Wi-Fi off.", action = shellAction("Show interfaces", "ip -brief addr 2>&1 | grep -viE '^lo|dummy' | head -12")),
    ),
    guided(
        "fm.radio", "FM radio", "Guides an FM reception test.",
        "FM needs wired headphones as the antenna.",
        Step("Plug wired headphones in."),
        Step("Open the FM radio app and tune to a local station."),
        Step("You hear the station, and switching to speaker works."),
    ),
    guided(
        "sd.adoptable", "Adoptable storage", "Formats an SD card as internal storage and checks apps can live on it.",
        "This erases the SD card. Use a card with nothing you need.",
        Step("Format the card as internal (phone) storage.", shortcut = Shortcuts.storage),
        Step("Move an app or data to the card and the app still opens."),
        Step("Reboot: the card mounts again and the moved app still works."),
        Step("Show storage volumes", action = shellAction("Show volumes", "sm list-volumes all 2>&1; sm list-disks adoptable 2>&1")),
    ),
    guided(
        "ota.update", "OTA update", "Guides an over-the-air upgrade through the system updater.",
        "Needs a newer build published for this device (official or your own update server).",
        Step("Current version", action = appAction("Show version", "getprop ro.lineage.version; getprop ro.lineage.build.version")),
        Step("The Updater lists available builds.", shortcut = Shortcuts.updater),
        Step("Download and install an update: the device reboots into the new build with data kept."),
    ),
    guided(
        "recovery.boot", "Recovery and sideload", "Reboots to recovery and walks through sideloading.",
        "Rebooting closes this app. Save this test after coming back.",
        Step("Reboot to recovery (or use the key combo).", action = shellAction("Reboot to recovery now", "svc power reboot recovery 2>&1")),
        Step("The recovery documented for this ROM starts (LineageOS Recovery on LineageOS-based builds)."),
        Step("\"Apply update > Apply from ADB\" accepts an adb sideload of the build zip."),
        Step("The device boots normally afterwards."),
    ),
    guided(
        "recovery.addons", "Add-ons in recovery", "Checks that the documented add-ons install from recovery.",
        "Use the add-ons the ROM documents for this device (for example a Google apps package).",
        Step("Each documented add-on sideloads in recovery without errors."),
        Step("The device boots and the add-on works."),
    ),
    guided(
        "touch.multi", "Multitouch", "Shows pointer locations to check every finger is tracked and nothing touches by itself.",
        "Turn off Pointer location at the end if you turn it on.",
        Step("Turn on Pointer location.", action = shellAction("Turn on Pointer location", "settings put system pointer_location 1 2>&1; settings get system pointer_location")),
        Step("Put 5 fingers down and move them: the top bar counts every finger and each trail follows its finger."),
        Step("Swipe along every edge and corner: the trail never jumps or breaks."),
        Step("Leave the screen untouched for a minute, including while charging: no touch appears by itself."),
        Step("Turn Pointer location off.", action = shellAction("Turn off Pointer location", "settings put system pointer_location 0 2>&1; settings get system pointer_location")),
    ),
    guided(
        "charging.offmode", "Off-mode charging", "Checks the device shows charging while powered off.",
        "Rebooting closes this app. Save this test after coming back.",
        Step("Power the device off, then plug in a charger: a charging animation or battery level shows."),
        Step("The level rises after a while, and the device boots normally with the power button."),
    ),
    guided(
        "charging.cables", "Charging with A-to-C and C-to-C cables", "Checks charging with a USB-A charger and a USB-C charger.",
        "Skip a cable the device cannot use. Use a known good charger and cable for each.",
        Step("Charge from a USB-A charger: the device shows charging and the current is positive.", action = shellAction("Show battery state", "dumpsys battery 2>&1 | grep -iE 'powered|status|level|current' | head -10")),
        Step("Charge from a USB-C charger with a C-to-C cable: the device shows charging, at the stock speed if the charger supports it.", action = shellAction("Show battery state", "dumpsys battery 2>&1 | grep -iE 'powered|status|level|current' | head -10")),
    ),
    guided(
        "factory.reset", "Factory reset", "Resets the device and checks it comes back to setup.",
        "This erases all data, including this app and its results. Pull the report first, reinstall the app after setup, then record this test.",
        Step("Run the factory reset from settings.", shortcut = Shortcuts.reset),
        Step("The device reboots, wipes and boots into the setup wizard."),
        Step("Setup completes and the device is still encrypted.", action = appAction("Show encryption state", "getprop ro.crypto.state; getprop ro.crypto.type")),
    ),
    guided(
        "microg.selfcheck", "microG self-check", "Opens microG and checks its self-check and Exposure Notifications default.",
        "On a fresh install, before signing in to a Google account.",
        Step("Open microG settings and run the self-check: every item is ticked.", shortcut = Shortcuts.microg),
        Step("Exposure Notifications is off by default."),
    ),
    guided(
        "privacy.dashboard", "Advanced Privacy", "Opens the privacy settings and checks the Advanced Privacy dashboard.",
        "Advanced Privacy ships with every /e/OS build.",
        Step("Advanced Privacy is reachable from the privacy settings and its dashboard opens.", shortcut = Shortcuts.privacy),
    ),
    guided(
        "integrity.basic", "Play Integrity basic", "Checks the build passes Play Integrity basic integrity through microG.",
        "Needs network access and an app that calls the Play Integrity API, such as Play Integrity API Checker from App Lounge.",
        Step("Install Play Integrity API Checker from App Lounge and open it."),
        Step("Run the check: it reports MEETS_BASIC_INTEGRITY. Device and strong integrity are not expected."),
    ),
    guided(
        "display.extradim", "Extra Dim and color modes", "Toggles Extra Dim and checks it really turns off.",
        "Extra Dim once got stuck on. Toggle it from Quick Settings and check the setting follows.",
        Step("Turn Extra Dim on from Quick Settings: the screen gets dimmer.", action = shellAction("Read Extra Dim state", "settings get secure reduce_bright_colors_activated; dumpsys color_display | grep -iE 'reduce bright|activated' | head -4")),
        Step("Turn it off: brightness comes back and the state reads 0.", action = shellAction("Read Extra Dim state", "settings get secure reduce_bright_colors_activated")),
        Step("Repeat on and off 3 times: it never gets stuck."),
        Step("Night Light turns on and off the same way.", action = shellAction("Read Night Light state", "settings get secure night_display_activated")),
    ),
    guided(
        "stability", "Stability and battery", "Collects drain and wake lock data and asks about screen-of-death issues.",
        "Let the device idle overnight with the screen off before the drain steps.",
        Step("Screen off and on 20 times: it always wakes, never stays black.",),
        Step("Overnight idle drain is in line with stock (roughly 1 % per hour or less).", shortcut = Shortcuts.batteryUsage, action = shellAction("Show discharge stats", "dumpsys batterystats --charged | grep -iE 'Discharge|Screen off|Time on battery' | head -15")),
        Step("No app or service holds the device awake.", action = shellAction("Show wake locks", "dumpsys power | grep -iE 'wake lock|PARTIAL_WAKE_LOCK' | head -20")),
    ),
)
