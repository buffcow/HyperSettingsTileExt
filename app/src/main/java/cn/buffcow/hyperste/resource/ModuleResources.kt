package cn.buffcow.hyperste.resource

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.xposedModule

/**
 * Provides configuration-aware access to resources packaged with this Xposed module.
 *
 * Each instance represents one short-lived resource session derived from the current host
 * configuration. Callers should create a new session when showing UI instead of caching strings,
 * drawables, themes, or the host context.
 *
 * @author qingyu
 * <p>Create on 2026/08/12 18:27</p>
 */
internal class ModuleResources private constructor(
    private val resources: Resources,
    private val theme: Resources.Theme?,
) {

    /** Returns a module string or [fallback] when the resource cannot be resolved. */
    fun getString(@StringRes resourceId: Int, fallback: String): String {
        return runCatching {
            resources.getString(resourceId)
        }.onFailure {
            logResourceFailure("string", resourceId, it)
        }.getOrDefault(fallback)
    }

    /**
     * Returns a formatted module string or [fallback] when the resource cannot be resolved.
     *
     * The fallback is returned as-is because it may already be a complete user-facing message.
     */
    fun getString(
        @StringRes resourceId: Int,
        fallback: String,
        vararg formatArgs: Any,
    ): String {
        return runCatching {
            resources.getString(resourceId, *formatArgs)
        }.onFailure {
            logResourceFailure("formatted string", resourceId, it)
        }.getOrDefault(fallback)
    }

    /** Returns a new module drawable instance, or `null` when it cannot be resolved. */
    fun getDrawable(@DrawableRes resourceId: Int): Drawable? {
        return runCatching {
            resources.getDrawable(resourceId, theme)
        }.onFailure {
            logResourceFailure("drawable", resourceId, it)
        }.getOrNull()
    }

    private fun logResourceFailure(type: String, resourceId: Int, throwable: Throwable) {
        logError(
            "Failed to load module $type resource: id=0x${resourceId.toString(16)}",
            throwable,
        )
    }

    companion object {

        /**
         * Creates a resource session aligned with the current host configuration.
         *
         * The module [android.content.pm.ApplicationInfo] supplied by libxposed lets Android load
         * the base APK, splits, overlays, and shared resource libraries as one resource set.
         */
        fun from(hostContext: Context): ModuleResources? {
            return runCatching {
                val applicationInfo = xposedModule.moduleApplicationInfo
                val resources = loadResources(hostContext, applicationInfo)
                val theme = applicationInfo.theme
                    .takeIf { it != 0 }
                    ?.let { themeResourceId ->
                        runCatching {
                            resources.newTheme().apply {
                                applyStyle(themeResourceId, true)
                            }
                        }.onFailure {
                            logError("Failed to create the module resource theme", it)
                        }.getOrNull()
                    }
                ModuleResources(resources, theme)
            }.onFailure {
                logError("Failed to create a module resource session", it)
            }.getOrNull()
        }

        @SuppressLint("NewApi")
        private fun loadResources(
            hostContext: Context,
            applicationInfo: android.content.pm.ApplicationInfo,
        ): Resources {
            return hostContext.packageManager.run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getResourcesForApplication(
                        applicationInfo,
                        Configuration(hostContext.resources.configuration),
                    )
                } else {
                    getResourcesForApplication(applicationInfo)
                }
            }
        }
    }
}
