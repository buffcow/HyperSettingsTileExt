package cn.buffcow.hyperste.toggle

/**
 * Describes the visibility, interactivity, and checked value of a quick toggle.
 *
 * @property isAvailable whether the feature should be displayed on this device
 * @property isEnabled whether the user may currently change the feature
 * @property isChecked the current system-backed switch value
 * @property secondaryText dynamic supplementary text displayed below the title; `null` uses the
 * toggle's static description, if one is defined
 * @author qingyu
 * <p>Create on 2026/08/12 17:56</p>
 */
internal data class QuickToggleState(
    val isAvailable: Boolean,
    val isEnabled: Boolean,
    val isChecked: Boolean,
    val secondaryText: CharSequence? = null,
) {

    companion object {
        val UNAVAILABLE = QuickToggleState(
            isAvailable = false,
            isEnabled = false,
            isChecked = false,
        )
    }
}
