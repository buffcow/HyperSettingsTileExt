package cn.buffcow.hyperste.extension

import android.content.Context
import android.content.pm.PackageManager

/**
 * Returns whether the context has every requested permission.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 16:36</p>
 */
internal fun Context.hasPermissions(vararg permissions: String): Boolean {
    return permissions.all { permission ->
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
