package cn.buffcow.hyperste.toggle

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.logDebug

/**
 * Provides HyperOS USB debugging state reading, switching, and settings navigation.
 *
 * @author qingyu
 * <p>Create on 2026/08/13 11:44</p>
 */
internal class UsbDebuggingQuickToggle : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.DEVELOPER_OPTIONS
    override val titleRes: Int = R.string.quick_toggle_usb_debugging_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openUsbDebuggingSettings)

    override fun readState(host: QuickToggleHost): QuickToggleState {
        val context = host.context
        if (
            !DeveloperOptionsAccess.isAllowed(context) ||
            !DeveloperOptionsAccess.isDeviceProvisioned(context)
        ) {
            return QuickToggleState.UNAVAILABLE
        }

        val checked = isUsbDebuggingEnabled(context)
        val usbDataSignalingAllowed = isUsbDataSignalingAllowed(context)
        val isKeyguardLocked = isKeyguardLocked(context)
        val secondaryText = when {
            !usbDataSignalingAllowed -> host.resolveString(
                R.string.quick_toggle_usb_debugging_disabled_by_admin,
                FALLBACK_DISABLED_BY_ADMIN,
            )

            !checked && isKeyguardLocked -> host.resolveString(
                R.string.quick_toggle_usb_debugging_unlock,
                FALLBACK_UNLOCK_TO_ENABLE,
            )

            else -> null
        }
        return QuickToggleState(
            isAvailable = true,
            isEnabled = usbDataSignalingAllowed &&
                    !ActivityManager.isUserAMonkey() &&
                    (checked || !isKeyguardLocked),
            isChecked = checked,
            secondaryText = secondaryText,
        )
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        val context = host.context
        if (checked) {
            check(DeveloperOptionsAccess.isAllowed(context)) {
                "Developer options are unavailable for the current user"
            }
            check(DeveloperOptionsAccess.isDeviceProvisioned(context)) {
                "USB debugging cannot be enabled before device provisioning completes"
            }
            check(isUsbDataSignalingAllowed(context)) {
                "USB debugging is disabled by device policy"
            }
            check(!ActivityManager.isUserAMonkey()) {
                "USB debugging cannot be enabled during monkey testing"
            }
            check(!isKeyguardLocked(context)) {
                "USB debugging cannot be enabled while the device is locked"
            }
        }
        check(
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                if (checked) SETTING_ENABLED else SETTING_DISABLED,
            ),
        ) {
            "Settings.Global rejected the USB debugging state change"
        }
        logDebug("USB debugging state requested: checked=$checked")
    }

    private fun isUsbDebuggingEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED,
            SETTING_DISABLED,
        ) != SETTING_DISABLED
    }

    private fun isKeyguardLocked(context: Context): Boolean {
        return context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    private fun isUsbDataSignalingAllowed(context: Context): Boolean {
        return context.getSystemService(DevicePolicyManager::class.java)
            ?.isUsbDataSignalingEnabled != false
    }

    private fun openUsbDebuggingSettings(host: QuickToggleHost) {
        host.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                setPackage(SETTINGS_PACKAGE)
                putExtra(EXTRA_FRAGMENT_ARG_KEY, USB_DEBUGGING_PREFERENCE_KEY)
            },
        )
    }

    private fun QuickToggleHost.resolveString(resourceId: Int, fallback: String): String {
        return moduleResources?.getString(resourceId, fallback) ?: fallback
    }

    companion object {
        private const val ID = "usb_debugging"
        private const val FALLBACK_TITLE = "USB debugging"
        private const val FALLBACK_UNLOCK_TO_ENABLE = "Unlock to enable"
        private const val FALLBACK_DISABLED_BY_ADMIN = "Disabled by admin"

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val USB_DEBUGGING_PREFERENCE_KEY = "enable_adb"

        private const val SETTING_DISABLED = 0
        private const val SETTING_ENABLED = 1
    }
}
