package cn.buffcow.hyperste.hook

import android.annotation.SuppressLint
import android.content.Context
import cn.buffcow.hyperste.dialog.SystemUiQuickToggleDialog
import cn.buffcow.hyperste.toggle.QuickToggleRegistry
import cn.buffcow.hyperste.toggle.QuickToggleSelectionStore
import io.github.libxposed.api.XposedInterface.Invoker

/**
 * Intercepts the Control Center Settings tile long-press entry point and displays
 * supported system-backed quick toggles.
 *
 * @author qingyu
 * <p>Create on 2026/08/12 16:05</p>
 */
internal class SettingsTileLongPressHook(
    private val classLoader: ClassLoader,
) : HookInstaller() {

    @SuppressLint("PrivateApi")
    override fun install() {
        val qsTileImplClass = classLoader.loadClass(QS_TILE_IMPL_CLASS)
        val settingsTileClass = classLoader.loadClass(SETTINGS_TILE_CLASS)
        val expandableClass = classLoader.loadClass(EXPANDABLE_CLASS)
        val contextField = qsTileImplClass.getDeclaredField(CONTEXT_FIELD).apply {
            isAccessible = true
        }
        val activityStarterField = qsTileImplClass.getDeclaredField(ACTIVITY_STARTER_FIELD).apply {
            isAccessible = true
        }
        val quickToggleDialog = SystemUiQuickToggleDialog(
            classLoader = classLoader,
            registry = QuickToggleRegistry(classLoader),
            selectionStore = QuickToggleSelectionStore(),
        )

        val longClickMethod = qsTileImplClass.getDeclaredMethod("longClick", expandableClass).apply {
            isAccessible = true
        }
        val originalLongClickInvoker = module.getInvoker(longClickMethod)
            .setType(Invoker.Type.ORIGIN)
        module.hook(longClickMethod).intercept { chain ->
            val receiver = chain.thisObject
            if (receiver?.javaClass != settingsTileClass) {
                return@intercept chain.proceed()
            }

            val dialogShown = runCatching {
                val expandable = chain.args[0]
                val context = contextField.get(receiver) as? Context
                    ?: error("SettingsTile.mContext is null")
                val activityStarter = activityStarterField.get(receiver)
                    ?: error("SettingsTile.mActivityStarter is null")
                quickToggleDialog.show(context, activityStarter) {
                    originalLongClickInvoker.invoke(receiver, expandable)
                }
            }.getOrElse {
                logE(
                    "Failed to show the SettingsTile long-press quick toggle dialog; " +
                            "falling back to the original system behavior",
                    it,
                )
                false
            }

            if (dialogShown) {
                null
            } else {
                chain.proceed()
            }
        }
        logD("SettingsTile long-press hook installed")
    }

    companion object {
        private const val QS_TILE_IMPL_CLASS = "com.android.systemui.qs.tileimpl.QSTileImpl"
        private const val SETTINGS_TILE_CLASS = "com.android.systemui.qs.tiles.SettingsTile"
        private const val EXPANDABLE_CLASS = "com.android.systemui.animation.Expandable"
        private const val CONTEXT_FIELD = "mContext"
        private const val ACTIVITY_STARTER_FIELD = "mActivityStarter"
    }
}
