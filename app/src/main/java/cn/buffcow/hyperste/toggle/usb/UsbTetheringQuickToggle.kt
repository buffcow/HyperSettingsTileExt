package cn.buffcow.hyperste.toggle.usb

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.TetheringManager
import android.os.Environment
import android.os.SystemClock
import android.os.UserManager
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleAction
import cn.buffcow.hyperste.toggle.QuickToggleActionUnavailableException
import cn.buffcow.hyperste.toggle.QuickToggleCategory
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.regex.Pattern

/**
 * Provides HyperOS USB tethering state reading, asynchronous switching, and settings navigation.
 *
 * @author qingyu
 * <p>Create on 2026/08/13 16:03</p>
 */
@SuppressLint(
    "NewApi",
    "MissingPermission",
    "WrongConstant",
    "PrivateApi",
    "BlockedPrivateApi",
    "DiscouragedPrivateApi",
)
internal class UsbTetheringQuickToggle(
    private val classLoader: ClassLoader,
) : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.CONNECTIVITY
    override val titleRes: Int = R.string.quick_toggle_usb_tethering_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openTetheringSettings)

    private var backendInitializationAttempted = false
    private var backendFailureLogged = false
    private var stateFailureLogged = false
    private var backend: UsbTetheringBackend? = null

    override fun readState(host: QuickToggleHost): QuickToggleState {
        val activeBackend = getBackend(host.context) ?: return QuickToggleState.UNAVAILABLE
        return runCatching {
            activeBackend.readState(host.context).toQuickToggleState(host)
        }.onSuccess {
            stateFailureLogged = false
        }.onFailure {
            logStateFailureOnce("Failed to read the USB tethering state", it)
        }.getOrDefault(QuickToggleState.UNAVAILABLE)
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        val activeBackend = getBackend(host.context)
            ?: error("USB tethering control is unavailable")
        activeBackend.requestState(host.context, checked)
    }

    private fun getBackend(context: Context): UsbTetheringBackend? {
        if (!backendInitializationAttempted) {
            backendInitializationAttempted = true
            backend = runCatching {
                UsbTetheringBackend(classLoader, context)
            }.onFailure {
                if (!backendFailureLogged) {
                    backendFailureLogged = true
                    logError("Failed to initialize USB tethering control", it)
                }
            }.getOrNull()
        }
        return backend
    }

    private fun BackendState.toQuickToggleState(host: QuickToggleHost): QuickToggleState {
        return QuickToggleState(
            isAvailable = isAvailable,
            isEnabled = isEnabled,
            isChecked = isChecked,
            secondaryText = message?.let { stateMessage ->
                val (resourceId, fallback) = when (stateMessage) {
                    StateMessage.STARTING -> R.string.quick_toggle_usb_tethering_starting to
                            FALLBACK_STARTING

                    StateMessage.STOPPING -> R.string.quick_toggle_usb_tethering_stopping to
                            FALLBACK_STOPPING

                    StateMessage.START_FAILED -> R.string.quick_toggle_usb_tethering_start_failed to
                            FALLBACK_START_FAILED

                    StateMessage.STOP_FAILED -> R.string.quick_toggle_usb_tethering_stop_failed to
                            FALLBACK_STOP_FAILED

                    StateMessage.USB_STORAGE_IN_USE ->
                        R.string.quick_toggle_usb_tethering_storage_in_use to
                                FALLBACK_USB_STORAGE_IN_USE

                    StateMessage.UNAVAILABLE -> R.string.quick_toggle_usb_tethering_unavailable to
                            FALLBACK_UNAVAILABLE
                }
                host.moduleResources?.getString(resourceId, fallback) ?: fallback
            },
        )
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openTetheringSettings(host: QuickToggleHost) {
        val intent = Intent(TETHER_SETTINGS_ACTION).apply {
            setClassName(SETTINGS_PACKAGE, TETHER_SETTINGS_ACTIVITY)
            putExtra(EXTRA_FRAGMENT_ARG_KEY, USB_TETHERING_PREFERENCE_KEY)
        }
        if (intent.resolveActivityInfo(host.context.packageManager, 0) == null) {
            throw QuickToggleActionUnavailableException(
                "USB tethering settings activity is unavailable",
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

    private data class BackendState(
        val isAvailable: Boolean,
        val isEnabled: Boolean,
        val isChecked: Boolean,
        val message: StateMessage? = null,
    ) {

        companion object {
            val UNAVAILABLE = BackendState(
                isAvailable = false,
                isEnabled = false,
                isChecked = false,
            )
        }
    }

    private enum class StateMessage {
        STARTING,
        STOPPING,
        START_FAILED,
        STOP_FAILED,
        USB_STORAGE_IN_USE,
        UNAVAILABLE,
    }

    private enum class RequestAction {
        START,
        STOP;

        val targetState: Boolean
            get() = this == START
    }

    private data class PendingRequest(
        val action: RequestAction,
        val startedAtMillis: Long,
    ) {
        val targetState: Boolean
            get() = action.targetState
    }

    private data class RecentFailure(
        val action: RequestAction,
        val occurredAtMillis: Long,
    )

    private data class InterfaceSnapshot(
        val available: List<String>,
        val tethered: List<String>,
        val errored: List<String>,
        val canReadLastError: Boolean,
    )

    private class UsbTetheringBackend(
        classLoader: ClassLoader,
        context: Context,
    ) {

        private val tetheringManager = context.getSystemService(TetheringManager::class.java)
            ?: error("TetheringManager is unavailable")
        private val tetheringManagerClass = classLoader.loadClass(TETHERING_MANAGER_CLASS)
        private val getTetherableUsbRegexsMethod = tetheringManagerClass.getDeclaredMethod(
            GET_TETHERABLE_USB_REGEXS_METHOD,
        ).apply {
            isAccessible = true
        }
        private val getTetheredIfacesMethod = findOptionalMethod(GET_TETHERED_IFACES_METHOD)
        private val getTetherableIfacesMethod = findOptionalMethod(GET_TETHERABLE_IFACES_METHOD)
        private val getTetheringErroredIfacesMethod = findOptionalMethod(
            GET_TETHERING_ERRORED_IFACES_METHOD,
        )
        private val getLastTetherErrorMethod = findOptionalMethod(
            GET_LAST_TETHER_ERROR_METHOD,
            String::class.java,
        )
        private val stopTetheringMethod = tetheringManagerClass.getDeclaredMethod(
            STOP_TETHERING_METHOD,
            Int::class.javaPrimitiveType!!,
        ).apply {
            isAccessible = true
        }
        private val shouldShowEntitlementUiMethod =
            TetheringManager.TetheringRequest.Builder::class.java.getDeclaredMethod(
                SET_SHOULD_SHOW_ENTITLEMENT_UI_METHOD,
                Boolean::class.javaPrimitiveType!!,
            ).apply {
                isAccessible = true
            }
        private val currentUserMethod = classLoader.loadClass(CROSS_USER_UTILS_CLASS)
            .getDeclaredMethod(GET_CURRENT_USER_METHOD)
            .apply {
                isAccessible = true
            }
        private val staticRestrictionMethod = classLoader.loadClass(
            ENTERPRISE_RESTRICTIONS_HELPER_CLASS,
        ).getDeclaredMethod(
            HAS_RESTRICTION_METHOD,
            Context::class.java,
            String::class.java,
        ).apply {
            isAccessible = true
        }
        private val restrictionHelper: Any
        private val isRestrictionMethod: Method
        private var pendingRequest: PendingRequest? = null
        private var recentFailure: RecentFailure? = null

        init {
            val restrictionHelperClass = classLoader.loadClass(
                ENTERPRISE_RESTRICTIONS_HELPER_STUB_CLASS,
            )
            restrictionHelper = restrictionHelperClass.getDeclaredMethod(GET_INSTANCE_METHOD).run {
                isAccessible = true
                invokePlatform(null) ?: error("RestrictionsHelperStub.getInstance() returned null")
            }
            isRestrictionMethod = classLoader.loadClass(ENTERPRISE_RESTRICTIONS_INTERFACE_CLASS)
                .getDeclaredMethod(IS_RESTRICTION_METHOD, String::class.java)
                .apply {
                    isAccessible = true
                }
        }

        fun readState(context: Context): BackendState {
            if (!isAvailableForCurrentUser(context)) {
                return BackendState.UNAVAILABLE
            }
            val usbRegexs = getStringArray(getTetherableUsbRegexsMethod).toList()
            if (usbRegexs.isEmpty()) {
                return BackendState.UNAVAILABLE
            }

            val snapshot = readInterfaceSnapshot(context)
            val isTethered = snapshot.tethered.any { interfaceName ->
                interfaceName.matchesAny(usbRegexs)
            }
            val isUsbConnected = isUsbConnected(context)
            val now = SystemClock.elapsedRealtime()
            updatePendingState(isTethered, isUsbConnected, now)
            if (recentFailure?.action?.targetState == isTethered) {
                recentFailure = null
            }
            expireRecentFailure(now)

            if (isTethered) {
                return BackendState(
                    isAvailable = true,
                    isEnabled = pendingRequest == null,
                    isChecked = true,
                    message = pendingRequest?.let { StateMessage.STOPPING }
                        ?: recentFailure?.toMessage(),
                )
            }
            pendingRequest?.let { pending ->
                return BackendState(
                    isAvailable = true,
                    isEnabled = false,
                    isChecked = false,
                    message = if (pending.action == RequestAction.START) {
                        StateMessage.STARTING
                    } else {
                        StateMessage.STOPPING
                    },
                )
            }
            if (!isUsbConnected) {
                recentFailure = null
                return BackendState(
                    isAvailable = true,
                    isEnabled = false,
                    isChecked = false,
                )
            }

            val isUsbStorageShared = Environment.getExternalStorageState() == Environment.MEDIA_SHARED
            val hasUsbError = hasUsbError(snapshot, usbRegexs)
            val baseEnabled = !isUsbStorageShared && !hasUsbError
            return BackendState(
                isAvailable = true,
                isEnabled = baseEnabled,
                isChecked = false,
                message = recentFailure?.toMessage() ?: when {
                    hasUsbError -> StateMessage.UNAVAILABLE
                    isUsbStorageShared -> StateMessage.USB_STORAGE_IN_USE
                    else -> null
                },
            )
        }

        fun requestState(context: Context, checked: Boolean) {
            val currentState = readState(context)
            check(currentState.isAvailable) {
                "USB tethering is unavailable for the current user"
            }
            check(currentState.isEnabled) {
                "USB tethering cannot be changed in the current state"
            }
            if (currentState.isChecked == checked) {
                return
            }

            val action = if (checked) RequestAction.START else RequestAction.STOP
            pendingRequest = PendingRequest(
                action = action,
                startedAtMillis = SystemClock.elapsedRealtime(),
            )
            recentFailure = null
            try {
                if (checked) {
                    startTethering(context)
                } else {
                    stopTetheringMethod.invokePlatform(tetheringManager, TETHERING_USB)
                }
                logDebug("USB tethering state requested: checked=$checked")
            } catch (failure: Throwable) {
                recordFailure(action)
                throw failure
            }
        }

        private fun startTethering(context: Context) {
            val requestBuilder = TetheringManager.TetheringRequest.Builder(TETHERING_USB)
            shouldShowEntitlementUiMethod.invokePlatform(requestBuilder, true)
            tetheringManager.startTethering(
                requestBuilder.build(),
                context.mainExecutor,
                object : TetheringManager.StartTetheringCallback {
                    override fun onTetheringStarted() {
                        logDebug("USB tethering start request was accepted")
                    }

                    override fun onTetheringFailed(error: Int) {
                        if (pendingRequest?.action != RequestAction.START) {
                            return
                        }
                        recordFailure(RequestAction.START)
                        logError("USB tethering failed to start: errorCode=$error", null)
                    }
                },
            )
        }

        private fun updatePendingState(
            isTethered: Boolean,
            isUsbConnected: Boolean,
            now: Long,
        ) {
            val pending = pendingRequest ?: return
            if (!isUsbConnected) {
                pendingRequest = null
                recentFailure = null
                return
            }
            if (isTethered == pending.targetState) {
                pendingRequest = null
                recentFailure = null
                logDebug("USB tethering reached the requested state: checked=$isTethered")
                return
            }
            if (now - pending.startedAtMillis >= REQUEST_TIMEOUT_MS) {
                recordFailure(pending.action, now)
                logError(
                    "USB tethering state request timed out: checked=${pending.targetState}",
                    null,
                )
            }
        }

        private fun expireRecentFailure(now: Long) {
            recentFailure = recentFailure?.takeIf { failure ->
                now - failure.occurredAtMillis < FAILURE_MESSAGE_DURATION_MS
            }
        }

        private fun recordFailure(
            action: RequestAction,
            now: Long = SystemClock.elapsedRealtime(),
        ) {
            pendingRequest = null
            recentFailure = RecentFailure(action, now)
        }

        private fun RecentFailure.toMessage(): StateMessage {
            return if (action == RequestAction.START) {
                StateMessage.START_FAILED
            } else {
                StateMessage.STOP_FAILED
            }
        }

        private fun isAvailableForCurrentUser(context: Context): Boolean {
            if (!hasRequiredPermissions(context) || ActivityManager.isUserAMonkey()) {
                return false
            }
            val currentUserId = (currentUserMethod.invokePlatform(null) as? Number)?.toInt()
                ?: error("CrossUserUtils.getCurrentUserId() returned a non-numeric value")
            if (currentUserId != SYSTEM_USER_ID) {
                return false
            }
            val userManager = context.getSystemService(UserManager::class.java)
                ?: error("UserManager is unavailable")
            return !userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING) &&
                    !isRestrictedByEnterprise(context)
        }

        private fun hasRequiredPermissions(context: Context): Boolean {
            return with(context) {
                checkSelfPermission(TETHER_PRIVILEGED_PERMISSION) ==
                        PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(NETWORK_SETTINGS_PERMISSION) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        private fun isRestrictedByEnterprise(context: Context): Boolean {
            val staticRestricted = staticRestrictionMethod.invokePlatform(
                null,
                context,
                ENTERPRISE_TETHERING_RESTRICTION,
            ) as? Boolean ?: error("RestrictionsHelper.hasRestriction() returned a non-boolean value")
            if (staticRestricted) {
                return true
            }
            return isRestrictionMethod.invokePlatform(
                restrictionHelper,
                ENTERPRISE_TETHERING_RESTRICTION,
            ) as? Boolean ?: error("IRestrictionsHelper.isRestriction() returned a non-boolean value")
        }

        private fun readInterfaceSnapshot(context: Context): InterfaceSnapshot {
            return runCatching {
                InterfaceSnapshot(
                    available = getStringArray(
                        getTetherableIfacesMethod ?: error("getTetherableIfaces() is unavailable"),
                    ).toList(),
                    tethered = getStringArray(
                        getTetheredIfacesMethod ?: error("getTetheredIfaces() is unavailable"),
                    ).toList(),
                    errored = getStringArray(
                        getTetheringErroredIfacesMethod
                            ?: error("getTetheringErroredIfaces() is unavailable"),
                    ).toList(),
                    canReadLastError = getLastTetherErrorMethod != null,
                )
            }.getOrElse {
                readStickyInterfaceSnapshot(context)
            }
        }

        private fun readStickyInterfaceSnapshot(context: Context): InterfaceSnapshot {
            val intent = context.registerReceiver(
                null,
                IntentFilter(TETHER_STATE_CHANGED_ACTION),
                Context.RECEIVER_EXPORTED,
            ) ?: error("The tethering state sticky broadcast is unavailable")
            return InterfaceSnapshot(
                available = intent.getStringArrayListExtra(EXTRA_AVAILABLE_ARRAY).orEmpty(),
                tethered = intent.getStringArrayListExtra(EXTRA_TETHER_ARRAY).orEmpty(),
                errored = intent.getStringArrayListExtra(EXTRA_ERRORED_ARRAY).orEmpty(),
                canReadLastError = false,
            )
        }

        private fun isUsbConnected(context: Context): Boolean {
            return context.registerReceiver(
                null,
                IntentFilter(USB_STATE_ACTION),
                Context.RECEIVER_EXPORTED,
            )?.getBooleanExtra(EXTRA_USB_CONNECTED, false) == true
        }

        private fun hasUsbError(snapshot: InterfaceSnapshot, usbRegexs: List<String>): Boolean {
            if (snapshot.errored.any { interfaceName -> interfaceName.matchesAny(usbRegexs) }) {
                return true
            }
            if (!snapshot.canReadLastError) {
                return false
            }
            val getLastError = getLastTetherErrorMethod ?: return false
            return snapshot.available
                .filter { interfaceName -> interfaceName.matchesAny(usbRegexs) }
                .any { interfaceName ->
                    val error = (getLastError.invokePlatform(
                        tetheringManager,
                        interfaceName,
                    ) as? Number)?.toInt()
                        ?: error("TetheringManager.getLastTetherError() returned a non-numeric value")
                    error != TETHER_ERROR_NO_ERROR && error != TETHER_ERROR_NO_ERROR_COMPAT
                }
        }

        private fun getStringArray(method: Method): Array<String> {
            val value = method.invokePlatform(tetheringManager)
            @Suppress("UNCHECKED_CAST")
            return value as? Array<String>
                ?: error("${method.name}() returned a non-string-array value")
        }

        private fun String.matchesAny(regexs: List<String>): Boolean {
            return regexs.any { regex -> Pattern.matches(regex, this) }
        }

        private fun findOptionalMethod(name: String, vararg parameterTypes: Class<*>): Method? {
            return runCatching {
                tetheringManagerClass.getDeclaredMethod(name, *parameterTypes).apply {
                    isAccessible = true
                }
            }.getOrNull()
        }

        private fun Method.invokePlatform(receiver: Any?, vararg arguments: Any?): Any? {
            return try {
                invoke(receiver, *arguments)
            } catch (error: InvocationTargetException) {
                throw error.cause ?: error
            }
        }
    }

    companion object {
        private const val ID = "usb_tethering"
        private const val FALLBACK_TITLE = "USB tethering"
        private const val FALLBACK_STARTING = "Starting..."
        private const val FALLBACK_STOPPING = "Stopping..."
        private const val FALLBACK_START_FAILED = "Couldn't start"
        private const val FALLBACK_STOP_FAILED = "Couldn't stop"
        private const val FALLBACK_USB_STORAGE_IN_USE = "USB storage in use"
        private const val FALLBACK_UNAVAILABLE = "Unavailable"

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val TETHER_SETTINGS_ACTIVITY =
            $$"com.android.settings.Settings$TetherSettingsActivity"
        private const val TETHER_SETTINGS_ACTION = "android.settings.TETHER_SETTINGS"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val USB_TETHERING_PREFERENCE_KEY = "usb_tether_settings"

        private const val TETHERING_MANAGER_CLASS = "android.net.TetheringManager"
        private const val GET_TETHERABLE_USB_REGEXS_METHOD = "getTetherableUsbRegexs"
        private const val GET_TETHERED_IFACES_METHOD = "getTetheredIfaces"
        private const val GET_TETHERABLE_IFACES_METHOD = "getTetherableIfaces"
        private const val GET_TETHERING_ERRORED_IFACES_METHOD = "getTetheringErroredIfaces"
        private const val GET_LAST_TETHER_ERROR_METHOD = "getLastTetherError"
        private const val STOP_TETHERING_METHOD = "stopTethering"
        private const val SET_SHOULD_SHOW_ENTITLEMENT_UI_METHOD = "setShouldShowEntitlementUi"

        private const val CROSS_USER_UTILS_CLASS = "miui.securityspace.CrossUserUtils"
        private const val GET_CURRENT_USER_METHOD = "getCurrentUserId"
        private const val ENTERPRISE_RESTRICTIONS_HELPER_CLASS =
            "com.miui.enterprise.RestrictionsHelper"
        private const val ENTERPRISE_RESTRICTIONS_HELPER_STUB_CLASS =
            "miui.enterprise.RestrictionsHelperStub"
        private const val ENTERPRISE_RESTRICTIONS_INTERFACE_CLASS =
            "miui.enterprise.IRestrictionsHelper"
        private const val HAS_RESTRICTION_METHOD = "hasRestriction"
        private const val GET_INSTANCE_METHOD = "getInstance"
        private const val IS_RESTRICTION_METHOD = "isRestriction"
        private const val ENTERPRISE_TETHERING_RESTRICTION = "disallow_tether"

        private const val TETHER_PRIVILEGED_PERMISSION = "android.permission.TETHER_PRIVILEGED"
        private const val NETWORK_SETTINGS_PERMISSION = "android.permission.NETWORK_SETTINGS"
        private const val TETHER_STATE_CHANGED_ACTION = "android.net.conn.TETHER_STATE_CHANGED"
        private const val USB_STATE_ACTION = "android.hardware.usb.action.USB_STATE"
        private const val EXTRA_AVAILABLE_ARRAY = "availableArray"
        private const val EXTRA_TETHER_ARRAY = "tetherArray"
        private const val EXTRA_ERRORED_ARRAY = "erroredArray"
        private const val EXTRA_USB_CONNECTED = "connected"

        private const val TETHERING_USB = 1
        private const val SYSTEM_USER_ID = 0
        private const val TETHER_ERROR_NO_ERROR = 0
        private const val TETHER_ERROR_NO_ERROR_COMPAT = 16
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val FAILURE_MESSAGE_DURATION_MS = 5_000L
    }
}
