package io.github.rthery.devicechecker.interactive

import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.annotation.StringRes
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.shell.AppShell
import io.github.rthery.devicechecker.ui.UiText

/** Runs through Shizuku (shell uid); without it the step says so instead of failing silently. */
private fun shellAction(@StringRes label: Int, command: String) = StepAction(label) {
    val shell = privilegedShell ?: return@StepAction "Needs Shizuku. Connect it from the home screen."
    shell.exec(command).output.ifBlank { "(no output)" }
}

private fun appAction(@StringRes label: Int, command: String) = StepAction(label) {
    AppShell.exec(command).output.ifBlank { "(no output)" }
}

private fun guided(id: String, title: String, summary: String, @StringRes intro: Int, vararg steps: Step) =
    InteractiveTest(id, title, summary, manual = true, steps = steps.size) { scope -> GuidedSteps(scope, id, UiText.Res(intro), steps.toList()) }

/** Asks telephony whether each known number counts as an emergency number; nothing is dialed. */
private val emergencyNumbersAction = StepAction(R.string.guided_action_check_emergency_numbers) {
    val ecclist = AppShell.exec("getprop ril.ecclist; getprop ril.ecclist1; getprop ro.ril.ecclist").output
    val numbers = (ecclist.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() } + "112").distinct()
    val telephony = activity.getSystemService(TelephonyManager::class.java)
    fun isEmergency(number: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) telephony.isEmergencyNumber(number)
        else @Suppress("DEPRECATION") PhoneNumberUtils.isEmergencyNumber(number)
    val lines = (numbers + EMERGENCY_CONTROL_NUMBER).map { number ->
        val verdict = runCatching { if (isEmergency(number)) "emergency" else "not emergency" }.getOrElse { "error: ${it.message}" }
        val expected = if (number == EMERGENCY_CONTROL_NUMBER) " (control, should be not emergency)" else ""
        "$number: $verdict$expected"
    }
    "ril.ecclist: ${ecclist.lines().filter { it.isNotBlank() }.joinToString(" | ").ifEmpty { "(empty)" }}\n" + lines.joinToString("\n")
}

private const val EMERGENCY_CONTROL_NUMBER = "1234"

private val usbState = shellAction(R.string.guided_action_show_usb_state, "svc usb getFunctions 2>&1; dumpsys usb | grep -iE 'connected|configured|current_functions|data_role' | head -10")
private val tetherState = shellAction(R.string.guided_action_show_tethering_state, "dumpsys tethering | grep -iE 'tethered|state|upstream' | head -20")

