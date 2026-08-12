package cn.buffcow.hyperste

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * @author qingyu
 * <p>Create on 2026/07/27 14:30</p>
 */
class MainModule : XposedModule() {

    private var processName: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        xposedModule = this
        processName = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (!shouldInstallHooks(param)) {
            return
        }
        logPackageReady(param)
        installHooks(param)
    }

    private fun shouldInstallHooks(
        param: XposedModuleInterface.PackageReadyParam,
    ): Boolean {
        return param.run {
            if (!isFirstPackage) {
                return@run false
            }
            val targetProcess = resolveTargetProcess(packageName) ?: return@run false
            if (this@MainModule.processName != targetProcess) {
                logDebug("skipped process: ${this@MainModule.processName}")
                return@run false
            }
            true
        }
    }

    private fun logPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        param.run {
            logDebug(
                "Target package ready: package=$packageName, " +
                        "process=${this@MainModule.processName}, " +
                        "pid=${android.os.Process.myPid()}, " +
                        "module=${System.identityHashCode(this@MainModule)}, " +
                        "classLoader=${System.identityHashCode(classLoader)}",
            )
        }
    }

    private fun installHooks(param: XposedModuleInterface.PackageReadyParam) {
        param.run {
            when (packageName) {
                SYSTEM_UI_PACKAGE -> {
                    // TODO: 2026/8/12
                }
            }
        }
    }

    private fun resolveTargetProcess(packageName: String): String? {
        return when (packageName) {
            SYSTEM_UI_PACKAGE -> SYSTEM_UI_PACKAGE
            else -> null
        }
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

internal lateinit var xposedModule: XposedModule
    private set
