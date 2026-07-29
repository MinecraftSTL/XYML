/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.EnumCommonDirectory;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.LauncherState;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.UserState;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.startup.StartupPlatformPrompt;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptCoordinator;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptCopy;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptEffects;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptEnvironment;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptKind;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptPresenter;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptSnapshot;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptStateGateway;
import space.minecraftstl.xyml.ui.swing.startup.StartupPromptStrings;
import space.minecraftstl.xyml.util.AprilFools;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.Restarter;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Builds the Swing startup-prompt policy around the remaining launcher settings stores.
///
/// This adapter is deliberately isolated from the pure Swing prompt model while launcher settings still
/// expose their launcher observable-property representation.
@NotNullByDefault
public final class SwingStartupPrompts {
    /// Agreement policy version required by this launcher build.
    private static final int REQUIRED_AGREEMENT_VERSION = 1;

    /// Platform-notice policy version required by this launcher build.
    private static final int REQUIRED_PLATFORM_PROMPT_VERSION = 1;

    /// Stable language identifier used by the April Fools language invitation.
    private static final String APRIL_FOOLS_LANGUAGE_ID = "lzh";

    /// Stable persisted key for the deprecated-Java prompt marker.
    private static final String JAVA_VERSION_TIP = "javaVersion";

    /// Stable persisted key for the interpreted-mode prompt marker.
    private static final String JAVA_INTERPRETED_MODE_TIP = "javaInterpretedMode";

    /// Stable persisted key for the software-rendering prompt marker.
    private static final String SOFTWARE_RENDERING_TIP = "softwareRendering";

    /// Stable persisted key for the annual April Fools prompt marker.
    private static final String APRIL_FOOLS_TIP = "aprilFools";

    /// Prevents utility instantiation.
    private SwingStartupPrompts() {
    }

    /// Creates one idle prompt coordinator from the current initialized launcher state.
    ///
    /// This factory is invoked on the Swing event dispatch thread while launcher settings remain thread-bound.
    /// The returned coordinator performs later reads and writes through the serialized launcher-state bridge.
    ///
    /// @param presenter native Swing presentation boundary
    /// @param workerExecutor caller-owned non-EDT executor
    /// @param closeApplication unified application close command
    /// @return idle coordinator ready to start after the Swing window is visible
    public static StartupPromptCoordinator create(
            StartupPromptPresenter presenter,
            Executor workerExecutor,
            Runnable closeApplication) {
        LauncherStateDispatcher.requireEventThread();
        Objects.requireNonNull(presenter, "presenter");
        Objects.requireNonNull(workerExecutor, "workerExecutor");
        Objects.requireNonNull(closeApplication, "closeApplication");

        return new StartupPromptCoordinator(
                createEnvironment(),
                createStrings(),
                new LauncherStateGateway(),
                presenter,
                new LauncherEffects(closeApplication),
                SwingUiDispatcher.INSTANCE,
                workerExecutor);
    }

    /// Captures explicit policy inputs for this startup pass.
    ///
    /// Swing does not render through Prism, so the old Prism software-rendering signal is intentionally
    /// not propagated into the production Swing warning policy.
    ///
    /// @return immutable startup environment
    private static StartupPromptEnvironment createEnvironment() {
        Optional<String> aprilFoolsLanguage = SupportedLocale.getSupportedLocales().stream()
                .map(SupportedLocale::getName)
                .filter(APRIL_FOOLS_LANGUAGE_ID::equals)
                .findFirst();
        return new StartupPromptEnvironment(
                REQUIRED_AGREEMENT_VERSION,
                REQUIRED_PLATFORM_PROMPT_VERSION,
                classifyPlatform(OperatingSystem.CURRENT_OS, Architecture.SYSTEM_ARCH),
                Metadata.MINIMUM_SUPPORTED_JAVA_VERSION,
                JavaRuntime.CURRENT_VERSION,
                !JavaRuntime.CURRENT_JIT_ENABLED,
                false,
                AprilFools.isEnabled(),
                LocalDate.now().getYear(),
                "zh".equals(I18n.getLocale().getLocale().getLanguage()),
                aprilFoolsLanguage);
    }

