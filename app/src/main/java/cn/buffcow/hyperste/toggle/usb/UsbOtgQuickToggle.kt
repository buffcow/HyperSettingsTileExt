package cn.buffcow.hyperste.toggle.usb

import android.content.Intent
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleAction
import cn.buffcow.hyperste.toggle.QuickToggleCategory
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState
import java.lang.reflect.Method

/**
 * Reads and controls the HyperOS USB OTG state.
 *
 * @author qingyu
 * <p>Create on 2026/08/12 17:56</p>
 */
internal class UsbOtgQuickToggle(
    private val classLoader: ClassLoader,
) : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.CONNECTIVITY
    override val titleRes: Int = R.string.quick_toggle_usb_otg_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openOtgSettings)

    private val backend: OtgBackend? by lazy(LazyThreadSafetyMode.NONE) {
        createBackend()
    }

    override fun readState(host: QuickToggleHost): QuickToggleState {
        return backend?.readState() ?: QuickToggleState.UNAVAILABLE
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        val activeBackend = backend ?: error("USB OTG is not supported on this device")
        activeBackend.setChecked(checked)
    }

    private fun openOtgSettings(host: QuickToggleHost) {
        val title = host.moduleResources?.getString(titleRes, fallbackTitle)
            ?: fallbackTitle
        host.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(SETTINGS_PACKAGE, SUB_SETTINGS_ACTIVITY)
                putExtra(EXTRA_SHOW_FRAGMENT, OTG_SETTINGS_FRAGMENT)
                putExtra(EXTRA_SHOW_FRAGMENT_TITLE, title)
                putExtra(EXTRA_FRAGMENT_ARG_KEY, OTG_PREFERENCE_KEY)
            },
        )
    }

    private fun createBackend(): OtgBackend? {
        findSupportedBackend(BACKEND_MI_CHARGE, createMiChargeBackend())?.let {
            logDebug("USB OTG is using the IMiCharge backend")
            return it
        }
        findSupportedBackend(BACKEND_OTG_SWITCH, createLegacyBackend())?.let {
            logDebug("USB OTG is using the IOtgSwitch backend")
            return it
        }
        logDebug("USB OTG is not supported on this device")
        return null
    }

    private fun findSupportedBackend(name: String, backend: OtgBackend?): OtgBackend? {
        if (backend == null) {
            return null
        }
        return runCatching {
            backend.takeIf(OtgBackend::isSupported)
        }.onFailure {
            logError("Failed to check USB OTG $name backend support", it)
        }.getOrNull()
    }

    private fun createMiChargeBackend(): OtgBackend? {
        return runCatching {
            MiChargeBackend(classLoader)
        }.onFailure {
            logDebug("USB OTG IMiCharge backend is unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun createLegacyBackend(): OtgBackend? {
        return runCatching {
            LegacyOtgBackend(classLoader)
        }.onFailure {
            logDebug("USB OTG IOtgSwitch backend is unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private interface OtgBackend {
        fun isSupported(): Boolean

        fun readState(): QuickToggleState

        fun setChecked(checked: Boolean)
    }

    private class MiChargeBackend(classLoader: ClassLoader) : OtgBackend {

        private val instance: Any
        private val getPathMethod: Method
        private val setPathMethod: Method

        init {
            val miChargeClass = classLoader.loadClass(MI_CHARGE_CLASS)
            instance = miChargeClass.getDeclaredMethod(GET_INSTANCE_METHOD).run {
                isAccessible = true
                invoke(null) ?: error("IMiCharge.getInstance() returned null")
            }
            instance.javaClass.run {
                getPathMethod = getDeclaredMethod(GET_PATH_METHOD, String::class.java).apply {
                    isAccessible = true
                }
                setPathMethod = getDeclaredMethod(
                    SET_PATH_METHOD,
                    String::class.java,
                    String::class.java,
                ).apply {
                    isAccessible = true
                }
            }
        }

        override fun isSupported(): Boolean {
            return readValue(SUPPORT_PATH) == VALUE_ENABLED
        }

        override fun readState(): QuickToggleState {
            return QuickToggleState(
                isAvailable = true,
                isEnabled = readValue(CID_STATUS_PATH) != VALUE_ENABLED,
                isChecked = readValue(TOGGLE_PATH) == VALUE_ENABLED,
            )
        }

        override fun setChecked(checked: Boolean) {
            logDebug("Setting USB OTG state through IMiCharge: checked=$checked")
            val result = setPathMethod.invoke(
                instance,
                TOGGLE_PATH,
                if (checked) VALUE_ENABLED else VALUE_DISABLED,
            )
            check(result != false) {
                "IMiCharge.setMiChargePath() rejected the USB OTG state change"
            }
        }

        private fun readValue(path: String): String {
            return getPathMethod.invoke(instance, path) as? String
                ?: error("IMiCharge.getMiChargePath() returned a non-string value for $path")
        }
    }

    private class LegacyOtgBackend(classLoader: ClassLoader) : OtgBackend {

        private val instance: Any
        private val isSupportedMethod: Method
        private val getStatusMethod: Method
        private val setEnabledMethod: Method

        init {
            val otgSwitchClass = classLoader.loadClass(OTG_SWITCH_CLASS)
            instance = otgSwitchClass.getDeclaredMethod(GET_INSTANCE_METHOD).run {
                isAccessible = true
                invoke(null) ?: error("IOtgSwitch.getInstance() returned null")
            }
            instance.javaClass.run {
                isSupportedMethod = getDeclaredMethod(IS_SUPPORTED_METHOD).apply {
                    isAccessible = true
                }
                getStatusMethod = getDeclaredMethod(GET_STATUS_METHOD).apply {
                    isAccessible = true
                }
                setEnabledMethod = getDeclaredMethod(
                    SET_ENABLED_METHOD,
                    Boolean::class.javaPrimitiveType!!,
                ).apply {
                    isAccessible = true
                }
            }
        }

        override fun isSupported(): Boolean {
            return isSupportedMethod.invoke(instance) as? Boolean
                ?: error("IOtgSwitch.isOtgSupported() returned a non-boolean value")
        }

        override fun readState(): QuickToggleState {
            val status = getStatusMethod.invoke(instance) as? Number
                ?: error("IOtgSwitch.getOtgStatus() returned a non-numeric value")
            return QuickToggleState(
                isAvailable = true,
                isEnabled = true,
                isChecked = status.toInt() == LEGACY_STATUS_ENABLED,
            )
        }

        override fun setChecked(checked: Boolean) {
            setEnabledMethod.invoke(instance, checked)
        }
    }

    companion object {
        private const val ID = "usb_otg"
        private const val FALLBACK_TITLE = "USB OTG"
        private const val BACKEND_MI_CHARGE = "IMiCharge"
        private const val BACKEND_OTG_SWITCH = "IOtgSwitch"

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SUB_SETTINGS_ACTIVITY = "com.android.settings.SubSettings"
        private const val OTG_SETTINGS_FRAGMENT = "com.android.settings.OtgSettings"
        private const val EXTRA_SHOW_FRAGMENT = ":settings:show_fragment"
        private const val EXTRA_SHOW_FRAGMENT_TITLE = ":settings:show_fragment_title"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val OTG_PREFERENCE_KEY = "otg_button"

        private const val MI_CHARGE_CLASS = "miui.util.IMiCharge"
        private const val OTG_SWITCH_CLASS = "miui.util.IOtgSwitch"
        private const val GET_INSTANCE_METHOD = "getInstance"
        private const val GET_PATH_METHOD = "getMiChargePath"
        private const val SET_PATH_METHOD = "setMiChargePath"
        private const val IS_SUPPORTED_METHOD = "isOtgSupported"
        private const val GET_STATUS_METHOD = "getOtgStatus"
        private const val SET_ENABLED_METHOD = "setOtgEnabled"
        private const val SUPPORT_PATH = "otg_ui_support"
        private const val TOGGLE_PATH = "cc_toggle"
        private const val CID_STATUS_PATH = "cid_status"
        private const val VALUE_ENABLED = "1"
        private const val VALUE_DISABLED = "0"
        private const val LEGACY_STATUS_ENABLED = 0
    }
}
