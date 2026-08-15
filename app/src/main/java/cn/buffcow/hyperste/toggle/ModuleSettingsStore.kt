package cn.buffcow.hyperste.toggle

import android.content.Context
import android.provider.Settings

/**
 * Persists settings for the device-wide SystemUI quick-toggle dialog.
 *
 * A missing setting preserves legacy behavior by treating every registered toggle as enabled.
 * Collapsing the SystemUI panels after a state-change request is disabled by default for the same
 * reason.
 * Unknown IDs are retained when known selections are saved so configuration survives upgrades
 * and downgrades.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 17:50</p>
 */
internal class ModuleSettingsStore {

    /** Returns every persisted disabled toggle ID. */
    fun readDisabledIds(context: Context): Set<String> {
        return Settings.Global.getString(context.contentResolver, SETTING_DISABLED_IDS)
            ?.split(ID_SEPARATOR)
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()
    }

    /** Returns whether SystemUI panels collapse after a state-change request is submitted. */
    fun readCollapseAfterSwitching(context: Context): Boolean {
        val contentResolver = context.contentResolver
        val settingName = if (
            Settings.Global.getString(contentResolver, SETTING_COLLAPSE_AFTER_SWITCHING) != null
        ) {
            SETTING_COLLAPSE_AFTER_SWITCHING
        } else {
            LEGACY_SETTING_CLOSE_AFTER_SWITCHING
        }
        return Settings.Global.getInt(
            contentResolver,
            settingName,
            COLLAPSE_AFTER_SWITCHING_DISABLED,
        ) == COLLAPSE_AFTER_SWITCHING_ENABLED
    }

    /**
     * Saves the module settings while retaining toggle IDs unknown to this version.
     *
     * If either provider write fails, the previous values are restored on a best-effort basis and
     * the original failure is propagated to the caller.
     */
    fun writeSettings(
        context: Context,
        knownIds: Set<String>,
        disabledKnownIds: Set<String>,
        collapseAfterSwitching: Boolean,
    ) {
        require(disabledKnownIds.all(knownIds::contains)) {
            "Disabled toggle IDs must be part of the known registry"
        }
        require((knownIds + disabledKnownIds).none { ID_SEPARATOR in it }) {
            "Quick toggle IDs cannot contain the persistence separator"
        }
        val contentResolver = context.contentResolver
        val previousDisabledIds = Settings.Global.getString(contentResolver, SETTING_DISABLED_IDS)
        val previousCollapseAfterSwitching = Settings.Global.getString(
            contentResolver,
            SETTING_COLLAPSE_AFTER_SWITCHING,
        )
        val mergedIds = (readDisabledIds(context) - knownIds + disabledKnownIds).toSortedSet()
        try {
            check(
                Settings.Global.putString(
                    contentResolver,
                    SETTING_DISABLED_IDS,
                    mergedIds.joinToString(ID_SEPARATOR),
                ),
            ) {
                "Settings.Global rejected the quick-toggle selection change"
            }
            check(
                Settings.Global.putInt(
                    contentResolver,
                    SETTING_COLLAPSE_AFTER_SWITCHING,
                    if (collapseAfterSwitching) {
                        COLLAPSE_AFTER_SWITCHING_ENABLED
                    } else {
                        COLLAPSE_AFTER_SWITCHING_DISABLED
                    },
                ),
            ) {
                "Settings.Global rejected the collapse-after-switching setting change"
            }
        } catch (failure: Throwable) {
            runCatching {
                check(
                    Settings.Global.putString(
                        contentResolver,
                        SETTING_DISABLED_IDS,
                        previousDisabledIds,
                    ),
                ) {
                    "Settings.Global rejected the quick-toggle selection rollback"
                }
                check(
                    Settings.Global.putString(
                        contentResolver,
                        SETTING_COLLAPSE_AFTER_SWITCHING,
                        previousCollapseAfterSwitching,
                    ),
                ) {
                    "Settings.Global rejected the collapse-after-switching setting rollback"
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    companion object {
        private const val SETTING_DISABLED_IDS = "hyperste_disabled_quick_toggle_ids"
        private const val SETTING_COLLAPSE_AFTER_SWITCHING =
            "hyperste_collapse_system_ui_after_switching"
        private const val LEGACY_SETTING_CLOSE_AFTER_SWITCHING =
            "hyperste_close_quick_toggle_dialog_after_switching"
        private const val ID_SEPARATOR = ","
        private const val COLLAPSE_AFTER_SWITCHING_DISABLED = 0
        private const val COLLAPSE_AFTER_SWITCHING_ENABLED = 1
    }
}
