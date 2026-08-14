package cn.buffcow.hyperste.toggle.display

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.UserHandle
import android.os.UserManager
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.extension.hasPermissions
import cn.buffcow.hyperste.extension.invokeUnwrapped
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleCategory
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState

/**
 * Keeps the display awake through a process-scoped SystemUI wake lock.
 *
 * The wake lock remains active after the dialog closes, but it is released when the user turns
 * the screen off, switches users, disables the toggle, or the SystemUI process terminates.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 16:16</p>
 */
@SuppressLint(
    "WakelockTimeout",
    "MissingPermission",
    "PrivateApi",
    "DiscouragedPrivateApi",
)
@Suppress("DEPRECATION")
internal class KeepScreenOnQuickToggle : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.DISPLAY
    override val titleRes: Int = R.string.quick_toggle_keep_screen_on_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null

    private var backendInitializationAttempted = false
    private var backendFailureLogged = false
    private var stateFailureLogged = false
    private var backend: KeepScreenOnBackend? = null

    override fun readState(host: QuickToggleHost): QuickToggleState {
        val activeBackend = getBackend(host.context) ?: return QuickToggleState.UNAVAILABLE
        return runCatching {
            activeBackend.readState()
        }.onSuccess {
            stateFailureLogged = false
        }.onFailure {
            logStateFailureOnce("Failed to read the keep-screen-on state", it)
        }.getOrDefault(QuickToggleState.UNAVAILABLE)
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        val activeBackend = getBackend(host.context)
            ?: error("Keep-screen-on control is unavailable")
        activeBackend.setChecked(checked)
    }

    private fun getBackend(context: Context): KeepScreenOnBackend? {
        if (!backendInitializationAttempted) {
            backendInitializationAttempted = true
            backend = runCatching {
                KeepScreenOnBackend(context)
            }.onFailure {
                if (!backendFailureLogged) {
                    backendFailureLogged = true
                    logError("Failed to initialize keep-screen-on control", it)
                }
            }.getOrNull()
        }
        return backend
    }

    private fun logStateFailureOnce(message: String, throwable: Throwable) {
        if (stateFailureLogged) {
            return
        }
        stateFailureLogged = true
        logError(message, throwable)
    }

    private class KeepScreenOnBackend(context: Context) {

        private val applicationContext = context.applicationContext ?: context
        private val getCurrentUserMethod = ActivityManager::class.java
            .getDeclaredMethod(GET_CURRENT_USER_METHOD)
            .apply {
                isAccessible = true
            }
        private val userHandleOfMethod = UserHandle::class.java
            .getDeclaredMethod(USER_HANDLE_OF_METHOD, Int::class.javaPrimitiveType!!)
            .apply {
                isAccessible = true
            }
        private val createContextAsUserMethod = Context::class.java
            .getMethod(
                CREATE_CONTEXT_AS_USER_METHOD,
                UserHandle::class.java,
                Int::class.javaPrimitiveType!!,
            ).apply {
                isAccessible = true
            }
        private val powerManager: PowerManager
        private val wakeLock: PowerManager.WakeLock
        private val lifecycleReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val reason = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen turned off"
                    ACTION_USER_SWITCHED -> "user switched"
                    else -> return
                }
                releaseForLifecycle(reason)
            }
        }

        init {
            checkPermission(WAKE_LOCK_PERMISSION)
            checkPermission(MANAGE_USERS_PERMISSION)
            checkPermission(INTERACT_ACROSS_USERS_FULL_PERMISSION)
            powerManager = applicationContext.getSystemService(PowerManager::class.java)
                ?: error("PowerManager is unavailable")
            check(powerManager.isWakeLockLevelSupported(PowerManager.FULL_WAKE_LOCK)) {
                "FULL_WAKE_LOCK is not supported on this device"
            }
            wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
            }
            registerLifecycleReceiver()
        }

        fun readState(): QuickToggleState {
            return try {
                val userContext = createCurrentUserContext()
                if (isRestrictedByPolicy(userContext)) {
                    releaseForLifecycle("device policy changed")
                    return QuickToggleState.UNAVAILABLE
                }
                val checked = isHeld()
                QuickToggleState(
                    isAvailable = true,
                    isEnabled = checked || canEnable(userContext),
                    isChecked = checked,
                )
            } catch (throwable: Throwable) {
                releaseForLifecycle("state validation failed")
                throw throwable
            }
        }

        fun setChecked(checked: Boolean) {
            if (checked) {
                val userContext = createCurrentUserContext()
                check(!isRestrictedByPolicy(userContext)) {
                    "Keep screen on is disabled by device policy"
                }
                check(powerManager.isInteractive) {
                    "Keep screen on cannot be enabled while the display is not interactive"
                }
                check(!isKeyguardLocked(userContext)) {
                    "Keep screen on cannot be enabled while the device is locked"
                }
            }
            setWakeLockHeld(checked)
            logDebug("Keep-screen-on state requested: checked=$checked")
        }

        private fun checkPermission(permission: String) {
            check(applicationContext.hasPermissions(permission)) {
                "SystemUI is missing required permission: $permission"
            }
        }

        private fun registerLifecycleReceiver() {
            applicationContext.registerReceiver(
                lifecycleReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(ACTION_USER_SWITCHED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        }

        private fun createCurrentUserContext(): Context {
            val userId = (getCurrentUserMethod.invokeUnwrapped(null) as? Number)?.toInt()
                ?: error("ActivityManager.getCurrentUser() returned a non-numeric value")
            check(userId >= 0) {
                "ActivityManager.getCurrentUser() returned an invalid user ID: $userId"
            }
            val userHandle = userHandleOfMethod.invokeUnwrapped(null, userId) as? UserHandle
                ?: error("UserHandle.of() returned an invalid value")
            return createContextAsUserMethod.invokeUnwrapped(
                applicationContext,
                userHandle,
                CONTEXT_AS_USER_FLAGS,
            ) as? Context ?: error("Context.createContextAsUser() returned an invalid value")
        }

        private fun isRestrictedByPolicy(userContext: Context): Boolean {
            val userManager = userContext.getSystemService(UserManager::class.java)
                ?: error("UserManager is unavailable")
            if (userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT)) {
                return true
            }
            val devicePolicyManager = userContext.getSystemService(DevicePolicyManager::class.java)
                ?: error("DevicePolicyManager is unavailable")
            return devicePolicyManager.getMaximumTimeToLock(null) > NO_MAXIMUM_TIME_TO_LOCK
        }

        private fun canEnable(userContext: Context): Boolean {
            return powerManager.isInteractive && !isKeyguardLocked(userContext)
        }

        private fun isKeyguardLocked(userContext: Context): Boolean {
            return userContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
                ?: error("KeyguardManager is unavailable")
        }

        @Synchronized
        private fun isHeld(): Boolean {
            return wakeLock.isHeld
        }

        @Synchronized
        private fun setWakeLockHeld(held: Boolean) {
            if (held) {
                if (!wakeLock.isHeld) {
                    wakeLock.acquire()
                    check(wakeLock.isHeld) {
                        "PowerManager rejected the keep-screen-on wake lock"
                    }
                }
            } else if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }

        private fun releaseForLifecycle(reason: String) {
            runCatching {
                val wasHeld = isHeld()
                setWakeLockHeld(false)
                wasHeld
            }.onSuccess { released ->
                if (released) {
                    logDebug("Keep screen on disabled because $reason")
                }
            }.onFailure {
                logError("Failed to release the keep-screen-on wake lock after $reason", it)
            }
        }

    }

    companion object {
        private const val ID = "keep_screen_on"
        private const val FALLBACK_TITLE = "Keep screen on"

        private const val WAKE_LOCK_PERMISSION = "android.permission.WAKE_LOCK"
        private const val MANAGE_USERS_PERMISSION = "android.permission.MANAGE_USERS"
        private const val INTERACT_ACROSS_USERS_FULL_PERMISSION =
            "android.permission.INTERACT_ACROSS_USERS_FULL"
        private const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
        private const val WAKE_LOCK_TAG = "HyperSettingsTileExt:KeepScreenOn"

        private const val GET_CURRENT_USER_METHOD = "getCurrentUser"
        private const val USER_HANDLE_OF_METHOD = "of"
        private const val CREATE_CONTEXT_AS_USER_METHOD = "createContextAsUser"
        private const val CONTEXT_AS_USER_FLAGS = 0
        private const val NO_MAXIMUM_TIME_TO_LOCK = 0L
    }
}
