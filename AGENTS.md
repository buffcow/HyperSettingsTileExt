# Repository Guidelines

## Project Structure & Module Organization
HyperSettingsTileExt is a single-module Android application and Xposed module.

- `app/src/main/java/cn/buffcow/hyperste/` contains Kotlin sources; `MainModule.kt` is the Xposed entry point.
- `app/src/main/res/` contains Android resources, including default and Chinese strings.
- `app/src/main/resources/META-INF/xposed/` defines module metadata, entry classes, and package scope.
- `app/src/main/keepRules/` contains release optimization rules.
- Root and module `build.gradle.kts` files configure Gradle; dependency versions belong in `gradle/libs.versions.toml`.

Add local unit tests under `app/src/test/` and device tests under `app/src/androidTest/`, mirroring the production package hierarchy.

## Build, Test, and Development Commands
Use the checked-in wrapper from the repository root:

- `./gradlew assembleDebug` builds a debug APK.
- `./gradlew assembleRelease` builds the optimized release variant; signing may require local Gradle properties.
- `./gradlew lint` runs Android lint and fails on errors.
- `./gradlew testDebugUnitTest` runs JVM unit tests.
- `./gradlew connectedDebugAndroidTest` runs instrumentation tests on a connected device or emulator.
- `./gradlew clean` removes generated build output.

The project targets Java 11 bytecode and uses the Gradle-managed JVM toolchain configured in `gradle/gradle-daemon-jvm.properties`.

## Coding Style & Naming Conventions
- Follow Android Studio defaults: 4-space indentation, clear imports, and small focused classes.
- Keep package names lowercase, classes in `UpperCamelCase`, methods and fields in `lowerCamelCase`, and constants in `UPPER_SNAKE_CASE`.
- Android resource names should stay `lowercase_snake_case`, for example `ic_launcher_round` or `backup_rules.xml`.
- Prefer updating dependency aliases in `gradle/libs.versions.toml` instead of hardcoding versions in module build files.
- In Kotlin strings containing a literal `$`, prefer multi-dollar string interpolation over escaping
  each dollar sign when the configured Kotlin version supports it. For example, write
  `$$"com.A$a"` instead of `"com.A\$a"`, using the smallest interpolation prefix that preserves the
  intended literal text.
- For Kotlin 1.9 and newer, use `Enum.entries` instead of `Enum.values()` when iterating, searching,
  or otherwise enumerating enum constants. Use `Enum.values()` only when an actual array is required
  for Java or API interoperability.
- Call the top-level `logDebug()` and `logError()` functions from `Logger.kt` directly. Do not pass
  logging functions through constructors unless a class genuinely requires a replaceable logger,
  such as an explicit test seam or a different logging backend.

## Commit & Pull Request Guidelines
- Follow observed Conventional Commit style: `feat:`, `fix(scope):`, `perf:`, `refactor:`, `build:`.
- Keep commits focused by module/concern; avoid mixing refactor and behavior changes.
- PRs should include: summary, affected modules, risk/rollback notes, and test evidence (commands/logs; screenshots for UI changes).

## Security & Configuration
- Do not commit `local.properties`, keystores, credentials, or signing values such as `androidStorePassword`.
- Review changes to `META-INF/xposed/scope.list` carefully because they alter which applications the module can affect.

## Modern libxposed Hook API
- This project uses the modern `io.github.libxposed:api` (API 102), not the legacy rovo89 hook API.
- Treat `minApiVersion` in `META-INF/xposed/module.prop` as the compatibility baseline, not the
  compile-time dependency or `targetApiVersion`. Never call a libxposed API introduced above that
  baseline without guarding it with `getApiVersion() >= requiredApi`; otherwise use a compatible
  lower-API implementation. In particular, `HookBuilder.setId()` requires API 102 and must not be
  called unconditionally while `minApiVersion` is 101.
- Do not use `XposedHelpers`, `XposedBridge`, `XC_MethodHook`, `XC_MethodReplacement`, or `MethodHookParam`.
- Resolve the target `Method` or `Constructor` with reflection, then install hooks with
  `hook(executable).intercept { chain -> ... }`.
- For ordinary application hooks, prefer `onPackageReady()` and load target classes with
  `param.classLoader`. Use `onPackageLoaded()` and `param.defaultClassLoader` only when the hook must
  be installed before `AppComponentFactory` creates the final application class loader.
- Use code before `chain.proceed()` for before-call behavior and code after it for after-call behavior.
  The interceptor lambda's final value is the hooked call's result.
- To replace a method completely, return the replacement value without calling `chain.proceed()`.
- `chain.args` is immutable. To change arguments, create a new argument array and pass it to
  `chain.proceed(newArgs)`. Use `chain.thisObject` for the receiver of an instance method.
- To bypass every hook and invoke the original implementation, use
  `getInvoker(method).setType(Invoker.Type.ORIGIN)`. The default invoker uses the full hook chain and
  must not be treated as an original-method call.
- Keep the returned `HookHandle` when later unhooking or atomic replacement is required, and call
  `unhook()` to remove it.
- Use `ExceptionMode.PASSTHROUGH` when hook exceptions must reach the caller, especially while
  debugging; otherwise retain the configured/default protective behavior.

## Agent-Specific Instructions
- Do not delete the comments of my original code
- Do not run any Gradle task in this repository (for example `./gradlew ...`).
- Do not run automated tests (unit, integration, or instrumentation).
- If validation is needed, provide the exact commands for a human maintainer to run instead of executing them.
- All changes must be based on the latest current file state; do not rely on previous patches or assumptions.
- User’s current code is the source of truth and takes precedence by default.
- If a new instruction conflicts with existing code, modify only the necessary parts to satisfy the instruction while preserving all non-conflicting user changes.
- Do not overwrite or revert user modifications unintentionally; avoid full-file rewrites unless explicitly requested.
- Must provide detailed Chinese KDoc/Javadoc documentation for all public APIs in interface class.
- When touching the same Kotlin/Java object or View **multiple** times in one local block, use Kotlin scope functions such as
  `apply`, `run`, or `with` by default when setting or reading multiple properties.
