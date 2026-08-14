package cn.buffcow.hyperste.toggle

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings

/**
 * Centralizes access checks for developer-options quick toggles.
 *
 * @author qingyu
 * <p>Create on 2026/08/13 11:44</p>
 */
internal object DeveloperOptionsAccess {

    /**
     * Returns whether developer features are available to the current user.
     *
     * Access requires the developer-options master switch, an administrator user, and no
     * debugging restriction applied to the current user.
     */
    fun isAllowed(context: Context): Boolean {
        val developmentSettingsEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            if (Build.TYPE == BUILD_TYPE_ENG) SETTING_ENABLED else SETTING_DISABLED,
        ) != SETTING_DISABLED
        if (!developmentSettingsEnabled) {
            return false
        }
        val userManager = context.getSystemService(UserManager::class.java) ?: return false
        return userManager.run {
            isAdminUser && !hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
    }

    /** Returns whether initial device provisioning has completed. */
    fun isDeviceProvisioned(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVICE_PROVISIONED,
            SETTING_DISABLED,
        ) != SETTING_DISABLED
    }

    private const val BUILD_TYPE_ENG = "eng"
    private const val SETTING_DISABLED = 0
    private const val SETTING_ENABLED = 1
}
