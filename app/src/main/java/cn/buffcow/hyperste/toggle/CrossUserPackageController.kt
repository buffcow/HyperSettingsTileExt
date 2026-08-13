package cn.buffcow.hyperste.toggle

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 封装 HyperOS 跨用户应用启停、系统属性读取及进程停止能力
 *
 * @author qingyu
 * <p>Create on 2026/08/13 14:43</p>
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class CrossUserPackageController(classLoader: ClassLoader) {

    private val packageManager: Any
    private val isPackageAvailableMethod: Method
    private val getApplicationEnabledSettingMethod: Method
    private val setApplicationEnabledSettingMethod: Method
    private val getCurrentUserMethod: Method
    private val getSystemPropertyMethod: Method
    private val internationalBuildField = classLoader.loadClass(MIUI_BUILD_CLASS)
        .getDeclaredField(INTERNATIONAL_BUILD_FIELD)
        .apply {
            isAccessible = true
        }
    private val forceStopPackageAsUserMethod = runCatching {
        classLoader.loadClass(ACTIVITY_MANAGER_CLASS)
            .getDeclaredMethod(
                FORCE_STOP_PACKAGE_AS_USER_METHOD,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            ).apply {
                isAccessible = true
            }
    }.getOrNull()

    init {
        packageManager = classLoader.loadClass(APP_GLOBALS_CLASS)
            .getDeclaredMethod(GET_PACKAGE_MANAGER_METHOD)
            .apply {
                isAccessible = true
            }
            .invokePlatform(null)
            ?: error("AppGlobals.getPackageManager() returned null")
        packageManager.javaClass.run {
            isPackageAvailableMethod = getMethod(
                IS_PACKAGE_AVAILABLE_METHOD,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            ).apply {
                isAccessible = true
            }
            getApplicationEnabledSettingMethod = getMethod(
                GET_APPLICATION_ENABLED_SETTING_METHOD,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            ).apply {
                isAccessible = true
            }
            setApplicationEnabledSettingMethod = getMethod(
                SET_APPLICATION_ENABLED_SETTING_METHOD,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java,
            ).apply {
                isAccessible = true
            }
        }
        getCurrentUserMethod = classLoader.loadClass(ACTIVITY_MANAGER_CLASS)
            .getDeclaredMethod(GET_CURRENT_USER_METHOD)
            .apply {
                isAccessible = true
            }
        getSystemPropertyMethod = classLoader.loadClass(SYSTEM_PROPERTIES_CLASS)
            .getDeclaredMethod(GET_SYSTEM_PROPERTY_METHOD, String::class.java)
            .apply {
                isAccessible = true
            }
    }

    /** Returns whether SystemUI can perform the complete cross-user mutation. */
    fun hasRequiredMutationPermissions(context: Context): Boolean {
        return with(context) {
            checkSelfPermission(CHANGE_COMPONENT_ENABLED_STATE_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(INTERACT_ACROSS_USERS_FULL_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /** Returns the user currently displayed by SystemUI. */
    fun getCurrentUserId(): Int {
        val userId = (getCurrentUserMethod.invokePlatform(null) as? Number)?.toInt()
            ?: error("ActivityManager.getCurrentUser() returned a non-numeric value")
        check(userId >= 0) {
            "ActivityManager.getCurrentUser() returned an invalid user ID: $userId"
        }
        return userId
    }

    /** Returns whether the current build is an international HyperOS build. */
    fun isInternationalBuild(): Boolean {
        return internationalBuildField.getBoolean(null)
    }

    /** Reads a system property without caching its value. */
    fun getSystemProperty(name: String): String {
        val value = getSystemPropertyMethod.invokePlatform(null, name) as? String
            ?: error("SystemProperties.get() returned a non-string value for $name")
        return value
    }

    /** Returns whether [packageName] is installed and available for [userId]. */
    fun isPackageAvailable(packageName: String, userId: Int): Boolean {
        val isAvailable = isPackageAvailableMethod.invokePlatform(
            packageManager,
            packageName,
            userId,
        ) as? Boolean
            ?: error("IPackageManager.isPackageAvailable() returned a non-boolean value")
        return isAvailable
    }

    /** Returns the explicit application enabled state for [packageName] and [userId]. */
    fun getApplicationEnabledSetting(packageName: String, userId: Int): Int {
        val state = getApplicationEnabledSettingMethod.invokePlatform(
            packageManager,
            packageName,
            userId,
        ) as? Number
            ?: error("IPackageManager.getApplicationEnabledSetting() returned a non-numeric value")
        return state.toInt()
    }

    /** Sets the explicit application enabled [state] for [packageName] and [userId]. */
    fun setApplicationEnabledSetting(
        context: Context,
        packageName: String,
        state: Int,
        userId: Int,
    ) {
        setApplicationEnabledSettingMethod.invokePlatform(
            packageManager,
            packageName,
            state,
            PackageManager.DONT_KILL_APP,
            userId,
            context.packageName,
        )
    }

    /** Force-stops [packageName] for [userId] after it has been disabled. */
    fun forceStopPackage(context: Context, packageName: String, userId: Int) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: error("ActivityManager is unavailable")
        val method = forceStopPackageAsUserMethod
            ?: error("ActivityManager.forceStopPackageAsUser() is unavailable")
        method.invokePlatform(activityManager, packageName, userId)
    }

    private fun Method.invokePlatform(receiver: Any?, vararg arguments: Any?): Any? {
        return try {
            invoke(receiver, *arguments)
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    companion object {
        private const val APP_GLOBALS_CLASS = "android.app.AppGlobals"
        private const val ACTIVITY_MANAGER_CLASS = "android.app.ActivityManager"
        private const val SYSTEM_PROPERTIES_CLASS = "android.os.SystemProperties"
        private const val MIUI_BUILD_CLASS = "miui.os.Build"

        private const val GET_PACKAGE_MANAGER_METHOD = "getPackageManager"
        private const val IS_PACKAGE_AVAILABLE_METHOD = "isPackageAvailable"
        private const val GET_APPLICATION_ENABLED_SETTING_METHOD = "getApplicationEnabledSetting"
        private const val SET_APPLICATION_ENABLED_SETTING_METHOD = "setApplicationEnabledSetting"
        private const val GET_CURRENT_USER_METHOD = "getCurrentUser"
        private const val GET_SYSTEM_PROPERTY_METHOD = "get"
        private const val FORCE_STOP_PACKAGE_AS_USER_METHOD = "forceStopPackageAsUser"
        private const val INTERNATIONAL_BUILD_FIELD = "IS_INTERNATIONAL_BUILD"

        private const val CHANGE_COMPONENT_ENABLED_STATE_PERMISSION =
            "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
        private const val INTERACT_ACROSS_USERS_FULL_PERMISSION =
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    }
}
