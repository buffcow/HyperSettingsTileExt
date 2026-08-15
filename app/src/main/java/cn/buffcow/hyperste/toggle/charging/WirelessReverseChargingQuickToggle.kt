package cn.buffcow.hyperste.toggle.charging

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.widget.Toast
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.extension.findMethod
import cn.buffcow.hyperste.extension.invokeUnwrapped
import cn.buffcow.hyperste.extension.resolveString
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleAction
import cn.buffcow.hyperste.toggle.QuickToggleActionUnavailableException
import cn.buffcow.hyperste.toggle.QuickToggleCategory
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState
import java.lang.reflect.Method

/**
 * Controls HyperOS reverse wireless charging while preserving the vendor enable flow.
 *
 * Hardware support and the authoritative checked state come from Xiaomi's charging interfaces.
 * Enabling is routed through the original HyperOS confirmation activity after reproducing the
 * built-in tile's prerequisite checks, while disabling is applied directly.
 *
 * @author qingyu
 * <p>Create on 2026/08/15 15:45</p>
 */
internal class WirelessReverseChargingQuickToggle(
    private val classLoader: ClassLoader,
) : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.BATTERY
    override val titleRes: Int = R.string.quick_toggle_wireless_reverse_charging_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openReverseChargingSettings)

    private var backendFailureLogged = false
    private var stateFailureLogged = false
    private var pendingRequest: PendingRequest? = null
    private val backendResult by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            WirelessChargingBackend(classLoader)
        }
    }
    private val systemPropertyReaderResult by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            SystemPropertyReader(classLoader)
        }
    }

    override fun readState(host: QuickToggleHost): QuickToggleState {
        val backend = getBackend() ?: return QuickToggleState.UNAVAILABLE
        return runCatching {
            val actualState = backend.isChecked()
            val request = reconcilePendingRequest(actualState)
            QuickToggleState(
                isAvailable = true,
                isEnabled = request == null,
                isChecked = request?.targetState ?: actualState,
            )
        }.onSuccess {
            stateFailureLogged = false
        }.onFailure {
            if (!stateFailureLogged) {
                stateFailureLogged = true
                logError("Failed to read the reverse wireless charging state", it)
            }
        }.getOrDefault(QuickToggleState.UNAVAILABLE)
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        val backend = getBackend()
            ?: error("Reverse wireless charging is unsupported on this device")
        check(pendingRequest == null) {
            "A reverse wireless charging state change is already in progress"
        }
        if (backend.isChecked() == checked) {
            return
        }

        if (checked) {
            requestEnable(host)
        } else {
            requestDisable(host.context, backend)
        }
    }

    private fun requestEnable(host: QuickToggleHost) {
        val action = createEnableAction(host) ?: return
        val intent = requireResolvableIntent(
            context = host.context,
            intent = action.intent,
            unavailableMessage = action.unavailableMessage,
        )
        if (action.expectsStateChange) {
            pendingRequest = PendingRequest(
                targetState = true,
                requestedAtMillis = SystemClock.elapsedRealtime(),
            )
        }
        try {
            host.startActivity(intent)
        } catch (failure: Throwable) {
            pendingRequest = null
            throw failure
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestDisable(context: Context, backend: WirelessChargingBackend) {
        pendingRequest = PendingRequest(
            targetState = false,
            requestedAtMillis = SystemClock.elapsedRealtime(),
        )
        try {
            backend.setChecked(false)
        } catch (failure: Throwable) {
            pendingRequest = null
            throw failure
        }
        runCatching {
            context.sendBroadcastAsUser(
                Intent(ACTION_WIRELESS_CHARGING_CHANGED).apply {
                    putExtra(EXTRA_WIRELESS_CHARGING_STATE, WIRELESS_CHARGING_DISABLED)
                },
                resolveAllUsersHandle(),
            )
        }.onFailure {
            logError("Failed to broadcast the reverse wireless charging state", it)
        }
        logDebug("Reverse wireless charging state requested: checked=false")
    }

    private fun resolveAllUsersHandle(): UserHandle {
        return UserHandle::class.java.getDeclaredField(USER_HANDLE_ALL_FIELD)
            .apply { isAccessible = true }
            .get(null) as? UserHandle
            ?: error("UserHandle.ALL returned an unexpected value")
    }

    private fun reconcilePendingRequest(actualState: Boolean): PendingRequest? {
        val request = pendingRequest ?: return null
        if (request.targetState == actualState) {
            pendingRequest = null
            logDebug("Reverse wireless charging state changed: checked=$actualState")
            return null
        }
        if (SystemClock.elapsedRealtime() - request.requestedAtMillis < REQUEST_TIMEOUT_MS) {
            return request
        }
        pendingRequest = null
        logDebug(
            "Reverse wireless charging state change timed out: " +
                    "requested=${request.targetState}, actual=$actualState",
        )
        return null
    }

    private fun createEnableAction(host: QuickToggleHost): EnableAction? {
        val context = host.context
        if (
            Settings.Global.getInt(
                context.contentResolver,
                PHONE_CASE_STATUS_KEY,
                PHONE_CASE_STATUS_UNKNOWN,
            ) == PHONE_CASE_STATUS_BLOCKED
        ) {
            Toast.makeText(
                context,
                host.resolveString(
                    R.string.quick_toggle_wireless_reverse_charging_remove_case,
                    FALLBACK_REMOVE_CASE,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            return null
        }

        val batteryState = readBatteryState(context)
        if (batteryState.plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            return createPluginDialogAction(DIALOG_WIRELESS_CHARGING_CONNECTED)
        }
        if (batteryState.plugged == BATTERY_NOT_PLUGGED) {
            val configuredThreshold = Settings.Global.getInt(
                context.contentResolver,
                WIRELESS_REVERSE_CHARGING_THRESHOLD_KEY,
                THRESHOLD_UNCONFIGURED,
            )
            if (
                batteryState.levelPercent < MINIMUM_BATTERY_LEVEL_PERCENT ||
                configuredThreshold <= THRESHOLD_UNCONFIGURED &&
                batteryState.levelPercent < DEFAULT_BATTERY_THRESHOLD_PERCENT
            ) {
                return createPluginDialogAction(DIALOG_LOW_BATTERY)
            }
            if (
                configuredThreshold > THRESHOLD_UNCONFIGURED &&
                batteryState.levelPercent < configuredThreshold
            ) {
                return createPluginDialogAction(DIALOG_BELOW_CONFIGURED_THRESHOLD)
            }
        }
        if (isWiredChargingBlocked(batteryState)) {
            return EnableAction(
                intent = Intent().apply {
                    setClassName(SECURITY_CENTER_PACKAGE, WIRED_CHARGING_DIALOG_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_DIALOG_SELECTED, DIALOG_BELOW_CONFIGURED_THRESHOLD)
                },
                expectsStateChange = false,
                unavailableMessage = "Reverse wireless charging conflict dialog is unavailable",
            )
        }
        return createPluginDialogAction(
            dialogType = DIALOG_CONFIRM_ENABLE,
            expectsStateChange = true,
        )
    }

    private fun createPluginDialogAction(
        dialogType: Int,
        expectsStateChange: Boolean = false,
    ): EnableAction {
        return EnableAction(
            intent = Intent(ACTION_WIRELESS_CHARGING_DIALOG).apply {
                setPackage(SYSTEM_UI_PLUGIN_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_DIALOG_SELECTED, dialogType)
            },
            expectsStateChange = expectsStateChange,
            unavailableMessage = "Reverse wireless charging confirmation activity is unavailable",
        )
    }

    private fun isWiredChargingBlocked(batteryState: BatteryState): Boolean {
        val isWiredCharging =
            batteryState.status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    batteryState.status == BatteryManager.BATTERY_STATUS_FULL
        if (!isWiredCharging || batteryState.plugged !in WIRED_POWER_SOURCES) {
            return false
        }
        val restrictionMode = systemPropertyReaderResult.getOrElse {
            throw IllegalStateException("System property access is unavailable", it)
        }.getInt(NO_REVERSE_BOX_PROPERTY, NO_REVERSE_BOX_DISABLED)
        return when (restrictionMode) {
            NO_REVERSE_BOX_ALL_BUILDS -> true
            NO_REVERSE_BOX_INTERNATIONAL_BUILD -> isInternationalBuild()
            else -> false
        }
    }

    private fun isInternationalBuild(): Boolean {
        return BUILD_CLASS_NAMES.firstNotNullOfOrNull { className ->
            runCatching {
                classLoader.loadClass(className)
                    .getDeclaredField(INTERNATIONAL_BUILD_FIELD)
                    .apply { isAccessible = true }
                    .getBoolean(null)
            }.getOrNull()
        } ?: run {
            logDebug(
                "HyperOS international-build state is unavailable; " +
                        "applying the reverse-charging wired-power restriction conservatively",
            )
            true
        }
    }

    private fun readBatteryState(context: Context): BatteryState {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_EXPORTED,
        ) ?: error("The sticky battery-state broadcast is unavailable")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, BATTERY_LEVEL_UNKNOWN)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, BATTERY_SCALE_UNKNOWN)
        check(level >= 0 && scale > 0) {
            "The battery-state broadcast contains an invalid level or scale"
        }
        return BatteryState(
            levelPercent = level * PERCENT_SCALE / scale,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, BATTERY_NOT_PLUGGED),
            status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN,
            ),
        )
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openReverseChargingSettings(host: QuickToggleHost) {
        val action = if (
            Settings.Global.getInt(
                host.context.contentResolver,
                WIRELESS_REVERSE_CHARGING_THRESHOLD_KEY,
                THRESHOLD_UNCONFIGURED,
            ) > THRESHOLD_UNCONFIGURED
        ) {
            ACTION_WIRELESS_REVERSE_CHARGING_SETTINGS
        } else {
            ACTION_POWER_SETTINGS
        }
        val intent = Intent(action).apply {
            setPackage(SECURITY_CENTER_PACKAGE)
        }
        requireResolvableIntent(
            context = host.context,
            intent = intent,
            unavailableMessage = "Reverse wireless charging settings activity is unavailable",
        )
        host.startActivity(intent)
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun requireResolvableIntent(
        context: Context,
        intent: Intent,
        unavailableMessage: String,
    ): Intent {
        if (
            intent.resolveActivityInfo(
                context.packageManager,
                PackageManager.MATCH_DEFAULT_ONLY,
            ) == null
        ) {
            throw QuickToggleActionUnavailableException(unavailableMessage)
        }
        return intent
    }

    private fun getBackend(): WirelessChargingBackend? {
        return backendResult.onFailure {
            if (!backendFailureLogged) {
                backendFailureLogged = true
                logError("Failed to initialize reverse wireless charging control", it)
            }
        }.getOrNull()
    }

    private data class BatteryState(
        val levelPercent: Int,
        val plugged: Int,
        val status: Int,
    )

    private data class EnableAction(
        val intent: Intent,
        val expectsStateChange: Boolean,
        val unavailableMessage: String,
    )

    private data class PendingRequest(
        val targetState: Boolean,
        val requestedAtMillis: Long,
    )

    private class WirelessChargingBackend(classLoader: ClassLoader) {

        private val controllers: List<ChargingController> = CONTROLLER_CLASS_NAMES.mapNotNull { name ->
            runCatching {
                ChargingController(classLoader, name)
            }.onFailure {
                logDebug(
                    "Reverse wireless charging backend is unavailable: " +
                            "class=$name, reason=${it.javaClass.simpleName}",
                )
            }.getOrNull()
        }.filter { controller ->
            runCatching {
                controller.isSupported()
            }.onFailure {
                logError(
                    "Failed to check reverse wireless charging backend support: " +
                            "class=${controller.className}",
                    it,
                )
            }.getOrDefault(false)
        }

        init {
            check(controllers.isNotEmpty()) {
                "No supported reverse wireless charging backend is available"
            }
            logDebug(
                "Reverse wireless charging backends initialized: " +
                        controllers.joinToString { it.className },
            )
        }

        fun isChecked(): Boolean {
            var lastFailure: Throwable? = null
            controllers.forEach { controller ->
                runCatching {
                    return controller.isChecked()
                }.onFailure {
                    lastFailure = it
                }
            }
            throw IllegalStateException(
                "Every reverse wireless charging backend failed to read the current state",
                lastFailure,
            )
        }

        fun setChecked(checked: Boolean) {
            var lastFailure: Throwable? = null
            controllers.forEach { controller ->
                runCatching {
                    controller.setChecked(checked)
                    logDebug(
                        "Reverse wireless charging backend accepted the state change: " +
                                "class=${controller.className}, checked=$checked",
                    )
                    return
                }.onFailure {
                    lastFailure = it
                    logError(
                        "Reverse wireless charging backend rejected the state change: " +
                                "class=${controller.className}, checked=$checked",
                        it,
                    )
                }
            }
            throw IllegalStateException(
                "Every reverse wireless charging backend rejected the requested state",
                lastFailure,
            )
        }
    }

    private class ChargingController(
        classLoader: ClassLoader,
        val className: String,
    ) {

        private val instance: Any
        private val supportMethod: Method
        private val statusMethod: Method
        private val setEnabledMethod: Method

        init {
            val controllerClass = classLoader.loadClass(className)
            instance = controllerClass.findMethod(GET_INSTANCE_METHOD, 0)
                .invokeUnwrapped(null)
                ?: error("$className.getInstance() returned null")
            instance.javaClass.run {
                supportMethod = findMethod(IS_SUPPORTED_METHOD, 0)
                statusMethod = findMethod(GET_STATUS_METHOD, 0)
                setEnabledMethod = findMethod(
                    SET_ENABLED_METHOD,
                    Boolean::class.javaPrimitiveType!!,
                )
            }
        }

        fun isSupported(): Boolean {
            return supportMethod.invokeUnwrapped(instance) as? Boolean
                ?: error("$className.$IS_SUPPORTED_METHOD() returned a non-boolean value")
        }

        fun isChecked(): Boolean {
            val status = statusMethod.invokeUnwrapped(instance) as? Number
                ?: error("$className.$GET_STATUS_METHOD() returned a non-numeric value")
            return status.toInt() == STATUS_ENABLED
        }

        fun setChecked(checked: Boolean) {
            when (val result = setEnabledMethod.invokeUnwrapped(instance, checked)) {
                null -> Unit
                is Number -> check(result.toInt() == RESULT_SUCCESS) {
                    "$className.$SET_ENABLED_METHOD() returned ${result.toInt()}"
                }

                is Boolean -> check(result) {
                    "$className.$SET_ENABLED_METHOD() rejected the state change"
                }

                else -> error(
                    "$className.$SET_ENABLED_METHOD() returned an unsupported result type: " +
                            result.javaClass.name,
                )
            }
        }
    }

    private class SystemPropertyReader(classLoader: ClassLoader) {

        @SuppressLint("PrivateApi")
        private val getIntMethod = classLoader.loadClass(SYSTEM_PROPERTIES_CLASS)
            .findMethod(
                SYSTEM_PROPERTIES_GET_INT_METHOD,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            )

        fun getInt(name: String, defaultValue: Int): Int {
            return (getIntMethod.invokeUnwrapped(null, name, defaultValue) as? Number)?.toInt()
                ?: error("SystemProperties.getInt() returned a non-numeric value")
        }
    }

    companion object {
        private const val ID = "wireless_reverse_charging"
        private const val FALLBACK_TITLE = "Reverse wireless charging"
        private const val FALLBACK_REMOVE_CASE = "Remove the phone case first"

        private const val MI_CHARGE_CLASS = "miui.util.IMiCharge"
        private const val WIRELESS_SWITCH_CLASS = "miui.util.IWirelessSwitch"
        private val CONTROLLER_CLASS_NAMES = listOf(MI_CHARGE_CLASS, WIRELESS_SWITCH_CLASS)
        private const val GET_INSTANCE_METHOD = "getInstance"
        private const val IS_SUPPORTED_METHOD = "isWirelessChargingSupported"
        private const val GET_STATUS_METHOD = "getWirelessChargingStatus"
        private const val SET_ENABLED_METHOD = "setWirelessChargingEnabled"
        private const val STATUS_ENABLED = 0
        private const val RESULT_SUCCESS = 0

        private const val SYSTEM_UI_PLUGIN_PACKAGE = "miui.systemui.plugin"
        private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
        private const val WIRED_CHARGING_DIALOG_ACTIVITY =
            "com.miui.powercenter.wirelesscharge.WirelessChargingDialogActivity"

        private const val ACTION_WIRELESS_CHARGING_DIALOG =
            "miui.intent.action.ACTIVITY_WIRELESS_CHG_DIALOG"
        private const val ACTION_WIRELESS_CHARGING_CHANGED =
            "miui.intent.action.ACTION_WIRELESS_CHARGING"
        private const val ACTION_WIRELESS_REVERSE_CHARGING_SETTINGS =
            "miui.intent.action.POWER_WIRELESS_REVERSE_LIST"
        private const val ACTION_POWER_SETTINGS = "com.miui.securitycenter.action.POWER_SETTINGS"
        private const val EXTRA_DIALOG_SELECTED = "dialogSelected"
        private const val EXTRA_WIRELESS_CHARGING_STATE =
            "miui.intent.extra.WIRELESS_CHARGING"
        private const val WIRELESS_CHARGING_DISABLED = 1
        private const val USER_HANDLE_ALL_FIELD = "ALL"

        private const val DIALOG_CONFIRM_ENABLE = 1
        private const val DIALOG_WIRELESS_CHARGING_CONNECTED = 3
        private const val DIALOG_LOW_BATTERY = 4
        private const val DIALOG_BELOW_CONFIGURED_THRESHOLD = 5

        private const val PHONE_CASE_STATUS_KEY = "phone_case_status"
        private const val PHONE_CASE_STATUS_UNKNOWN = -1
        private const val PHONE_CASE_STATUS_BLOCKED = 3
        private const val WIRELESS_REVERSE_CHARGING_THRESHOLD_KEY =
            "wireless_reverse_charging"
        private const val THRESHOLD_UNCONFIGURED = 0
        private const val MINIMUM_BATTERY_LEVEL_PERCENT = 20
        private const val DEFAULT_BATTERY_THRESHOLD_PERCENT = 30

        private const val SYSTEM_PROPERTIES_CLASS = "android.os.SystemProperties"
        private const val SYSTEM_PROPERTIES_GET_INT_METHOD = "getInt"
        private const val NO_REVERSE_BOX_PROPERTY = "persist.vendor.noReverseBox.inGL"
        private const val NO_REVERSE_BOX_DISABLED = 0
        private const val NO_REVERSE_BOX_INTERNATIONAL_BUILD = 1
        private const val NO_REVERSE_BOX_ALL_BUILDS = 3
        private val BUILD_CLASS_NAMES = listOf("miuix.os.Build", "miui.os.Build")
        private const val INTERNATIONAL_BUILD_FIELD = "IS_INTERNATIONAL_BUILD"

        private const val BATTERY_LEVEL_UNKNOWN = -1
        private const val BATTERY_SCALE_UNKNOWN = -1
        private const val BATTERY_NOT_PLUGGED = 0
        private const val PERCENT_SCALE = 100
        private val WIRED_POWER_SOURCES = setOf(
            BatteryManager.BATTERY_PLUGGED_AC,
            BatteryManager.BATTERY_PLUGGED_USB,
        )

        private const val REQUEST_TIMEOUT_MS = 5_000L
    }
}