    /// Classifies one operating-system and architecture pair using the established launcher policy.
    ///
    /// @param operatingSystem operating-system family
    /// @param architecture system architecture
    /// @return explicit prompt behavior
    static StartupPlatformPrompt classifyPlatform(
            OperatingSystem operatingSystem,
            Architecture architecture) {
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(architecture, "architecture");
        if (architecture.isX86()) {
            return StartupPlatformPrompt.NONE;
        }
        if (operatingSystem == OperatingSystem.MACOS && architecture == Architecture.ARM64) {
            return StartupPlatformPrompt.MARK_SUPPORTED;
        }
        if (operatingSystem == OperatingSystem.WINDOWS && architecture == Architecture.ARM64) {
            return StartupPlatformPrompt.WINDOWS_ARM64;
        }
        if (operatingSystem == OperatingSystem.LINUX
                && (architecture == Architecture.LOONGARCH64
                || architecture == Architecture.LOONGARCH64_OW
                || architecture == Architecture.MIPS64EL)) {
            return StartupPlatformPrompt.LOONGARCH;
        }
        return StartupPlatformPrompt.OTHER_UNSUPPORTED;
    }

    /// Captures localized content without embedding localization access in the pure prompt policy.
    ///
    /// @return immutable localized startup strings
    private static StartupPromptStrings createStrings() {
        @Nullable String javaDownload = Metadata.getSuggestedJavaDownloadLink();
        Optional<StartupPromptStrings.Link> javaDownloadLink = Optional.ofNullable(javaDownload)
                .map(link -> new StartupPromptStrings.Link(
                        i18n(
                                "fatal.deprecated_java_version.download_link",
                                Metadata.RECOMMENDED_JAVA_VERSION),
                        URI.create(link)));
        String informationTitle = i18n("message.info");
        String warningTitle = i18n("message.warning");
        return new StartupPromptStrings(
                new StartupPromptStrings.Agreement(
                        new StartupPromptCopy(
                                i18n("launcher.agreement"),
                                i18n("launcher.agreement.hint")),
                        new StartupPromptStrings.Link(
                                i18n("launcher.agreement"),
                                URI.create(Metadata.EULA_URL)),
                        i18n("launcher.agreement.accept"),
                        i18n("launcher.agreement.decline")),
                new StartupPromptCopy(
                        warningTitle,
                        i18n("launcher.cache_directory.invalid")),
                new StartupPromptStrings.Platform(
                        new StartupPromptCopy(
                                informationTitle,
                                i18n("fatal.unsupported_platform.windows_arm64")),
                        new StartupPromptCopy(
                                informationTitle,
                                i18n("fatal.unsupported_platform.loongarch")),
                        new StartupPromptCopy(
                                warningTitle,
                                i18n("fatal.unsupported_platform"))),
                new StartupPromptStrings.DeprecatedJava(
                        new StartupPromptCopy(
                                warningTitle,
                                i18n("fatal.deprecated_java_version")),
                        javaDownloadLink),
                new StartupPromptStrings.Suppression(
                        new StartupPromptCopy(
                                warningTitle,
                                i18n("warning.java_interpreted_mode")),
                        new StartupPromptCopy(
                                warningTitle,
                                i18n("warning.software_rendering")),
                        i18n("button.ok"),
                        i18n("button.do_not_show_again")),
                new StartupPromptStrings.AprilFools(
                        new StartupPromptCopy(
                                informationTitle,
                                i18n("launcher.april_fools.switch_lzh")),
                        new StartupPromptCopy(
                                informationTitle,
                                i18n("launcher.april_fools.switch_lzh.confirm")),
                        10,
                        i18n("button.ok.countdown"),
                        i18n("button.ok"),
                        i18n("button.cancel"),
                        i18n("button.yes"),
                        i18n("button.no")),
                i18n("button.ok"));
    }

