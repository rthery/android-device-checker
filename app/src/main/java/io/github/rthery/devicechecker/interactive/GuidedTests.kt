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