val guidedTests = listOf(
    guided(
        "call.audio", "Phone call audio", "Walks through a real call: earpiece, microphone, speaker, mute.",
        R.string.guided_call_audio_intro,
        Step(R.string.guided_call_audio_step_1, shortcut = Shortcuts.dialer, action = shellAction(R.string.guided_action_show_call_state, "dumpsys telephony.registry | grep -E 'mCallState|mServiceState' | head -4")),
        Step(R.string.guided_call_audio_step_2),
        Step(R.string.guided_call_audio_step_3),
        Step(R.string.guided_call_audio_step_4),
        Step(R.string.guided_call_audio_step_5),
        Step(R.string.guided_call_audio_step_6),
        Step(R.string.guided_call_audio_step_7, action = shellAction(R.string.guided_action_check_rild_and_binder_waits, "logcat -d | grep -cE \"Waiting for service 'media.audio_flinger' on '/dev/vndbinder'\"; pidof rild; dumpsys telephony.registry | grep -m2 mCallState")),
    ),
    guided(
        "emergency.call", "Emergency calling", "Checks emergency calling is reachable, without dialing a real emergency number.",
        R.string.guided_emergency_call_intro,
        Step(R.string.guided_emergency_call_step_1),
        Step(R.string.guided_emergency_call_step_2, shortcut = Shortcuts.emergencyDialer),
        Step(R.string.guided_emergency_call_step_3, action = emergencyNumbersAction),
        Step(R.string.guided_emergency_call_step_4, shortcut = Shortcuts.emergencyDialer),
        Step(R.string.guided_emergency_call_step_5),
    ),
    guided(
        "messaging", "SMS and MMS", "Sends and receives a text and a picture message.",
        R.string.guided_messaging_intro,
        Step(R.string.guided_messaging_step_1, shortcut = Shortcuts.messaging),
        Step(R.string.guided_messaging_step_2),
        Step(R.string.guided_messaging_step_3),
        Step(R.string.guided_messaging_step_4),
    ),
    guided(
        "ims.calls", "VoLTE and VoWiFi", "Checks IMS registers and calls go over LTE and Wi-Fi.",
        R.string.guided_ims_calls_intro,
        Step(R.string.guided_ims_calls_step_1, shortcut = Shortcuts.sims, action = shellAction(R.string.guided_action_show_carrier_ims_config, "dumpsys carrier_config | grep -iE 'carrier_volte_available_bool|carrier_wfc_ims_available_bool|carrier_vt_available_bool' | sort -u | head -12")),
        Step(R.string.guided_ims_calls_step_2, action = shellAction(R.string.guided_action_show_ims_registration, "dumpsys telephony.registry | grep -iE 'ims|mVoiceRegState|rilVoiceRadioTechnology' | head -12")),
        Step(R.string.guided_ims_calls_step_3, shortcut = Shortcuts.dialer),
        Step(R.string.guided_ims_calls_step_4),
    ),
    guided(
        "sim.multi", "Dual SIM and eSIM", "Checks calls, data and SMS on each SIM slot and on an eSIM.",
        R.string.guided_sim_multi_intro,
        Step(R.string.guided_sim_multi_step_1, shortcut = Shortcuts.sims, action = appAction(R.string.guided_action_show_sim_slots, "getprop persist.radio.multisim.config; getprop ro.telephony.sim_slots.count; getprop ro.telephony.default_network")),
        Step(R.string.guided_sim_multi_step_2),
        Step(R.string.guided_sim_multi_step_3),
        Step(R.string.guided_sim_multi_step_4),
        Step(R.string.guided_sim_multi_step_5),
    ),
    guided(
        "tether.wifi", "Wi-Fi hotspot", "Guides hotspot setup and confirms a client gets internet.",
        R.string.guided_tether_wifi_intro,
        Step(R.string.guided_tether_wifi_step_1, shortcut = Shortcuts.tethering),
        Step(R.string.guided_tether_wifi_step_2, action = tetherState),
        Step(R.string.guided_tether_wifi_step_3),
    ),
    guided(
        "tether.usb", "USB tethering", "Switches USB to tethering and checks the computer gets network.",
        R.string.guided_tether_usb_intro,
        Step(R.string.guided_tether_usb_step_1, shortcut = Shortcuts.tethering, action = shellAction(R.string.guided_action_switch_usb_to_tethering, "svc usb setFunctions rndis 2>&1 || svc usb setFunctions ncm 2>&1; sleep 2; svc usb getFunctions 2>&1")),
        Step(R.string.guided_tether_usb_step_2, action = tetherState),
        Step(R.string.guided_tether_usb_step_3),
    ),
    guided(
        "tether.bluetooth", "Bluetooth tethering", "Guides Bluetooth tethering with a paired device.",
        R.string.guided_tether_bluetooth_intro,
        Step(R.string.guided_tether_bluetooth_step_1, shortcut = Shortcuts.tethering),
        Step(R.string.guided_tether_bluetooth_step_2, action = tetherState),
    ),
    guided(
        "usb.mtp", "USB file transfer (MTP)", "Switches USB to file transfer and checks the computer sees the storage.",
        R.string.guided_usb_mtp_intro,
        Step(R.string.guided_usb_mtp_step_1, shortcut = Shortcuts.usb, action = shellAction(R.string.guided_action_switch_usb_to_mtp, "svc usb setFunctions mtp 2>&1; sleep 2; svc usb getFunctions 2>&1")),
        Step(R.string.guided_usb_mtp_step_2, action = usbState),
        Step(R.string.guided_usb_mtp_step_3),
        Step(R.string.guided_usb_mtp_step_4),
    ),
    guided(
        "usb.otg", "USB OTG and USB Ethernet", "Checks a USB drive and a USB Ethernet adapter work through an OTG adapter.",
        R.string.guided_usb_otg_intro,
        Step(R.string.guided_usb_otg_step_1, shortcut = Shortcuts.storage, action = shellAction(R.string.guided_action_show_volumes, "sm list-volumes all 2>&1")),
        Step(R.string.guided_usb_otg_step_2),
        Step(R.string.guided_usb_otg_step_3, action = shellAction(R.string.guided_action_show_interfaces, "ip -brief addr 2>&1 | grep -viE '^lo|dummy' | head -12")),
    ),
    guided(
        "fm.radio", "FM radio", "Guides an FM reception test.",
        R.string.guided_fm_radio_intro,
        Step(R.string.guided_fm_radio_step_1),
        Step(R.string.guided_fm_radio_step_2),
        Step(R.string.guided_fm_radio_step_3),
    ),
    guided(
        "sd.adoptable", "Adoptable storage", "Formats an SD card as internal storage and checks apps can live on it.",
        R.string.guided_sd_adoptable_intro,
        Step(R.string.guided_sd_adoptable_step_1, shortcut = Shortcuts.storage),
        Step(R.string.guided_sd_adoptable_step_2),
        Step(R.string.guided_sd_adoptable_step_3),
        Step(R.string.guided_sd_adoptable_step_4, action = shellAction(R.string.guided_action_show_volumes, "sm list-volumes all 2>&1; sm list-disks adoptable 2>&1")),
    ),
    guided(
        "ota.update", "OTA update", "Guides an over-the-air upgrade through the system updater.",
        R.string.guided_ota_update_intro,
        Step(R.string.guided_ota_update_step_1, action = appAction(R.string.guided_action_show_version, "getprop ro.lineage.version; getprop ro.lineage.build.version")),
        Step(R.string.guided_ota_update_step_2, shortcut = Shortcuts.updater),
        Step(R.string.guided_ota_update_step_3),
    ),
    guided(
        "recovery.boot", "Recovery and sideload", "Reboots to recovery and walks through sideloading.",
        R.string.guided_recovery_boot_intro,
        Step(R.string.guided_recovery_boot_step_1, action = shellAction(R.string.guided_action_reboot_to_recovery_now, "svc power reboot recovery 2>&1")),
        Step(R.string.guided_recovery_boot_step_2),
        Step(R.string.guided_recovery_boot_step_3),
        Step(R.string.guided_recovery_boot_step_4),
    ),
    guided(
        "recovery.addons", "Add-ons in recovery", "Checks that the documented add-ons install from recovery.",
        R.string.guided_recovery_addons_intro,
        Step(R.string.guided_recovery_addons_step_1),
        Step(R.string.guided_recovery_addons_step_2),
    ),
    guided(
        "touch.multi", "Multitouch", "Shows pointer locations to check every finger is tracked and nothing touches by itself.",
        R.string.guided_touch_multi_intro,
        Step(R.string.guided_touch_multi_step_1, action = shellAction(R.string.guided_action_turn_on_pointer_location, "settings put system pointer_location 1 2>&1; settings get system pointer_location")),
        Step(R.string.guided_touch_multi_step_2),
        Step(R.string.guided_touch_multi_step_3),
        Step(R.string.guided_touch_multi_step_4),
        Step(R.string.guided_touch_multi_step_5, action = shellAction(R.string.guided_action_turn_off_pointer_location, "settings put system pointer_location 0 2>&1; settings get system pointer_location")),
    ),
    guided(
        "charging.offmode", "Off-mode charging", "Checks the device shows charging while powered off.",
        R.string.guided_charging_offmode_intro,
        Step(R.string.guided_charging_offmode_step_1),
        Step(R.string.guided_charging_offmode_step_2),
    ),
    guided(
        "charging.cables", "Charging with A-to-C and C-to-C cables", "Checks charging with a USB-A charger and a USB-C charger.",
        R.string.guided_charging_cables_intro,
        Step(R.string.guided_charging_cables_step_1, action = shellAction(R.string.guided_action_show_battery_state, "dumpsys battery 2>&1 | grep -iE 'powered|status|level|current' | head -10")),
        Step(R.string.guided_charging_cables_step_2, action = shellAction(R.string.guided_action_show_battery_state, "dumpsys battery 2>&1 | grep -iE 'powered|status|level|current' | head -10")),
    ),
    guided(
        "factory.reset", "Factory reset", "Resets the device and checks it comes back to setup.",
        R.string.guided_factory_reset_intro,
        Step(R.string.guided_factory_reset_step_1, shortcut = Shortcuts.reset),
        Step(R.string.guided_factory_reset_step_2),
        Step(R.string.guided_factory_reset_step_3, action = appAction(R.string.guided_action_show_encryption_state, "getprop ro.crypto.state; getprop ro.crypto.type")),
    ),
    guided(
        "microg.selfcheck", "microG self-check", "Opens microG and checks its self-check and Exposure Notifications default.",
        R.string.guided_microg_selfcheck_intro,
        Step(R.string.guided_microg_selfcheck_step_1, shortcut = Shortcuts.microg),
        Step(R.string.guided_microg_selfcheck_step_2),
    ),
    guided(
        "privacy.dashboard", "Advanced Privacy", "Opens the privacy settings and checks the Advanced Privacy dashboard.",
        R.string.guided_privacy_dashboard_intro,
        Step(R.string.guided_privacy_dashboard_step_1, shortcut = Shortcuts.privacy),
    ),
    guided(
        "display.extradim", "Extra Dim and color modes", "Toggles Extra Dim and checks it really turns off.",
        R.string.guided_display_extradim_intro,
        Step(R.string.guided_display_extradim_step_1, action = shellAction(R.string.guided_action_read_extra_dim_state, "settings get secure reduce_bright_colors_activated; dumpsys color_display | grep -iE 'reduce bright|activated' | head -4")),
        Step(R.string.guided_display_extradim_step_2, action = shellAction(R.string.guided_action_read_extra_dim_state, "settings get secure reduce_bright_colors_activated")),
        Step(R.string.guided_display_extradim_step_3),
        Step(R.string.guided_display_extradim_step_4, action = shellAction(R.string.guided_action_read_night_light_state, "settings get secure night_display_activated")),
    ),
    guided(
        "stability", "Stability and battery", "Collects drain and wake lock data and asks about screen-of-death issues.",
        R.string.guided_stability_intro,
        Step(R.string.guided_stability_step_1,),
        Step(R.string.guided_stability_step_2, shortcut = Shortcuts.batteryUsage, action = shellAction(R.string.guided_action_show_discharge_stats, "dumpsys batterystats --charged | grep -iE 'Discharge|Screen off|Time on battery' | head -15")),
        Step(R.string.guided_stability_step_3, action = shellAction(R.string.guided_action_show_wake_locks, "dumpsys power | grep -iE 'wake lock|PARTIAL_WAKE_LOCK' | head -20")),
    ),
)
