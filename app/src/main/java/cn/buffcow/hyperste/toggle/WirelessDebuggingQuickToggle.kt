@file:SuppressLint("MissingPermission", "BlockedPrivateApi", "DiscouragedPrivateApi")

package cn.buffcow.hyperste.toggle

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.provider.Settings
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import java.lang.reflect.Method
import java.net.Inet4Address

/**
 * Reads and controls HyperOS wireless debugging and exposes its active Wi-Fi endpoint.
 *
 * @author qingyu
 * <p>Create on 2026/08/13 10:47</p>
 */
@SuppressLint("PrivateApi")
internal class WirelessDebuggingQuickToggle(
    private val classLoader: ClassLoader,
) : QuickToggle {

    override val id: String = ID
    override val titleRes: Int = R.string.quick_toggle_wireless_debugging_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openWirelessDebuggingSettings)

    private var adbFailureLogged = false
    private val adbBackend: AdbManagerBackend? by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            AdbManagerBackend(classLoader)
        }.onFailure {
            logAdbFailureOnce("Failed to connect to the ADB manager service", it)
        }.getOrNull()
    }
    private val isSupported: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        val backend = adbBackend ?: return@lazy false
        runCatching {
            backend.isAdbWifiSupported()
        }.onFailure {
            logAdbFailureOnce("Failed to check wireless debugging support", it)
        }.getOrDefault(false).also { supported ->
            if (!supported) {
                logDebug("Wireless debugging is not supported on this device")
            }
        }
    }

    override fun readState(host: QuickToggleHost): QuickToggleState {
        if (!isSupported || !isDeveloperAccessAllowed(host.context)) {
            return QuickToggleState.UNAVAILABLE
        }

        val checked = isWirelessDebuggingEnabled(host.context)
        val wifiNetwork = findWifiNetwork(host.context)
        val isWifiConnected = wifiNetwork != null
        val isKeyguardLocked = isKeyguardLocked(host.context)
        val secondaryText = when {
            !isWifiConnected -> host.resolveString(
                R.string.quick_toggle_wireless_debugging_no_wifi,
                FALLBACK_NO_WIFI,
            )

            checked -> resolveWirelessEndpoint(host, wifiNetwork)
                ?: return QuickToggleState.UNAVAILABLE

            isKeyguardLocked -> host.resolveString(
                R.string.quick_toggle_wireless_debugging_unlock,
                FALLBACK_UNLOCK_TO_ENABLE,
            )

            else -> null
        }
        return QuickToggleState(
            isAvailable = true,
            isEnabled = checked || isWifiConnected && !isKeyguardLocked,
            isChecked = checked,
            secondaryText = secondaryText,
        )
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        check(isSupported) {
            "Wireless debugging is not supported on this device"
        }
        if (checked) {
            check(isDeveloperAccessAllowed(host.context)) {
                "Developer options are unavailable for the current user"
            }
            check(findWifiNetwork(host.context) != null) {
                "Wireless debugging requires a Wi-Fi connection"
            }
            check(!isKeyguardLocked(host.context)) {
                "Wireless debugging cannot be enabled while the device is locked"
            }
        }
        check(
            Settings.Global.putInt(
                host.context.contentResolver,
                WIRELESS_DEBUGGING_SETTING,
                if (checked) SETTING_ENABLED else SETTING_DISABLED,
            ),
        ) {
            "Settings.Global rejected the wireless debugging state change"
        }
        logDebug("Wireless debugging state requested: checked=$checked")
    }

    private fun resolveWirelessEndpoint(
        host: QuickToggleHost,
        wifiNetwork: Network,
    ): CharSequence? {
        val backend = adbBackend ?: return null
        val port = runCatching {
            backend.getAdbWirelessPort()
        }.onFailure {
            logAdbFailureOnce("Failed to read the wireless debugging port", it)
        }.getOrNull() ?: return null
        val address = getWifiIpv4Address(host.context, wifiNetwork)
        return if (address != null && port > 0) {
            "$address:$port"
        } else {
            host.resolveString(
                R.string.quick_toggle_wireless_debugging_getting_ip,
                FALLBACK_GETTING_IP,
            )
        }
    }

    private fun isDeveloperAccessAllowed(context: Context): Boolean {
        val developmentSettingsEnabled = Settings.Global.getInt(
            context.contentResolver,
            DEVELOPMENT_SETTINGS_SETTING,
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

    private fun isWirelessDebuggingEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            WIRELESS_DEBUGGING_SETTING,
            SETTING_DISABLED,
        ) != SETTING_DISABLED
    }

    private fun isKeyguardLocked(context: Context): Boolean {
        return context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    @Suppress("DEPRECATION")
    private fun findWifiNetwork(context: Context): Network? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        return connectivityManager.run {
            activeNetwork?.takeIf { isWifiNetwork(it) }
                ?: allNetworks.firstOrNull { isWifiNetwork(it) }
        }
    }

    private fun ConnectivityManager.isWifiNetwork(network: Network): Boolean {
        return getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun getWifiIpv4Address(context: Context, network: Network): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        return connectivityManager.run {
            getLinkProperties(network)
                ?.linkAddresses
                ?.asSequence()
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }
    }

    private fun openWirelessDebuggingSettings(host: QuickToggleHost) {
        val title = host.moduleResources?.getString(titleRes, fallbackTitle) ?: fallbackTitle
        host.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(SETTINGS_PACKAGE, SUB_SETTINGS_ACTIVITY)
                putExtra(EXTRA_SHOW_FRAGMENT, WIRELESS_DEBUGGING_FRAGMENT)
                putExtra(EXTRA_SHOW_FRAGMENT_TITLE, title)
            },
        )
    }

    private fun QuickToggleHost.resolveString(resourceId: Int, fallback: String): String {
        return moduleResources?.getString(resourceId, fallback) ?: fallback
    }

    private fun logAdbFailureOnce(message: String, throwable: Throwable) {
        if (adbFailureLogged) {
            return
        }
        adbFailureLogged = true
        logError(message, throwable)
    }

    private class AdbManagerBackend(classLoader: ClassLoader) {

        private val instance: Any
        private val isAdbWifiSupportedMethod: Method
        private val getAdbWirelessPortMethod: Method

        init {
            val serviceManagerClass = classLoader.loadClass(SERVICE_MANAGER_CLASS)
            val binder = serviceManagerClass.getDeclaredMethod(
                GET_SERVICE_METHOD,
                String::class.java,
            ).run {
                isAccessible = true
                invoke(null, ADB_SERVICE_NAME) as? IBinder
                    ?: error("ServiceManager.getService(\"adb\") returned null")
            }
            val adbManagerClass = classLoader.loadClass(ADB_MANAGER_CLASS)
            instance = classLoader.loadClass(ADB_MANAGER_STUB_CLASS)
                .getDeclaredMethod(AS_INTERFACE_METHOD, IBinder::class.java)
                .run {
                    isAccessible = true
                    invoke(null, binder) ?: error("IAdbManager.Stub.asInterface() returned null")
                }
            adbManagerClass.run {
                isAdbWifiSupportedMethod = getDeclaredMethod(
                    IS_ADB_WIFI_SUPPORTED_METHOD,
                ).apply {
                    isAccessible = true
                }
                getAdbWirelessPortMethod = getDeclaredMethod(
                    GET_ADB_WIRELESS_PORT_METHOD,
                ).apply {
                    isAccessible = true
                }
            }
        }

        fun isAdbWifiSupported(): Boolean {
            return isAdbWifiSupportedMethod.invoke(instance) as? Boolean
                ?: error("IAdbManager.isAdbWifiSupported() returned a non-boolean value")
        }

        fun getAdbWirelessPort(): Int {
            return (getAdbWirelessPortMethod.invoke(instance) as? Number)?.toInt()
                ?: error("IAdbManager.getAdbWirelessPort() returned a non-numeric value")
        }
    }

    companion object {
        private const val ID = "wireless_debugging"
        private const val FALLBACK_TITLE = "Wireless debugging"
        private const val FALLBACK_NO_WIFI = "No Wi-Fi"
        private const val FALLBACK_UNLOCK_TO_ENABLE = "Unlock to enable"
        private const val FALLBACK_GETTING_IP = "Getting IP..."

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SUB_SETTINGS_ACTIVITY = "com.android.settings.SubSettings"
        private const val WIRELESS_DEBUGGING_FRAGMENT =
            "com.android.settings.development.WirelessDebuggingFragment"
        private const val EXTRA_SHOW_FRAGMENT = ":settings:show_fragment"
        private const val EXTRA_SHOW_FRAGMENT_TITLE = ":settings:show_fragment_title"

        private const val SERVICE_MANAGER_CLASS = "android.os.ServiceManager"
        private const val ADB_MANAGER_CLASS = "android.debug.IAdbManager"
        private const val ADB_MANAGER_STUB_CLASS = $$"android.debug.IAdbManager$Stub"
        private const val GET_SERVICE_METHOD = "getService"
        private const val AS_INTERFACE_METHOD = "asInterface"
        private const val IS_ADB_WIFI_SUPPORTED_METHOD = "isAdbWifiSupported"
        private const val GET_ADB_WIRELESS_PORT_METHOD = "getAdbWirelessPort"
        private const val ADB_SERVICE_NAME = "adb"

        private const val DEVELOPMENT_SETTINGS_SETTING = "development_settings_enabled"
        private const val WIRELESS_DEBUGGING_SETTING = "adb_wifi_enabled"
        private const val BUILD_TYPE_ENG = "eng"
        private const val SETTING_DISABLED = 0
        private const val SETTING_ENABLED = 1
    }
}
