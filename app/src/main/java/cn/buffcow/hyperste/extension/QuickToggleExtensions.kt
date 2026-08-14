package cn.buffcow.hyperste.extension

import androidx.annotation.StringRes
import cn.buffcow.hyperste.toggle.QuickToggleHost

/**
 * Resolves a module string resource while retaining a safe fallback for resource-loading failures.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 16:36</p>
 */
internal fun QuickToggleHost.resolveString(
    @StringRes resourceId: Int,
    fallback: String,
): String {
    return moduleResources?.getString(resourceId, fallback) ?: fallback
}
