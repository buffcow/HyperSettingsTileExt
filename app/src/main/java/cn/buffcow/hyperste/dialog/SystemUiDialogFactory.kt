package cn.buffcow.hyperste.dialog

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context

/**
 * Creates dialogs with SystemUI's native dialog implementation and window behavior.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 17:50</p>
 */
@SuppressLint("PrivateApi")
internal class SystemUiDialogFactory(classLoader: ClassLoader) {

    private val constructor = classLoader.loadClass(SYSTEM_UI_DIALOG_CLASS)
        .getConstructor(Context::class.java)
        .apply {
            isAccessible = true
        }

    /** Creates a new dialog bound to [context]. */
    fun create(context: Context): AlertDialog {
        return constructor.newInstance(context) as AlertDialog
    }

    companion object {
        private const val SYSTEM_UI_DIALOG_CLASS = "com.android.systemui.statusbar.phone.SystemUIDialog"
    }
}
