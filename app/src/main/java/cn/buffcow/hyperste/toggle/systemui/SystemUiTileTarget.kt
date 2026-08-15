package cn.buffcow.hyperste.toggle.systemui

import android.content.ComponentName

/**
 * Identifies a SystemUI quick-settings tile without erasing its lifecycle model.
 *
 * Custom `TileService` implementations and built-in SystemUI tiles require different creation,
 * listening, and cleanup behavior. Callers must therefore retain the concrete target type instead
 * of passing an untyped tile spec to a generic proxy.
 *
 * @author qingyu
 * <p>Create on 2026/08/15 10:49</p>
 */
internal sealed interface SystemUiTileTarget {

    /** Exact tile spec understood by the active SystemUI host. */
    val spec: String
}

/**
 * Identifies a tile backed by an Android [android.service.quicksettings.TileService].
 *
 * @property component service component encoded by the custom tile spec
 * @property allowTemporaryCreation whether this specifically vetted tile may be instantiated when
 * it is absent from the user's current quick-settings layout; temporary creation can trigger
 * `onTileAdded`, so callers must opt in only after confirming that the tile's lifecycle side
 * effects are safe and reversible
 * @author qingyu
 * <p>Create on 2026/08/15 10:49</p>
 */
internal data class CustomTileTarget(
    val component: ComponentName,
    val allowTemporaryCreation: Boolean = false,
) : SystemUiTileTarget {

    override val spec: String = "custom(${component.flattenToShortString()})"
}

/**
 * Identifies a built-in SystemUI tile.
 *
 * This type intentionally has no click implementation yet. Built-in tiles such as NFC, Bluetooth,
 * and flashlight are owned directly by SystemUI and must not be routed through the temporary
 * `CustomTile` lifecycle used for [CustomTileTarget].
 *
 * @property spec built-in tile spec exposed by the active SystemUI implementation
 * @author qingyu
 * <p>Create on 2026/08/15 10:49</p>
 */
internal data class BuiltInTileTarget(
    override val spec: String,
) : SystemUiTileTarget
