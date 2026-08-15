package cn.buffcow.hyperste.toggle

import cn.buffcow.hyperste.toggle.developer.UsbDebuggingQuickToggle
import cn.buffcow.hyperste.toggle.developer.WirelessDebuggingQuickToggle
import cn.buffcow.hyperste.toggle.display.KeepScreenOnQuickToggle
import cn.buffcow.hyperste.toggle.display.MotionSicknessReliefQuickToggle
import cn.buffcow.hyperste.toggle.google.GoogleServicesQuickToggle
import cn.buffcow.hyperste.toggle.mishare.MiShareQuickToggle
import cn.buffcow.hyperste.toggle.usb.UsbOtgQuickToggle
import cn.buffcow.hyperste.toggle.usb.UsbTetheringQuickToggle

/**
 * Owns the single ordered registry shared by quick-control and feature-management dialogs.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 17:50</p>
 */
internal class QuickToggleRegistry(classLoader: ClassLoader) {

    val entries: List<QuickToggle> = listOf(
        GoogleServicesQuickToggle(
            classLoader = classLoader,
        ),
        MiShareQuickToggle(),
        UsbOtgQuickToggle(
            classLoader = classLoader,
        ),
        UsbTetheringQuickToggle(
            classLoader = classLoader,
        ),
        KeepScreenOnQuickToggle(),
        MotionSicknessReliefQuickToggle(),
        UsbDebuggingQuickToggle(),
        WirelessDebuggingQuickToggle(
            classLoader = classLoader,
        ),
    ).also { toggles ->
        check(toggles.map(QuickToggle::id).distinct().size == toggles.size) {
            "Quick toggle IDs must be unique"
        }
    }
}
