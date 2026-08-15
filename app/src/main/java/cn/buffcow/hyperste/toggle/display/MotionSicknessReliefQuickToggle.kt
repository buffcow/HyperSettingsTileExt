package cn.buffcow.hyperste.toggle.display

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.extension.findMethod
import cn.buffcow.hyperste.extension.invokeUnwrapped
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleAction
import cn.buffcow.hyperste.toggle.QuickToggleActionUnavailableException
import cn.buffcow.hyperste.toggle.QuickToggleCategory
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState

/**
 * Controls HyperOS motion sickness relief for the user currently displayed by SystemUI.
 *
 * The implementation mirrors the built-in `carsickness` tile by reading and writing the
 * per-user system setting. Security Center observes that setting and owns the actual visual
 * service lifecycle.
 *
 * @author qingyu
 * <p>Create on 2026/08/15 13:57</p>
 */
internal class MotionSicknessReliefQuickToggle : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.DISPLAY
    override val titleRes: Int = R.string.quick_toggle_motion_sickness_relief_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openMotionSicknessReliefSettings)

    private var backendFailureLogged = false
    private var stateFailureLogged = false
    private val backendResult by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            MotionSicknessReliefBackend()
        }
    }

    override fun readState(host: QuickToggleHost): QuickToggleState {
        return runCatching {
            if (!isSupported(host.context)) {
                return@runCatching QuickToggleState.UNAVAILABLE
            }
            val backend = getBackend() ?: return@runCatching QuickToggleState.UNAVAILABLE
            val currentUserId = backend.getCurrentUserId()
            QuickToggleState(
                isAvailable = true,
                isEnabled = true,
                isChecked = backend.isChecked(host.context.contentResolver, currentUserId),
            )
        }.onSuccess {
            stateFailureLogged = false
        }.onFailure {
            logStateFailureOnce("Failed to read the motion sickness relief state", it)
        }.getOrDefault(QuickToggleState.UNAVAILABLE)
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        check(isSupported(host.context)) {
            "Motion sickness relief is unsupported on this device"
        }
        val backend = getBackend()
            ?: error("Motion sickness relief control is unavailable")
        val currentUserId = backend.getCurrentUserId()
        val contentResolver = host.context.contentResolver
        if (backend.isChecked(contentResolver, currentUserId) == checked) {
            return
        }
        backend.setChecked(contentResolver, currentUserId, checked)
        check(backend.isChecked(contentResolver, currentUserId) == checked) {
            "The requested motion sickness relief state was not applied"
        }
        logDebug(
            "Motion sickness relief state requested: checked=$checked, userId=$currentUserId",
        )
    }

    private fun getBackend(): MotionSicknessReliefBackend? {
        return backendResult.onFailure {
            if (!backendFailureLogged) {
                backendFailureLogged = true
                logError("Failed to initialize motion sickness relief control", it)
            }
        }.getOrNull()
    }

    private fun isSupported(context: Context): Boolean {
        if (
            Settings.Secure.getInt(
                context.contentResolver,
                MOTION_SICKNESS_SUPPORT_KEY,
                NOT_SUPPORTED,
            ) != SUPPORTED
        ) {
            return false
        }
        val applicationInfo = try {
            context.packageManager.getApplicationInfo(
                SECURITY_CENTER_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return applicationInfo.metaData?.getBoolean(SECURITY_CENTER_SUPPORT_METADATA) == true
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openMotionSicknessReliefSettings(host: QuickToggleHost) {
        val intent = Intent().apply {
            setPackage(SECURITY_CENTER_PACKAGE)
            setClassName(SECURITY_CENTER_PACKAGE, MOTION_SICKNESS_SETTINGS_ACTIVITY)
            putExtra(EXTRA_ENTER_WAY, ENTER_WAY_QUICK_SETTINGS)
        }
        if (
            intent.resolveActivityInfo(
                host.context.packageManager,
                PackageManager.MATCH_DEFAULT_ONLY,
            ) == null
        ) {
            throw QuickToggleActionUnavailableException(
                "Motion sickness relief settings activity is unavailable",
            )
        }
        host.startActivity(intent)
    }

    private fun logStateFailureOnce(message: String, throwable: Throwable) {
        if (stateFailureLogged) {
            return
        }
        stateFailureLogged = true
        logError(message, throwable)
    }

    private class MotionSicknessReliefBackend {

        private val getCurrentUserMethod = ActivityManager::class.java.findMethod(
            GET_CURRENT_USER_METHOD,
            0,
        )
        private val getIntForUserMethod = Settings.System::class.java.findMethod(
            GET_INT_FOR_USER_METHOD,
            ContentResolver::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        private val putIntForUserMethod = Settings.System::class.java.findMethod(
            PUT_INT_FOR_USER_METHOD,
            ContentResolver::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )

        fun getCurrentUserId(): Int {
            val userId = (getCurrentUserMethod.invokeUnwrapped(null) as? Number)?.toInt()
                ?: error("ActivityManager.getCurrentUser() returned a non-numeric value")
            check(userId >= 0) {
                "ActivityManager.getCurrentUser() returned an invalid user ID: $userId"
            }
            return userId
        }

        fun isChecked(contentResolver: ContentResolver, userId: Int): Boolean {
            val value = getIntForUserMethod.invokeUnwrapped(
                null,
                contentResolver,
                MOTION_SICKNESS_MODE_KEY,
                MODE_DISABLED,
                userId,
            ) as? Number
                ?: error("Settings.System.getIntForUser() returned a non-numeric value")
            return value.toInt() == MODE_ENABLED
        }

        fun setChecked(contentResolver: ContentResolver, userId: Int, checked: Boolean) {
            val value = if (checked) MODE_ENABLED else MODE_DISABLED
            val applied = putIntForUserMethod.invokeUnwrapped(
                null,
                contentResolver,
                MOTION_SICKNESS_MODE_KEY,
                value,
                userId,
            ) as? Boolean
                ?: error("Settings.System.putIntForUser() returned a non-boolean value")
            check(applied) {
                "Settings.System rejected the motion sickness relief state"
            }
        }
    }

    companion object {
        private const val ID = "motion_sickness_relief"
        private const val FALLBACK_TITLE = "Motion sickness relief"

        private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
        private const val MOTION_SICKNESS_SETTINGS_ACTIVITY =
            "com.miui.carsickness.ui.CarSicknessReliefSettingsActivity"
        private const val EXTRA_ENTER_WAY = "enter_way"
        private const val ENTER_WAY_QUICK_SETTINGS = "quick_setings"
        private const val SECURITY_CENTER_SUPPORT_METADATA = "miui.supportCarSicknessSetting"

        private const val MOTION_SICKNESS_SUPPORT_KEY = "car_sickness_is_support"
        private const val MOTION_SICKNESS_MODE_KEY = "settings_car_sickness_mode"
        private const val NOT_SUPPORTED = 0
        private const val SUPPORTED = 1
        private const val MODE_DISABLED = 0
        private const val MODE_ENABLED = 1

        private const val GET_CURRENT_USER_METHOD = "getCurrentUser"
        private const val GET_INT_FOR_USER_METHOD = "getIntForUser"
        private const val PUT_INT_FOR_USER_METHOD = "putIntForUser"
    }
}
