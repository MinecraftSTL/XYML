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
package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.countly.CrashReport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.crash.SwingCrashReportWindow;
import space.minecraftstl.xyml.ui.swing.startup.SwingStartupSafetyDialogs;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.ui.swing.startup.SwingStartupSafetyDialogs.Severity.INFO;
import static space.minecraftstl.xyml.util.Pair.pair;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Handles uncaught launcher failures and presents reportable errors through Swing.
@NotNullByDefault
public final class CrashReporter implements Thread.UncaughtExceptionHandler {
    /// Holds failure signatures lazily so localization is initialized only when crash classification is needed.
    @NotNullByDefault
    private static final class KnownFailures {
        /// Failure signatures and their localized user-facing explanations.
        @SuppressWarnings("unchecked")
        private static final Pair<String, String> @Unmodifiable [] SOURCE =
                (Pair<String, String>[]) new Pair<?, ?>[]{
                        pair("Location is not set", i18n("crash.NoClassDefFound")),
                        pair("UnsatisfiedLinkError", i18n("crash.user_fault")),
                        pair("java.time.zone.ZoneRulesException: Unable to load TZDB time-zone rules", i18n("crash.user_fault")),
                        pair("java.lang.NoClassDefFoundError", i18n("crash.NoClassDefFound")),
                        pair("space.minecraftstl.xyml.util.ResourceNotFoundError", i18n("crash.NoClassDefFound")),
                        pair("java.lang.VerifyError", i18n("crash.NoClassDefFound")),
                        pair("java.lang.NoSuchMethodError", i18n("crash.NoClassDefFound")),
                        pair("java.lang.NoSuchFieldError", i18n("crash.NoClassDefFound")),
                        pair("javax.imageio.IIOException", i18n("crash.NoClassDefFound")),
                        pair("netscape.javascript.JSException", i18n("crash.NoClassDefFound")),
                        pair("java.lang.IncompatibleClassChangeError", i18n("crash.NoClassDefFound")),
                        pair("java.lang.ClassFormatError", i18n("crash.NoClassDefFound")),
                        pair("NoSuchAlgorithmException", "Has your operating system been installed completely or is a ghost system?")
                };

        /// Prevents construction of this lazy holder.
        private KnownFailures() {
        }
    }

    /// Whether failures eligible for reporting should open the native Swing report window.
    private final boolean showCrashWindow;

    /// Supplies the latest successful toolkit-neutral launcher update result at presentation time.
    private final BooleanSupplier updateAvailableSupplier;

    /// Creates an uncaught-failure handler.
    ///
    /// @param showCrashWindow whether reportable failures should open a report window
    public CrashReporter(boolean showCrashWindow) {
        this(showCrashWindow, () -> false);
    }

    /// Creates an uncaught-failure handler with an injected launcher update-status source.
    ///
    /// The source is evaluated only when a complete crash window is about to be shown, so callers may update
    /// their snapshot after this reporter has been installed as the process-wide handler.
    ///
    /// @param showCrashWindow whether reportable failures should open a report window
    /// @param updateAvailableSupplier latest successful update-availability snapshot
    public CrashReporter(boolean showCrashWindow, BooleanSupplier updateAvailableSupplier) {
        this.showCrashWindow = showCrashWindow;
        this.updateAvailableSupplier = Objects.requireNonNull(
                updateAvailableSupplier,
                "updateAvailableSupplier");
    }

    /// Logs, classifies, and presents one uncaught launcher failure before flushing persistent state.
    ///
    /// @param thread thread on which the failure escaped
    /// @param failure uncaught failure
    @Override
    public void uncaughtException(Thread thread, Throwable failure) {
        LOG.error("Uncaught exception in thread " + thread.getName(), failure);

        try {
            CrashReport report = new CrashReport(thread, failure);
            if (!report.shouldBeReport()) {
                return;
            }

            LOG.error(report.getDisplayText());
            EdtDispatcher.execute(() -> presentFailure(report, failure));
        } catch (Throwable handlingFailure) {
            LOG.error("Unable to handle uncaught exception", handlingFailure);
        }

        FileSaver.shutdown();
        LOG.shutdown();
    }

    /// Presents either a concise known-environment explanation or the complete native crash report.
    ///
    /// Failures raised by the presentation itself are contained so the Swing event thread cannot recursively
    /// enter the process-wide uncaught exception handler.
    ///
    /// @param report complete crash report
    /// @param failure original uncaught failure
    private void presentFailure(CrashReport report, Throwable failure) {
        try {
            @Nullable String knownMessage = findKnownFailureMessage(failure);
            if (knownMessage != null) {
                if (StringUtils.isNotBlank(knownMessage)) {
                    SwingStartupSafetyDialogs.showMessage(INFO, knownMessage);
                }
                return;
            }
            if (showCrashWindow) {
                SwingCrashReportWindow.show(report, isUpdateAvailable());
            }
        } catch (Throwable presentationFailure) {
            LOG.error("Unable to present uncaught exception", presentationFailure);
        }
    }

    /// Reads the current injected launcher update result without retaining update-service state.
    ///
    /// @return whether the latest successful native check reported an available update
    boolean isUpdateAvailable() {
        return updateAvailableSupplier.getAsBoolean();
    }

    /// Finds the explanation for a recognized environment failure.
    ///
    /// @param failure failure to classify
    /// @return localized explanation, or null when the full report should be shown
    static @Nullable String findKnownFailureMessage(Throwable failure) {
        String stackTrace = StringUtils.getStackTrace(failure);
        for (Pair<String, String> entry : KnownFailures.SOURCE) {
            if (stackTrace.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
