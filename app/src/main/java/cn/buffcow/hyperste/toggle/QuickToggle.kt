package cn.buffcow.hyperste.toggle

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import cn.buffcow.hyperste.resource.ModuleResources

/**
 * Provides host-process services and module resources to a quick toggle.
 *
 * Activity launches are routed through SystemUI so keyguard dismissal and shade collapsing remain
 * consistent with built-in quick settings tiles.
 */
internal interface QuickToggleHost {

    /** Current SystemUI context used to resolve resources and construct actions. */
    val context: Context

    /** Module resource session aligned with the current SystemUI configuration. */
    val moduleResources: ModuleResources?

    /** Starts [intent] through SystemUI's activity-launch pipeline. */
    fun startActivity(intent: Intent)
}

/**
 * Performs an optional secondary action for a quick toggle.
 *
 * Implementations may throw when the action cannot be completed. The dialog consumes the
 * long-click event and reports the failure without triggering the toggle's primary action.
 */
internal fun interface QuickToggleAction {

    /** Performs the action using services exposed by [host]. */
    fun perform(host: QuickToggleHost)
}

/**
 * Defines a boolean system feature that can be rendered by the quick-toggle dialog.
 *
 * @author qingyu
 * <p>Create on 2026/08/12 17:56</p>
 */
internal interface QuickToggle {

    /** Stable identifier used for logging and duplicate removal. */
    val id: String

    /** Module string resource displayed next to the switch. */
    @get:StringRes
    val titleRes: Int

    /** English fallback used when [titleRes] cannot be loaded. */
    val fallbackTitle: String

    /** Optional module string resource displayed below the switch. */
    @get:StringRes
    val descriptionRes: Int?

    /** Optional English fallback used when [descriptionRes] cannot be loaded. */
    val fallbackDescription: String?

    /** Optional action performed when the toggle row is long-clicked. */
    val longClickAction: QuickToggleAction?
        get() = null

    /**
     * Reads the current system-backed state.
     *
     * Implementations may throw when a platform API cannot be reached. The dialog treats an
     * initial failure as unavailable and preserves the original Settings tile long-press action.
     */
    fun readState(host: QuickToggleHost): QuickToggleState

    /** Requests a new checked state from the backing system feature. */
    fun setChecked(host: QuickToggleHost, checked: Boolean)
}
