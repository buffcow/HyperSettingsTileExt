/**
 * @author qingyu
 * <p>Create on 2026/08/05 15:12</p>
 */
package cn.buffcow.hyperste

import android.util.Log

fun logDebug(message: String) {
    xposedModule.log(Log.DEBUG, TAG, message)
}

fun logError(message: String, throwable: Throwable? = null) {
    if (throwable == null) {
        xposedModule.log(Log.ERROR, TAG, message)
    } else {
        xposedModule.log(Log.ERROR, TAG, message, throwable)
    }
}

private const val TAG = "HyperSettingsTileExt"
