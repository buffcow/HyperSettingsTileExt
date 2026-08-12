package cn.buffcow.hyperste.hook

import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.xposedModule
import io.github.libxposed.api.XposedModule

/**
 * Defines a group of hooks that can be installed independently in a target process.
 *
 * @author qingyu
 * <p>Create on 2026/07/28 15:14</p>
 */
internal abstract class HookInstaller {

    protected val module: XposedModule get() = xposedModule

    /**
     * Installs every hook managed by this installer into the target process.
     *
     * Implementations should isolate expected resolution and installation failures so that
     * one failed hook does not affect the target application or other installers.
     */
    abstract fun install()

    protected fun logD(message: String) {
        logDebug(message)
    }

    protected fun logE(message: String, throwable: Throwable? = null) {
        logError(message, throwable)
    }
}
