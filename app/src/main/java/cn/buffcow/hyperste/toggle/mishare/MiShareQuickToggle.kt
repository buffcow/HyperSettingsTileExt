package cn.buffcow.hyperste.toggle.mishare

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.extension.resolveString
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleAction
import cn.buffcow.hyperste.toggle.QuickToggleCategory
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState
import cn.buffcow.hyperste.toggle.systemui.CustomTileTarget

/**
 * Proxies the original HyperOS Mi Share custom tile while preserving its vendor preparation flow.
 *
 * The authoritative checked state remains the Mi Share system setting. A temporary custom tile is
 * used only when the user has not placed Mi Share in the current quick-settings layout, allowing
 * the original tile service to retain CTA, permission, Wi-Fi, and foreground-service behavior.
 *
 * @author qingyu
 * <p>Create on 2026/08/15 10:49</p>
 */
internal class MiShareQuickToggle : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.CONNECTIVITY
    override val titleRes: Int = R.string.quick_toggle_mi_share_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openMiShareSettings)

    private var nextRequestId = 0L
    private var pendingRequest: PendingRequest? = null
    private var recentFailure: RecentFailure? = null
    private var stateFailureLogged = false

    override fun readState(host: QuickToggleHost): QuickToggleState {
        return runCatching {
            if (!isMiShareServiceAvailable(host.context) ||
                !host.systemUiTileController.isSupported(TILE_TARGET)
            ) {
                return@runCatching QuickToggleState.UNAVAILABLE
            }

            val actualState = readCheckedState(host.context)
            reconcilePendingRequest(host, actualState)
            val now = SystemClock.elapsedRealtime()
            recentFailure = recentFailure?.takeIf { failure ->
                now - failure.occurredAtMillis < FAILURE_MESSAGE_DURATION_MS
            }
            val request = pendingRequest
            QuickToggleState(
                isAvailable = true,
                isEnabled = request == null,
                isChecked = when (request?.phase) {
                    PendingPhase.PREPARING,
                    PendingPhase.CLICK_DISPATCHED -> request.targetState

                    PendingPhase.FAILED,
                    null -> actualState
                },
                secondaryText = resolveStatusText(host, request),
            )
        }.onSuccess {
            stateFailureLogged = false
        }.onFailure {
            if (!stateFailureLogged) {
                stateFailureLogged = true
                logError("Failed to read the Mi Share state", it)
            }
        }.getOrDefault(QuickToggleState.UNAVAILABLE)
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        check(isMiShareServiceAvailable(host.context)) {
            "Mi Share tile service is unavailable"
        }
        check(host.systemUiTileController.isSupported(TILE_TARGET)) {
            "Mi Share SystemUI tile control is unavailable"
        }
        check(pendingRequest == null) {
            "A Mi Share state change is already in progress"
        }
        if (readCheckedState(host.context) == checked) {
            return
        }

        val request = PendingRequest(
            id = ++nextRequestId,
            targetState = checked,
        )
        pendingRequest = request
        recentFailure = null
        try {
            host.systemUiTileController.requestCustomTileClick(
                target = TILE_TARGET,
                onClickDispatched = {
                    pendingRequest?.takeIf { it.id == request.id }?.phase =
                        PendingPhase.CLICK_DISPATCHED
                },
                onFailure = { failure ->
                    handleControllerFailure(request.id, failure)
                },
                onFinished = {
                    handleControllerFinished(host.context.applicationContext, request.id)
                },
            )
        } catch (failure: Throwable) {
            pendingRequest = null
            recentFailure = RecentFailure(SystemClock.elapsedRealtime())
            throw failure
        }
    }

    private fun reconcilePendingRequest(host: QuickToggleHost, actualState: Boolean) {
        val request = pendingRequest ?: return
        if (request.phase == PendingPhase.FAILED ||
            request.targetState != actualState
        ) {
            return
        }
        if (
            host.systemUiTileController.finishCustomTileObservation(
                target = TILE_TARGET,
                checked = request.targetState,
            )
        ) {
            pendingRequest = null
            logDebug("Mi Share state changed: checked=${request.targetState}")
        }
    }

    private fun resolveStatusText(
        host: QuickToggleHost,
        request: PendingRequest?,
    ): CharSequence? {
        return when {
            request?.phase == PendingPhase.FAILED || recentFailure != null -> host.resolveString(
                R.string.quick_toggle_mi_share_failed,
                FALLBACK_FAILED,
            )

            request?.targetState == true -> host.resolveString(
                R.string.quick_toggle_mi_share_turning_on,
                FALLBACK_TURNING_ON,
            )

            request?.targetState == false -> host.resolveString(
                R.string.quick_toggle_mi_share_turning_off,
                FALLBACK_TURNING_OFF,
            )

            else -> null
        }
    }

    private fun handleControllerFailure(requestId: Long, failure: Throwable) {
        val request = pendingRequest?.takeIf { it.id == requestId } ?: return
        request.phase = PendingPhase.FAILED
        recentFailure = RecentFailure(SystemClock.elapsedRealtime())
        logError(
            "Failed to deliver the Mi Share SystemUI tile click: checked=${request.targetState}",
            failure,
        )
    }

    private fun handleControllerFinished(context: Context, requestId: Long) {
        val request = pendingRequest?.takeIf { it.id == requestId } ?: return
        val actualState = runCatching {
            readCheckedState(context)
        }.onFailure {
            logError("Failed to verify the Mi Share state after the tile click", it)
        }.getOrNull()
        if (request.phase != PendingPhase.FAILED) {
            if (actualState == request.targetState) {
                logDebug("Mi Share state changed: checked=${request.targetState}")
            } else {
                logDebug(
                    "Mi Share tile click finished without an observed state change: " +
                            "requested=${request.targetState}, actual=$actualState",
                )
            }
        }
        pendingRequest = null
    }

    private fun openMiShareSettings(host: QuickToggleHost) {
        val intent = Intent(TileServiceActions.QS_TILE_PREFERENCES).apply {
            setClassName(MI_SHARE_PACKAGE, MI_SHARE_SETTINGS_ACTIVITY)
            putExtra(Intent.EXTRA_COMPONENT_NAME, TILE_COMPONENT)
            putExtra(
                EXTRA_TILE_STATE,
                if (readCheckedState(host.context)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            )
        }
        host.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun isMiShareServiceAvailable(context: Context): Boolean {
        val packageManager = context.packageManager
        val serviceInfo = try {
            packageManager.getServiceInfo(
                TILE_COMPONENT,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        if (!serviceInfo.applicationInfo.enabled) {
            return false
        }
        return when (packageManager.getComponentEnabledSetting(TILE_COMPONENT)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false

            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> serviceInfo.enabled
        }
    }

    private fun readCheckedState(context: Context): Boolean {
        return Settings.System.getInt(
            context.contentResolver,
            MI_SHARE_STATUS_KEY,
            MI_SHARE_STATUS_UNINITIALIZED,
        ) == MI_SHARE_STATUS_ENABLED
    }

    private enum class PendingPhase {
        PREPARING,
        CLICK_DISPATCHED,
        FAILED,
    }

    private data class PendingRequest(
        val id: Long,
        val targetState: Boolean,
        var phase: PendingPhase = PendingPhase.PREPARING,
    )

    private data class RecentFailure(
        val occurredAtMillis: Long,
    )

    private object TileServiceActions {
        const val QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"
    }

    companion object {
        private const val ID = "mi_share"
        private const val FALLBACK_TITLE = "Mi Share"
        private const val FALLBACK_TURNING_ON = "Turning on…"
        private const val FALLBACK_TURNING_OFF = "Turning off…"
        private const val FALLBACK_FAILED = "Failed"
        private const val MI_SHARE_PACKAGE = "com.miui.mishare.connectivity"
        private const val MI_SHARE_TILE_SERVICE =
            "com.miui.mishare.connectivity.tile.MiShareTileService"
        private const val MI_SHARE_SETTINGS_ACTIVITY =
            "com.miui.mishare.activity.MiShareSettingsActivity"
        private const val MI_SHARE_STATUS_KEY = "mishare_switch_status"
        private const val MI_SHARE_STATUS_UNINITIALIZED = -1
        private const val MI_SHARE_STATUS_ENABLED = 2
        private const val EXTRA_TILE_STATE = "state"
        private const val FAILURE_MESSAGE_DURATION_MS = 5_000L

        private val TILE_COMPONENT = ComponentName(MI_SHARE_PACKAGE, MI_SHARE_TILE_SERVICE)
        private val TILE_TARGET = CustomTileTarget(
            component = TILE_COMPONENT,
            allowTemporaryCreation = true,
        )
    }
}
