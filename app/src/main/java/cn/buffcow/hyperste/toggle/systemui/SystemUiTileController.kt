package cn.buffcow.hyperste.toggle.systemui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.buffcow.hyperste.extension.findMethod
import cn.buffcow.hyperste.extension.invokeUnwrapped
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Delivers one click through an existing or explicitly vetted temporary SystemUI tile instance.
 *
 * A successful callback only means that the click reached the SystemUI tile object. The backing
 * feature may still require permissions, user interaction, or asynchronous service work, so the
 * feature implementation remains responsible for observing its authoritative state. Temporary
 * custom tiles are kept alive long enough for queued `TileService` clicks and are then removed with
 * SystemUI's own custom-tile cleanup path.
 *
 * @author qingyu
 * <p>Create on 2026/08/15 10:49</p>
 */
@SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi")
internal class SystemUiTileController(
    classLoader: ClassLoader,
    private val qsHost: Any,
    hostContext: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val systemUiContext = hostContext.applicationContext ?: hostContext
    private val reflectionResult = runCatching {
        TileReflection(classLoader, qsHost)
    }
    private val activeCustomSessions = mutableMapOf<String, CustomTileSession>()
    private var reflectionFailureLogged = false

    /**
     * Reports whether [target] has a compatible implementation in the current SystemUI build.
     *
     * Built-in targets deliberately return `false` until a separate lifecycle strategy is added.
     * Custom targets are supported when an existing tile can be reused, or when the target has
     * explicitly allowed temporary creation and SystemUI exposes the matching removal path.
     */
    fun isSupported(target: SystemUiTileTarget): Boolean {
        reflectionResult.exceptionOrNull()?.let { failure ->
            if (!reflectionFailureLogged) {
                reflectionFailureLogged = true
                logError("Failed to initialize SystemUI tile control", failure)
            }
        }
        return when (target) {
            is CustomTileTarget -> runCatching {
                val reflection = reflectionResult.getOrThrow()
                reflection.findExistingTile(qsHost, target.spec) != null ||
                        target.allowTemporaryCreation && reflection.canRestoreTemporaryTile
            }.getOrDefault(false)

            is BuiltInTileTarget -> false
        }
    }

    /**
     * Starts one asynchronous click session for [target].
     *
     * [onClickDispatched] runs after the SystemUI tile accepts the click call, not after the backing
     * feature changes state. [onFailure] is reserved for controller, reflection, creation, or
     * readiness failures. Once a session has been created, [onFinished] runs after listeners and
     * any owned temporary tile are cleaned up. Validation failures that occur before session
     * creation are thrown to the caller. Only one session per custom component may be active at a
     * time.
     */
    fun requestCustomTileClick(
        target: CustomTileTarget,
        onClickDispatched: () -> Unit,
        onFailure: (Throwable) -> Unit,
        onFinished: () -> Unit,
    ) {
        checkMainThread()
        check(target.spec !in activeCustomSessions) {
            "A SystemUI custom tile click is already in progress: spec=${target.spec}"
        }

        val reflection = reflectionResult.getOrThrow()
        val existingTile = reflection.findExistingTile(qsHost, target.spec)
        val ownsTile = existingTile == null
        if (ownsTile) {
            check(target.allowTemporaryCreation) {
                "Temporary SystemUI custom tile creation is not allowed: spec=${target.spec}"
            }
            check(reflection.canRestoreTemporaryTile) {
                "SystemUI cannot restore temporary custom tile lifecycle state: spec=${target.spec}"
            }
        }
        val wasMarkedAdded = !ownsTile || isMarkedAdded(target.component)
        val tile = existingTile ?: reflection.createTile(qsHost, target.spec)
        check(reflection.isCustomTile(tile)) {
            "SystemUI returned a non-CustomTile instance for spec=${target.spec}"
        }

        val session = CustomTileSession(
            target = target,
            tile = tile,
            ownsTile = ownsTile,
            tileUserId = if (ownsTile) reflection.getTileUserId(tile) else null,
            wasMarkedAdded = wasMarkedAdded,
            onClickDispatched = onClickDispatched,
            onFailure = onFailure,
            onFinished = onFinished,
        )
        activeCustomSessions[target.spec] = session
        runCatching {
            reflection.setListening(tile, session.listeningOwner, true)
            reflection.refreshState(tile)
            pollUntilReady(reflection, session)
        }.onFailure { failure ->
            failSession(reflection, session, failure)
        }
    }

    /**
     * Signals that the feature owning [target] observed its requested state.
     *
     * The controller still keeps the tile alive for a short grace period so an asynchronously
     * queued `TileService` click can finish before the listening token and temporary instance are
     * released. The result is `true` only when an active, already-dispatched session accepted the
     * completion signal.
     */
    fun finishCustomTileObservation(target: CustomTileTarget): Boolean {
        checkMainThread()
        val session = activeCustomSessions[target.spec] ?: return false
        if (!session.clickDispatched) {
            return false
        }
        if (session.cleanupScheduled) {
            return true
        }
        session.cleanupScheduled = true
        session.observationTimeout?.let { callback -> mainHandler.removeCallbacks(callback) }
        mainHandler.postDelayed(
            { cleanupSession(reflectionResult.getOrThrow(), session) },
            COMPLETION_GRACE_PERIOD_MS,
        )
        return true
    }

    private fun pollUntilReady(reflection: TileReflection, session: CustomTileSession) {
        val deadline = SystemClock.elapsedRealtime() + READINESS_TIMEOUT_MS
        lateinit var readinessPoll: Runnable
        readinessPoll = Runnable {
            if (activeCustomSessions[session.target.spec] !== session) {
                return@Runnable
            }
            runCatching {
                if (reflection.getTileState(session.tile) != TILE_STATE_UNAVAILABLE) {
                    dispatchClick(reflection, session)
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    error("SystemUI custom tile did not become ready: spec=${session.target.spec}")
                } else {
                    mainHandler.postDelayed(readinessPoll, READINESS_POLL_INTERVAL_MS)
                }
            }.onFailure { failure ->
                failSession(reflection, session, failure)
            }
        }
        session.readinessPoll = readinessPoll
        mainHandler.post(readinessPoll)
    }

    private fun dispatchClick(reflection: TileReflection, session: CustomTileSession) {
        session.readinessPoll?.let { callback -> mainHandler.removeCallbacks(callback) }
        reflection.click(session.tile)
        session.clickDispatched = true
        logDebug(
            "SystemUI custom tile click dispatched: " +
                    "spec=${session.target.spec}, temporary=${session.ownsTile}",
        )
        runCatching(session.onClickDispatched).onFailure { failure ->
            failSession(reflection, session, failure)
            return
        }
        val observationTimeout = Runnable {
            if (activeCustomSessions[session.target.spec] === session) {
                cleanupSession(reflection, session)
            }
        }
        session.observationTimeout = observationTimeout
        mainHandler.postDelayed(observationTimeout, OBSERVATION_TIMEOUT_MS)
    }

    private fun failSession(
        reflection: TileReflection,
        session: CustomTileSession,
        failure: Throwable,
    ) {
        if (activeCustomSessions[session.target.spec] !== session || session.failureReported) {
            return
        }
        session.failureReported = true
        session.readinessPoll?.let { callback -> mainHandler.removeCallbacks(callback) }
        session.observationTimeout?.let { callback -> mainHandler.removeCallbacks(callback) }
        runCatching {
            session.onFailure(failure)
        }.onFailure { callbackFailure ->
            logError("Failed to report a SystemUI custom tile controller failure", callbackFailure)
        }
        cleanupSession(reflection, session)
    }

    private fun cleanupSession(reflection: TileReflection, session: CustomTileSession) {
        if (activeCustomSessions[session.target.spec] !== session || session.cleanupStarted) {
            return
        }
        session.cleanupStarted = true
        session.readinessPoll?.let { callback -> mainHandler.removeCallbacks(callback) }
        session.observationTimeout?.let { callback -> mainHandler.removeCallbacks(callback) }
        runCatching {
            reflection.setListening(session.tile, session.listeningOwner, false)
        }.onFailure {
            logError("Failed to stop listening to a SystemUI custom tile", it)
        }

        if (!session.ownsTile) {
            finishCleanup(session)
            return
        }
        runCatching {
            reflection.destroy(session.tile)
        }.onFailure {
            logError("Failed to destroy a temporary SystemUI custom tile", it)
        }
        mainHandler.postDelayed(
            {
                runCatching {
                    if (!session.wasMarkedAdded) {
                        reflection.restoreTemporaryTileLifecycle(
                            userId = checkNotNull(session.tileUserId),
                            component = session.target.component,
                        )
                    }
                }.onFailure {
                    logError(
                        "Failed to restore temporary SystemUI custom tile lifecycle state: " +
                                "spec=${session.target.spec}",
                        it,
                    )
                }
                finishCleanup(session)
            },
            TEMPORARY_TILE_REMOVAL_DELAY_MS,
        )
    }

    private fun finishCleanup(session: CustomTileSession) {
        if (activeCustomSessions.remove(session.target.spec, session)) {
            runCatching(session.onFinished).onFailure {
                logError("Failed to finish a SystemUI custom tile controller callback", it)
            }
            logDebug(
                "SystemUI custom tile click session finished: " +
                        "spec=${session.target.spec}, temporary=${session.ownsTile}",
            )
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SystemUI tile control must run on the main thread"
        }
    }

    private fun isMarkedAdded(component: ComponentName): Boolean {
        return systemUiContext.getSharedPreferences(TILE_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(component.flattenToString(), false)
    }

    private class TileReflection(classLoader: ClassLoader, qsHost: Any) {

        private val customTileClass = classLoader.loadClass(CUSTOM_TILE_CLASS)
        private val getTilesMethod = qsHost.javaClass.findMethod(GET_TILES_METHOD, 0)
        private val createTileMethod = qsHost.javaClass.findMethod(
            CREATE_TILE_METHOD,
            String::class.java,
        )
        private val getTileSpecMethod = customTileClass.findMethod(GET_TILE_SPEC_METHOD, 0)
        private val getStateMethod = customTileClass.findMethod(GET_STATE_METHOD, 0)
        private val setListeningMethod = customTileClass.findMethod(SET_LISTENING_METHOD, 2)
        private val refreshStateMethod = customTileClass.findMethod(REFRESH_STATE_METHOD, 0)
        private val clickMethod = customTileClass.findMethod(CLICK_METHOD, 1)
        private val destroyMethod = customTileClass.findMethod(DESTROY_METHOD, 0)
        private val getUserMethod = customTileClass.findMethod(GET_USER_METHOD, 0)
        private val interactor: Any?
        private val onCustomTileRemovedMethod: Method?

        init {
            val removalReflection = runCatching {
                val hostInteractor = qsHost.javaClass.findField(INTERACTOR_FIELD).run {
                    isAccessible = true
                    get(qsHost) ?: error("MiuiQSHostAdapter.interactor is null")
                }
                hostInteractor to hostInteractor.javaClass.findMethod(
                    ON_CUSTOM_TILE_REMOVED_METHOD,
                    Int::class.javaPrimitiveType!!,
                    ComponentName::class.java,
                )
            }.getOrNull()
            interactor = removalReflection?.first
            onCustomTileRemovedMethod = removalReflection?.second
        }

        val canRestoreTemporaryTile: Boolean
            get() = interactor != null && onCustomTileRemovedMethod != null

        fun findExistingTile(qsHost: Any, spec: String): Any? {
            val tiles = getTilesMethod.invokeUnwrapped(qsHost) as? Iterable<*> ?: return null
            return tiles.firstOrNull { tile ->
                tile != null && runCatching {
                    getTileSpecMethod.invokeUnwrapped(tile) == spec
                }.getOrDefault(false)
            }
        }

        fun createTile(qsHost: Any, spec: String): Any {
            return createTileMethod.invokeUnwrapped(qsHost, spec)
                ?: error("SystemUI failed to create a custom tile: spec=$spec")
        }

        fun isCustomTile(tile: Any): Boolean = customTileClass.isInstance(tile)

        fun getTileUserId(tile: Any): Int {
            return getUserMethod.invokeUnwrapped(tile) as? Int
                ?: error("CustomTile.getUser() returned a non-integer value")
        }

        fun setListening(tile: Any, owner: Any, listening: Boolean) {
            setListeningMethod.invokeUnwrapped(tile, owner, listening)
        }

        fun refreshState(tile: Any) {
            refreshStateMethod.invokeUnwrapped(tile)
        }

        fun getTileState(tile: Any): Int {
            val state = getStateMethod.invokeUnwrapped(tile)
                ?: error("SystemUI custom tile state is null")
            return state.javaClass.findField(STATE_FIELD).run {
                isAccessible = true
                getInt(state)
            }
        }

        fun click(tile: Any) {
            clickMethod.invokeUnwrapped(tile, null)
        }

        fun destroy(tile: Any) {
            destroyMethod.invokeUnwrapped(tile)
        }

        fun restoreTemporaryTileLifecycle(userId: Int, component: ComponentName) {
            val receiver = interactor
                ?: error("SystemUI custom tile removal interactor is unavailable")
            val method = onCustomTileRemovedMethod
                ?: error("SystemUI custom tile removal method is unavailable")
            method.invokeUnwrapped(receiver, userId, component)
        }
    }

    private class CustomTileSession(
        val target: CustomTileTarget,
        val tile: Any,
        val ownsTile: Boolean,
        val tileUserId: Int?,
        val wasMarkedAdded: Boolean,
        val onClickDispatched: () -> Unit,
        val onFailure: (Throwable) -> Unit,
        val onFinished: () -> Unit,
        val listeningOwner: Any = Any(),
        var readinessPoll: Runnable? = null,
        var observationTimeout: Runnable? = null,
        var clickDispatched: Boolean = false,
        var cleanupScheduled: Boolean = false,
        var cleanupStarted: Boolean = false,
        var failureReported: Boolean = false,
    )

    companion object {
        private const val CUSTOM_TILE_CLASS = "com.android.systemui.qs.external.CustomTile"
        private const val GET_TILES_METHOD = "getTiles"
        private const val CREATE_TILE_METHOD = "createTile"
        private const val GET_TILE_SPEC_METHOD = "getTileSpec"
        private const val GET_STATE_METHOD = "getState"
        private const val SET_LISTENING_METHOD = "setListening"
        private const val REFRESH_STATE_METHOD = "refreshState"
        private const val CLICK_METHOD = "click"
        private const val DESTROY_METHOD = "destroy"
        private const val GET_USER_METHOD = "getUser"
        private const val STATE_FIELD = "state"
        private const val INTERACTOR_FIELD = "interactor"
        private const val ON_CUSTOM_TILE_REMOVED_METHOD = "onCustomTileRemoved"
        private const val TILE_PREFERENCES_NAME = "tiles_prefs"
        private const val TILE_STATE_UNAVAILABLE = 0
        private const val READINESS_TIMEOUT_MS = 5_000L
        private const val READINESS_POLL_INTERVAL_MS = 100L
        private const val OBSERVATION_TIMEOUT_MS = 5_000L
        private const val COMPLETION_GRACE_PERIOD_MS = 1_000L
        private const val TEMPORARY_TILE_REMOVAL_DELAY_MS = 500L

        private fun Class<*>.findField(name: String): Field {
            return generateSequence(this) { type -> type.superclass }
                .firstNotNullOfOrNull { type ->
                    runCatching { type.getDeclaredField(name) }.getOrNull()
                } ?: error("SystemUI field is unavailable: ${this.name}#$name")
        }
    }
}
