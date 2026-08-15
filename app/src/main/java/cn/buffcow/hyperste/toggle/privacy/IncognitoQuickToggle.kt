package cn.buffcow.hyperste.toggle.privacy

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.extension.findMethod
import cn.buffcow.hyperste.extension.invokeUnwrapped
import cn.buffcow.hyperste.extension.resolveString
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleAction
import cn.buffcow.hyperste.toggle.QuickToggleActionUnavailableException
import cn.buffcow.hyperste.toggle.QuickToggleCategory
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState
import cn.buffcow.hyperste.toggle.systemui.CustomTileTarget

/**
 * Proxies the original HyperOS Incognito custom tile for the current foreground user.
 *
 * The Security Center tile owns the complete state-change flow, including the secure setting,
 * persistent system property, AppOps restrictions, internal service, and status-bar indicator.
 * This implementation therefore reads the authoritative secure setting but never writes it
 * directly.
 *
 * @author qingyu
 * <p>Create on 2026/08/15 16:50</p>
 */
internal class IncognitoQuickToggle : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.SERVICES
    override val titleRes: Int = R.string.quick_toggle_incognito_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openIncognitoSettings)

    private val backendResult by lazy(LazyThreadSafetyMode.NONE) {
        runCatching(::IncognitoBackend)
    }
    private var nextRequestId = 0L
    private var pendingRequest: PendingRequest? = null
    private var recentFailure: RecentFailure? = null
    private var backendFailureLogged = false
    private var stateFailureLogged = false

    override fun readState(host: QuickToggleHost): QuickToggleState {
        return runCatching {
            val backend = getBackend() ?: return@runCatching QuickToggleState.UNAVAILABLE
            val userContext = backend.createCurrentUserContext(host.context)
            if (!isTileServiceAvailable(userContext) ||
                !host.systemUiTileController.isSupported(TILE_TARGET)
            ) {
                return@runCatching QuickToggleState.UNAVAILABLE
            }

            val actualState = backend.isChecked(userContext)
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
                logError("Failed to read the Incognito state", it)
            }
        }.getOrDefault(QuickToggleState.UNAVAILABLE)
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        val backend = getBackend() ?: error("Incognito state control is unavailable")
        val userContext = backend.createCurrentUserContext(host.context)
        check(isTileServiceAvailable(userContext)) {
            "Incognito tile service is unavailable"
        }
        check(host.systemUiTileController.isSupported(TILE_TARGET)) {
            "Incognito SystemUI tile control is unavailable"
        }
        check(pendingRequest == null) {
            "An Incognito state change is already in progress"
        }
        if (backend.isChecked(userContext) == checked) {
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
                    handleControllerFinished(
                        context = host.context.applicationContext ?: host.context,
                        requestId = request.id,
                    )
                },
            )
        } catch (failure: Throwable) {
            pendingRequest = null
            recentFailure = RecentFailure(SystemClock.elapsedRealtime())
            throw failure
        }
    }

    private fun getBackend(): IncognitoBackend? {
        return backendResult.onFailure {
            if (!backendFailureLogged) {
                backendFailureLogged = true
                logError("Failed to initialize Incognito state control", it)
            }
        }.getOrNull()
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
            logDebug("Incognito state changed: checked=${request.targetState}")
        }
    }

    private fun resolveStatusText(
        host: QuickToggleHost,
        request: PendingRequest?,
    ): CharSequence? {
        return when {
            request?.phase == PendingPhase.FAILED || recentFailure != null -> host.resolveString(
                R.string.quick_toggle_incognito_failed,
                FALLBACK_FAILED,
            )

            request?.targetState == true -> host.resolveString(
                R.string.quick_toggle_incognito_turning_on,
                FALLBACK_TURNING_ON,
            )

            request?.targetState == false -> host.resolveString(
                R.string.quick_toggle_incognito_turning_off,
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
            "Failed to deliver the Incognito SystemUI tile click: checked=${request.targetState}",
            failure,
        )
    }

    private fun handleControllerFinished(context: Context, requestId: Long) {
        val request = pendingRequest?.takeIf { it.id == requestId } ?: return
        val actualState = runCatching {
            val backend = getBackend() ?: error("Incognito state control is unavailable")
            backend.isChecked(backend.createCurrentUserContext(context))
        }.onFailure {
            logError("Failed to verify the Incognito state after the tile click", it)
        }.getOrNull()
        if (request.phase != PendingPhase.FAILED) {
            if (actualState == request.targetState) {
                logDebug("Incognito state changed: checked=${request.targetState}")
            } else {
                recentFailure = RecentFailure(SystemClock.elapsedRealtime())
                logDebug(
                    "Incognito tile click finished without an observed state change: " +
                            "requested=${request.targetState}, actual=$actualState",
                )
            }
        }
        pendingRequest = null
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openIncognitoSettings(host: QuickToggleHost) {
        val intent = Intent(INCOGNITO_SETTINGS_ACTION).apply {
            setClassName(SECURITY_CENTER_PACKAGE, INCOGNITO_SETTINGS_ACTIVITY)
        }
        if (
            intent.resolveActivityInfo(
                host.context.packageManager,
                PackageManager.MATCH_DEFAULT_ONLY,
            ) == null
        ) {
            throw QuickToggleActionUnavailableException(
                "Incognito settings activity is unavailable",
            )
        }
        host.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun isTileServiceAvailable(context: Context): Boolean {
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

    private class IncognitoBackend {

        private val getCurrentUserMethod = ActivityManager::class.java.findMethod(
            GET_CURRENT_USER_METHOD,
            0,
        )
        private val userHandleOfMethod = UserHandle::class.java.findMethod(
            USER_HANDLE_OF_METHOD,
            Int::class.javaPrimitiveType!!,
        )
        private val createContextAsUserMethod = Context::class.java.findMethod(
            CREATE_CONTEXT_AS_USER_METHOD,
            UserHandle::class.java,
            Int::class.javaPrimitiveType!!,
        )

        fun createCurrentUserContext(context: Context): Context {
            val currentUserId = (getCurrentUserMethod.invokeUnwrapped(null) as? Number)?.toInt()
                ?: error("ActivityManager.getCurrentUser() returned a non-numeric value")
            check(currentUserId >= 0) {
                "ActivityManager.getCurrentUser() returned an invalid user ID: $currentUserId"
            }
            val userHandle = userHandleOfMethod.invokeUnwrapped(null, currentUserId) as? UserHandle
                ?: error("UserHandle.of() returned an invalid value")
            return createContextAsUserMethod.invokeUnwrapped(
                context.applicationContext ?: context,
                userHandle,
                CONTEXT_AS_USER_FLAGS,
            ) as? Context ?: error("Context.createContextAsUser() returned an invalid value")
        }

        fun isChecked(context: Context): Boolean {
            return Settings.Secure.getInt(
                context.contentResolver,
                INCOGNITO_STATE_KEY,
                STATE_DISABLED,
            ) == STATE_ENABLED
        }
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

    companion object {
        private const val ID = "incognito"
        private const val FALLBACK_TITLE = "Incognito"
        private const val FALLBACK_TURNING_ON = "Turning on…"
        private const val FALLBACK_TURNING_OFF = "Turning off…"
        private const val FALLBACK_FAILED = "Failed"

        private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
        private const val INCOGNITO_TILE_SERVICE =
            "com.miui.permcenter.settings.InvisibleModeTileService"
        private const val INCOGNITO_SETTINGS_ACTIVITY =
            "com.miui.permcenter.settings.InvisibleModeActivity"
        private const val INCOGNITO_SETTINGS_ACTION =
            "com.miui.securitycenter.action.INVISIBLE_SETTING"
        private const val INCOGNITO_STATE_KEY = "key_invisible_mode_state"

        private const val GET_CURRENT_USER_METHOD = "getCurrentUser"
        private const val USER_HANDLE_OF_METHOD = "of"
        private const val CREATE_CONTEXT_AS_USER_METHOD = "createContextAsUser"
        private const val CONTEXT_AS_USER_FLAGS = 0
        private const val STATE_DISABLED = 0
        private const val STATE_ENABLED = 1
        private const val FAILURE_MESSAGE_DURATION_MS = 5_000L

        private val TILE_COMPONENT = ComponentName(
            SECURITY_CENTER_PACKAGE,
            INCOGNITO_TILE_SERVICE,
        )
        private val TILE_TARGET = CustomTileTarget(
            component = TILE_COMPONENT,
            allowTemporaryCreation = true,
        )
    }
}
