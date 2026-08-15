package cn.buffcow.hyperste.toggle

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.resource.ModuleResources
import cn.buffcow.hyperste.toggle.systemui.SystemUiTileController

/** Declares the visual group that owns a quick toggle. */
internal enum class QuickToggleCategory(
    @get:StringRes val titleRes: Int,
    val fallbackTitle: String,
) {
    SERVICES(
        titleRes = R.string.quick_toggle_category_services,
        fallbackTitle = "Services",
    ),
    CONNECTIVITY(
        titleRes = R.string.quick_toggle_category_connectivity,
        fallbackTitle = "Connectivity",
    ),
    BATTERY(
        titleRes = R.string.quick_toggle_category_battery,
        fallbackTitle = "Battery",
    ),
    DISPLAY(
        titleRes = R.string.quick_toggle_category_display,
        fallbackTitle = "Display",
    ),
    DEVELOPER_OPTIONS(
        titleRes = R.string.quick_toggle_category_developer_options,
        fallbackTitle = "Developer options",
    ),
}

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

    /**
     * Controller bound to the active SystemUI quick-settings host.
     *
     * Feature implementations must use a concrete tile target type so custom `TileService`
     * instances cannot accidentally share the lifecycle strategy of built-in SystemUI tiles.
     */
    val systemUiTileController: SystemUiTileController

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

/** Indicates that an optional quick-toggle action has no destination on the current system. */
internal class QuickToggleActionUnavailableException(message: String) : IllegalStateException(message)

/**
 * Defines a boolean system feature that can be rendered by the quick-toggle dialog.
 *
 * @author qingyu
 * <p>Create on 2026/08/12 17:56</p>
 */
internal interface QuickToggle {

    /** Stable identifier used for logging and duplicate removal. */
    val id: String

    /** Visual category used to group the toggle in the dialog. */
    val category: QuickToggleCategory

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
     * Whether the global collapse-after-switching preference may collapse SystemUI after this
     * toggle is enabled.
     *
     * Implementations should return `false` when the dialog must remain visible while an enabled
     * state resolves asynchronous information. Disabling a toggle is not restricted by this
     * capability. This capability also does not affect manual dialog dismissal or long-click
     * actions.
     */
    val canCollapseAfterEnabling: Boolean
        get() = true

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