    /// Converts one persisted numeric marker to a non-negative optional integer.
    ///
    /// @param value persisted marker of unknown type
    /// @return non-negative integer marker, or empty for missing and invalid values
    static OptionalInt nonNegativeTip(@Nullable Object value) {
        if (value instanceof Number number && number.intValue() >= 0) {
            return OptionalInt.of(number.intValue());
        }
        return OptionalInt.empty();
    }

    /// Converts one persisted annual marker to a positive optional integer.
    ///
    /// @param value persisted marker of unknown type
    /// @return positive year marker, or empty for missing and invalid values
    static OptionalInt positiveYearTip(@Nullable Object value) {
        if (value instanceof Number number && number.intValue() > 0) {
            return OptionalInt.of(number.intValue());
        }
        return OptionalInt.empty();
    }

    /// Bridges startup prompt state reads and writes to launcher observable properties.
    @NotNullByDefault
    private static final class LauncherStateGateway implements StartupPromptStateGateway {
        /// Captures the current prompt state on the Swing event dispatch thread.
        @Override
        public StartupPromptSnapshot readSnapshot() {
            return callOnLauncherStateThread(() -> {
                LauncherSettings settings = SettingsManager.settings();
                LauncherState state = SettingsManager.state();
                UserState userState = SettingsManager.userState();
                boolean invalidCacheDirectory = settings.commonDirectoryTypeProperty().get()
                        == EnumCommonDirectory.CUSTOM
                        && !FileUtils.canCreateDirectory(settings.getResolvedCommonDirectory());
                return new StartupPromptSnapshot(
                        Math.max(0, userState.agreementVersionProperty().get()),
                        invalidCacheDirectory,
                        Math.max(0, userState.platformPromptVersionProperty().get()),
                        nonNegativeTip(state.getShownTips().get(JAVA_VERSION_TIP)),
                        Boolean.TRUE.equals(
                                state.getShownTips().get(JAVA_INTERPRETED_MODE_TIP)),
                        Boolean.TRUE.equals(
                                state.getShownTips().get(SOFTWARE_RENDERING_TIP)),
                        positiveYearTip(state.getShownTips().get(APRIL_FOOLS_TIP)));
            });
        }

        /// Persists agreement acceptance on the Swing event dispatch thread.
        ///
        /// @param agreementVersion accepted agreement version
        @Override
        public void acceptAgreement(int agreementVersion) {
            runOnLauncherStateThread(() -> SettingsManager.userState()
                    .agreementVersionProperty()
                    .set(agreementVersion));
        }

        /// Restores default cache-directory selection on the Swing event dispatch thread.
        @Override
        public void restoreDefaultCacheDirectory() {
            runOnLauncherStateThread(() -> SettingsManager.settings()
                    .commonDirectoryTypeProperty()
                    .set(EnumCommonDirectory.DEFAULT));
        }

        /// Persists platform-prompt acknowledgement on the Swing event dispatch thread.
        ///
        /// @param promptVersion acknowledged platform prompt version
        @Override
        public void markPlatformPromptShown(int promptVersion) {
            runOnLauncherStateThread(() -> SettingsManager.userState()
                    .platformPromptVersionProperty()
                    .set(promptVersion));
        }

        /// Persists deprecated-Java acknowledgement on the Swing event dispatch thread.
        ///
        /// @param minimumJavaVersion acknowledged minimum Java feature version
        @Override
        public void markDeprecatedJavaPromptShown(int minimumJavaVersion) {
            putShownTip(JAVA_VERSION_TIP, minimumJavaVersion);
        }

