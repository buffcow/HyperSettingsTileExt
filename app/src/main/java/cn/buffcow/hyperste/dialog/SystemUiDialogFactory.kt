package cn.buffcow.hyperste.dialog

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import cn.buffcow.hyperste.extension.findField
import cn.buffcow.hyperste.extension.findMethod
import cn.buffcow.hyperste.extension.invokeUnwrapped

/**
 * Creates dialogs with SystemUI's native dialog implementation and window behavior.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 17:50</p>
 */
@SuppressLint("PrivateApi")
internal class SystemUiDialogFactory(private val classLoader: ClassLoader) {

    // Resolve on first use because SystemUI initializes its dependencies after package-ready.
    private val createDialog by lazy(::resolveDialogCreator)

    /** Creates a new dialog bound to [context]. */
    fun create(context: Context): AlertDialog {
        return createDialog(context)
    }

    private fun resolveDialogCreator(): (Context) -> AlertDialog {
        val dialogClass = classLoader.loadClass(SYSTEM_UI_DIALOG_CLASS)
            .asSubclass(AlertDialog::class.java)
        val constructor = try {
            dialogClass.getConstructor(Context::class.java)
        } catch (_: NoSuchMethodException) {
            return resolveFactoryCreator()
        }
        constructor.isAccessible = true
        return { context -> constructor.newInstance(context) }
    }

    private fun resolveFactoryCreator(): (Context) -> AlertDialog {
        val dependencyClass = classLoader.loadClass(DEPENDENCY_CLASS)
        val tunerServiceClass = classLoader.loadClass(TUNER_SERVICE_CLASS)
        val tunerService = dependencyClass.run {
            val dependency = findField("sDependency").get(null)
                ?: error("SystemUI Dependency is not initialized")
            findMethod("getDependencyInner", Any::class.java)
                .invokeUnwrapped(dependency, tunerServiceClass)
                ?: error("SystemUI TunerService is unavailable")
        }

        // HyperOS 4's TunerFragment uses this lazy to obtain the injected dialog factory.
        val factoryLazy = tunerService.javaClass.findField("mSystemUIDialogFactoryLazy")
            .get(tunerService) ?: error("SystemUIDialog factory provider is unavailable")
        val factory = factoryLazy.javaClass.findMethod("get", 0).invokeUnwrapped(factoryLazy)
            ?: error("SystemUIDialog factory is unavailable")
        val createMethod = factory.javaClass.findMethod("create", Context::class.java)
        return { context -> createMethod.invokeUnwrapped(factory, context) as AlertDialog }
    }

    companion object {
        private const val SYSTEM_UI_DIALOG_CLASS = "com.android.systemui.statusbar.phone.SystemUIDialog"
        private const val DEPENDENCY_CLASS = "com.android.systemui.Dependency"
        private const val TUNER_SERVICE_CLASS = "com.android.systemui.tuner.TunerService"
    }
}
