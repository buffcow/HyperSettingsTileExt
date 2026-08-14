package cn.buffcow.hyperste.toggle

import android.content.Context
import android.provider.Settings

/**
 * Persists the quick-toggle IDs hidden from the device-wide SystemUI dialog.
 *
 * A missing setting preserves legacy behavior by treating every registered toggle as enabled.
 * Unknown IDs are retained when known selections are saved so configuration survives upgrades
 * and downgrades.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 17:50</p>
 */
internal class QuickToggleSelectionStore {

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

    /**
     * Replaces the disabled state of [knownIds] while retaining IDs unknown to this version.
     */
    fun writeKnownDisabledIds(
        context: Context,
        knownIds: Set<String>,
        disabledKnownIds: Set<String>,
    ) {
        require(disabledKnownIds.all(knownIds::contains)) {
            "Disabled toggle IDs must be part of the known registry"
        }
        require((knownIds + disabledKnownIds).none { ID_SEPARATOR in it }) {
            "Quick toggle IDs cannot contain the persistence separator"
        }
        val mergedIds = (readDisabledIds(context) - knownIds + disabledKnownIds).toSortedSet()
        check(
            Settings.Global.putString(
                context.contentResolver,
                SETTING_DISABLED_IDS,
                mergedIds.joinToString(ID_SEPARATOR),
            ),
        ) {
            "Settings.Global rejected the quick-toggle selection change"
        }
    }

    companion object {
        private const val SETTING_DISABLED_IDS = "hyperste_disabled_quick_toggle_ids"
        private const val ID_SEPARATOR = ","
    }
}