        /// Persists interpreted-mode suppression on the Swing event dispatch thread.
        @Override
        public void suppressInterpretedJavaWarning() {
            putShownTip(JAVA_INTERPRETED_MODE_TIP, true);
        }

        /// Persists software-rendering suppression on the Swing event dispatch thread.
        @Override
        public void suppressSoftwareRenderingWarning() {
            putShownTip(SOFTWARE_RENDERING_TIP, true);
        }

        /// Persists the resolved April Fools year on the Swing event dispatch thread.
        ///
        /// @param year resolved local year
        @Override
        public void markAprilFoolsShown(int year) {
            putShownTip(APRIL_FOOLS_TIP, year);
        }

        /// Selects one installed launcher language on the Swing event dispatch thread.
        ///
        /// @param languageId stable target language identifier
        @Override
        public void selectLanguage(String languageId) {
            Objects.requireNonNull(languageId, "languageId");
            runOnLauncherStateThread(() -> {
                SupportedLocale language = SupportedLocale.getSupportedLocales().stream()
                        .filter(locale -> languageId.equals(locale.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unsupported launcher language: " + languageId));
                SettingsManager.settings().languageProperty().set(language);
            });
        }

        /// Writes one shown-tip marker on the Swing event dispatch thread.
        ///
        /// @param key stable tip key
        /// @param value persisted tip value
        private static void putShownTip(String key, Object value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            runOnLauncherStateThread(() -> SettingsManager.state().getShownTips().put(key, value));
        }
    }

    /// Bridges process effects while keeping the coordinator worker independent from both UI threads.
    @NotNullByDefault
    private static final class LauncherEffects implements StartupPromptEffects {
        /// Unified application close command scheduled onto the Swing event dispatch thread.
        private final Runnable closeApplication;

        /// Creates process effects for one application runtime.
        ///
        /// @param closeApplication unified application close command
        private LauncherEffects(Runnable closeApplication) {
            this.closeApplication = Objects.requireNonNull(closeApplication, "closeApplication");
        }

        /// Leaves settings persistence to its active property listeners before restart.
        @Override
        public void saveBeforeRestart() {
            // The Swing window does not reuse the removed stage-bound window state.
        }

        /// Waits for every already-issued settings save.
        ///
        /// @throws InterruptedException when the worker is interrupted
        @Override
        public void waitForPendingSaves() throws InterruptedException {
            FileSaver.waitForAllSaves();
        }

        /// Starts the replacement launcher process.
        ///
        /// @throws java.io.IOException when the replacement process cannot be started
        @Override
        public void restartApplication() throws java.io.IOException {
            Restarter.restartSelf();
        }

        /// Schedules unified application shutdown without making the worker wait for Swing.
        @Override
        public void closeApplication() {
            SwingUiDispatcher.INSTANCE.dispatch(closeApplication);
        }

        /// Records one prompt failure while allowing coordinator policy to decide continuation.
        ///
        /// @param promptKind prompt whose operation failed
        /// @param failure exact prompt failure
        @Override
        public void reportFailure(StartupPromptKind promptKind, Throwable failure) {
            LOG.warning("Swing startup prompt failed: " + promptKind, failure);
        }
    }

    /// Runs one state mutation on the Swing EDT.
    ///
    /// @param operation mutation requiring launcher-state confinement
    private static void runOnLauncherStateThread(Runnable operation) {
        LauncherStateDispatcher.executeAndWait(Objects.requireNonNull(operation, "operation"));
    }

    /// Returns one non-null value produced on the Swing EDT.
    ///
    /// @param supplier value supplier requiring launcher-state confinement
    /// @param <T> non-null result type
    /// @return supplied value
    private static <T> T callOnLauncherStateThread(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        runOnLauncherStateThread(() -> result.set(Objects.requireNonNull(
                supplier.get(),
                "launcher state supplier returned null")));
        return Objects.requireNonNull(result.get(), "launcher state supplier did not run");
    }
}
