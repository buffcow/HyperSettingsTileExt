package cn.buffcow.hyperste.toggle

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError

/**
 * 提供 HyperOS Google 服务状态读取、跨用户切换及原生设置页跳转
 *
 * @author qingyu
 * <p>Create on 2026/08/13 14:43</p>
 */
internal class GoogleServicesQuickToggle(
    private val classLoader: ClassLoader,
) : QuickToggle {

    override val id: String = ID
    override val category: QuickToggleCategory = QuickToggleCategory.SERVICES
    override val titleRes: Int = R.string.quick_toggle_google_services_title
    override val fallbackTitle: String = FALLBACK_TITLE
    override val descriptionRes: Int? = null
    override val fallbackDescription: String? = null
    override val longClickAction = QuickToggleAction(::openGoogleServicesSettings)

    private var controllerFailureLogged = false
    private var stateFailureLogged = false
    private val controllerResult by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            CrossUserPackageController(classLoader)
        }
    }

    override fun readState(host: QuickToggleHost): QuickToggleState {
        val controller = getController() ?: return QuickToggleState.UNAVAILABLE
        return runCatching {
            val currentUserId = controller.getCurrentUserId()
            if (!isSupported(host.context, controller, currentUserId)) {
                return@runCatching QuickToggleState.UNAVAILABLE
            }
            QuickToggleState(
                isAvailable = true,
                isEnabled = true,
                isChecked = CORE_PACKAGES.none { packageName ->
                    controller.getApplicationEnabledSetting(packageName, currentUserId) ==
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
            )
        }.onSuccess {
            stateFailureLogged = false
        }.onFailure {
            logStateFailureOnce("Failed to read the Google services state", it)
        }.getOrDefault(QuickToggleState.UNAVAILABLE)
    }

    override fun setChecked(host: QuickToggleHost, checked: Boolean) {
        val controller = getController()
            ?: error("Google services package control is unavailable")
        val currentUserId = controller.getCurrentUserId()
        check(isSupported(host.context, controller, currentUserId)) {
            "Google services are unavailable for the current user"
        }

        val targetState = if (checked) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val targetPackages = buildList {
            addAll(CORE_PACKAGES)
            addAll(AUXILIARY_PACKAGES)
            if (checked) {
                add(EXT_SERVICES_PACKAGE)
            }
        }
        val targets = collectTargets(controller, currentUserId, targetPackages)
        applyStateTransaction(host.context, controller, targets, targetState)
        if (!checked) {
            forceStopDisabledPackages(host.context, controller, targets)
        }
        logDebug(
            "Google services state requested: checked=$checked, " +
                    "packageUserCount=${targets.size}",
        )
    }

    private fun getController(): CrossUserPackageController? {
        return controllerResult.onFailure {
            if (!controllerFailureLogged) {
                controllerFailureLogged = true
                logError("Failed to initialize cross-user package control", it)
            }
        }.getOrNull()
    }

    private fun isSupported(
        context: Context,
        controller: CrossUserPackageController,
        currentUserId: Int,
    ): Boolean {
        return controller.hasRequiredMutationPermissions(context) &&
                !controller.isInternationalBuild() &&
                controller.getSystemProperty(GMS_CORE_PROPERTY) == PROPERTY_ENABLED &&
                CORE_PACKAGES.all { packageName ->
                    controller.isPackageAvailable(packageName, currentUserId)
                }
    }

    private fun collectTargets(
        controller: CrossUserPackageController,
        currentUserId: Int,
        packageNames: List<String>,
    ): List<PackageTarget> {
        return buildList {
            linkedSetOf(currentUserId, SECOND_SPACE_USER_ID).forEach { userId ->
                packageNames.forEach { packageName ->
                    if (controller.isPackageAvailable(packageName, userId)) {
                        add(
                            PackageTarget(
                                packageName = packageName,
                                userId = userId,
                                originalState = controller.getApplicationEnabledSetting(
                                    packageName,
                                    userId,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun applyStateTransaction(
        context: Context,
        controller: CrossUserPackageController,
        targets: List<PackageTarget>,
        targetState: Int,
    ) {
        val changedTargets = mutableListOf<PackageTarget>()
        try {
            targets.forEach { target ->
                if (target.originalState == targetState) {
                    return@forEach
                }
                changedTargets += target
                controller.setApplicationEnabledSetting(
                    context = context,
                    packageName = target.packageName,
                    state = targetState,
                    userId = target.userId,
                )
                check(
                    controller.getApplicationEnabledSetting(
                        target.packageName,
                        target.userId,
                    ) == targetState,
                ) {
                    "Package manager did not apply the requested state: " +
                            "package=${target.packageName}, userId=${target.userId}"
                }
            }
        } catch (failure: Throwable) {
            rollbackChangedTargets(context, controller, changedTargets, failure)
            throw failure
        }
    }

    private fun rollbackChangedTargets(
        context: Context,
        controller: CrossUserPackageController,
        changedTargets: List<PackageTarget>,
        originalFailure: Throwable,
    ) {
        changedTargets.asReversed().forEach { target ->
            runCatching {
                controller.setApplicationEnabledSetting(
                    context = context,
                    packageName = target.packageName,
                    state = target.originalState,
                    userId = target.userId,
                )
                check(
                    controller.getApplicationEnabledSetting(
                        target.packageName,
                        target.userId,
                    ) == target.originalState,
                ) {
                    "Package manager did not restore the original state"
                }
            }.onFailure { rollbackFailure ->
                originalFailure.addSuppressed(rollbackFailure)
                logError(
                    "Failed to roll back a Google services package state: " +
                            "package=${target.packageName}, userId=${target.userId}",
                    rollbackFailure,
                )
            }
        }
    }

    private fun forceStopDisabledPackages(
        context: Context,
        controller: CrossUserPackageController,
        targets: List<PackageTarget>,
    ) {
        targets.forEach { target ->
            runCatching {
                controller.forceStopPackage(context, target.packageName, target.userId)
            }.onFailure {
                logError(
                    "Failed to force-stop a disabled Google services package: " +
                            "package=${target.packageName}, userId=${target.userId}",
                    it,
                )
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openGoogleServicesSettings(host: QuickToggleHost) {
        val intent = Intent(GOOGLE_SERVICES_SETTINGS_ACTION).apply {
            setClassName(SECURITY_CENTER_PACKAGE, GOOGLE_SERVICES_SETTINGS_ACTIVITY)
        }
        if (
            intent.resolveActivityInfo(
                host.context.packageManager,
                PackageManager.MATCH_DEFAULT_ONLY,
            ) == null
        ) {
            logDebug("Google services settings activity is unavailable")
            error("Google services settings activity is unavailable")
        }
        host.startActivity(intent)
    }

    private fun logStateFailureOnce(message: String, throwable: Throwable) {
        if (stateFailureLogged) {
            return
        }
        stateFailureLogged = true
        logError(message, throwable)
    }

    private data class PackageTarget(
        val packageName: String,
        val userId: Int,
        val originalState: Int,
    )

    companion object {
        private const val ID = "google_services"
        private const val FALLBACK_TITLE = "Google services"

        private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
        private const val GOOGLE_SERVICES_SETTINGS_ACTIVITY =
            "com.miui.googlebase.ui.GmsCoreSettings"
        private const val GOOGLE_SERVICES_SETTINGS_ACTION = "miui.intent.action.APP_SETTINGS"

        private const val GMS_CORE_PROPERTY = "ro.miui.has_gmscore"
        private const val PROPERTY_ENABLED = "1"
        private const val SECOND_SPACE_USER_ID = 999
        private const val EXT_SERVICES_PACKAGE = "com.google.android.ext.services"

        private val CORE_PACKAGES = listOf(
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
        )
        private val AUXILIARY_PACKAGES = listOf(
            "com.google.android.syncadapters.contacts",
            "com.google.android.backuptransport",
            "com.google.android.onetimeinitializer",
            "com.google.android.partnersetup",
            "com.google.android.configupdater",
            "com.google.android.ext.shared",
            "com.google.android.printservice.recommendation",
        )
    }
}
