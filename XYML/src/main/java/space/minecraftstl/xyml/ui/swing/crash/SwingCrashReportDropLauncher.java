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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleReader;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.shell.AppShellFrame;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipException;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Installs the application-wide route for launcher-exported crash report bundles.
///
/// The native drop callback performs only filename and regular-file checks. Complete ZIP validation
/// and reading run on a caller-owned I/O executor before immutable contents return to the EDT.
@NotNullByDefault
public final class SwingCrashReportDropLauncher implements AutoCloseable {
    /// Owning application component used for every native error or result window.
    private final Component owner;

    /// Background ZIP reader with the fixed Core safety policy.
    private final ExportedCrashBundleReader reader;

    /// Caller-owned executor used for every blocking archive operation.
    private final Executor ioExecutor;

    /// EDT-only boundary opening the imported read-only crash window.
    private final CrashWindowFactory windowFactory;

    /// EDT-only boundary reporting a stable localized failure category.
    private final ImportFailureReporter failureReporter;

    /// Independently removable route installed on the application shell.
    private final ShellFileDropHandler.RouteRegistration dropRegistration;

    /// Suppresses repeated closure and late background completion.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Installs the production route and ties its lifetime to the owning application frame.
    ///
    /// @param frame non-null production application frame
    /// @param ioExecutor caller-owned I/O executor
    /// @return installed drop-route lifecycle
    public static SwingCrashReportDropLauncher install(
            AppShellFrame frame,
            Executor ioExecutor) {
        AppShellFrame owner = Objects.requireNonNull(frame, "frame");
        Executor executor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        AtomicReference<@Nullable SwingCrashReportDropLauncher> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            SwingCrashReportDropLauncher launcher = install(
                    owner,
                    owner.shellPanel(),
                    executor,
                    new ExportedCrashBundleReader(),
                    (ignoredOwner, bundle) -> SwingGameCrashWindow.openImported(owner, bundle),
                    SwingCrashReportDropLauncher::showImportFailure);
            owner.addWindowListener(new WindowAdapter() {
                /// Removes the route after native owner disposal.
                ///
                /// @param event owner-window close event
                @Override
                public void windowClosed(WindowEvent event) {
                    Objects.requireNonNull(event, "event");
                    launcher.close();
                }
            });
            result.set(launcher);
        });
        return Objects.requireNonNull(result.get(), "crash-report drop launcher was not installed");
    }

    /// Installs an injected route for deterministic headless tests.
    ///
    /// @param owner non-null owner passed to every result and failure boundary
    /// @param target shell drop target
    /// @param ioExecutor background executor
    /// @param reader bounded crash-export reader
    /// @param windowFactory successful presentation boundary
    /// @param failureReporter rejected-import presentation boundary
    /// @return installed route lifecycle
    static SwingCrashReportDropLauncher install(
            Component owner,
            JComponent target,
            Executor ioExecutor,
            ExportedCrashBundleReader reader,
            CrashWindowFactory windowFactory,
            ImportFailureReporter failureReporter) {
        EdtDispatcher.requireEventDispatchThread();
        return new SwingCrashReportDropLauncher(
                owner,
                target,
                ioExecutor,
                reader,
                windowFactory,
                failureReporter);
    }

    /// Creates and registers one exported-crash route on the EDT.
    private SwingCrashReportDropLauncher(
            Component owner,
            JComponent target,
            Executor ioExecutor,
            ExportedCrashBundleReader reader,
            CrashWindowFactory windowFactory,
            ImportFailureReporter failureReporter) {
        EdtDispatcher.requireEventDispatchThread();
        this.owner = Objects.requireNonNull(owner, "owner");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.windowFactory = Objects.requireNonNull(windowFactory, "windowFactory");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        dropRegistration = ShellFileDropHandler.register(
                Objects.requireNonNull(target, "target"),
                this::supports,
                this::open);
    }

    /// Removes the shell route and suppresses any later background result.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            dropRegistration.close();
        }
    }

    /// Applies cheap filename and regular-file checks from the native drop callback.
    private boolean supports(Path source) {
        Path candidate = Objects.requireNonNull(source, "source");
        return !closed.get()
                && ExportedCrashBundleReader.hasSupportedFileName(candidate)
                && Files.isRegularFile(candidate);
    }

    /// Schedules complete ZIP validation and reading away from the EDT.
    private void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        Path archive = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!supports(archive)) {
            return;
        }
        try {
            ioExecutor.execute(() -> readAndPresent(archive));
        } catch (RuntimeException failure) {
            reportFailure(archive, failure);
        }
    }

    /// Reads one immutable bundle and returns either its result or stable failure category to the EDT.
    private void readAndPresent(Path archive) {
        try {
            ExportedCrashBundle bundle = reader.read(archive);
            EdtDispatcher.execute(() -> {
                if (!closed.get()) {
                    windowFactory.open(owner, bundle);
                }
            });
        } catch (IOException | RuntimeException failure) {
            reportFailure(archive, failure);
        }
    }

    /// Logs the internal failure and schedules a safe localized category unless the owner has closed.
    private void reportFailure(Path archive, Throwable failure) {
        LOG.warning("Failed to import exported crash report " + archive, failure);
        ImportFailure category = ImportFailure.from(failure);
        EdtDispatcher.execute(() -> {
            if (!closed.get()) {
                failureReporter.show(owner, archive, category);
            }
        });
    }

    /// Shows the production localized import failure dialog using the real application owner.
    private static void showImportFailure(Component owner, Path archive, ImportFailure failure) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                Objects.requireNonNull(owner, "owner"),
                i18n("game.crash.import.failed", archive.getFileName(), i18n(failure.messageKey())),
                i18n("game.crash.import.title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Stable user-facing failure categories independent of platform exception wording.
    @NotNullByDefault
    enum ImportFailure {
        /// The file no longer exists or is not an ordinary readable file.
        UNREADABLE("game.crash.import.failure.unreadable"),

        /// The archive contains an unsafe path or duplicate supported path.
        UNSAFE("game.crash.import.failure.unsafe"),

        /// The archive exceeds an entry, byte, or compression-ratio ceiling.
        TOO_LARGE("game.crash.import.failure.too_large"),

        /// The archive contains no supported non-empty diagnostic text.
        NO_LOGS("game.crash.import.failure.no_logs"),

        /// The ZIP is malformed, encrypted, or otherwise unsupported.
        INVALID_ZIP("game.crash.import.failure.invalid_zip");

        /// Localized message key for this stable category.
        private final String messageKey;

        /// Creates one stable failure category.
        ImportFailure(String messageKey) {
            this.messageKey = messageKey;
        }

        /// Returns the localized explanation key.
        private String messageKey() {
            return messageKey;
        }

        /// Safely maps current Core exceptions without exposing their optional English message.
        ///
        /// @param failure internal read failure
        /// @return stable localized failure category
        static ImportFailure from(Throwable failure) {
            Throwable cause = Objects.requireNonNull(failure, "failure");
            if (cause instanceof ZipException) {
                return INVALID_ZIP;
            }
            String message = cause.getMessage();
            if (message == null) {
                return cause instanceof IOException ? UNREADABLE : INVALID_ZIP;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("no supported non-empty diagnostic text")) {
                return NO_LOGS;
            }
            if (normalized.contains("limit") || normalized.contains("exceeds")) {
                return TOO_LARGE;
            }
            if (normalized.contains("nul")
                    || normalized.contains("absolute or empty")
                    || normalized.contains("ambiguous or traversal")
                    || normalized.contains("duplicate diagnostic entry")
                    || normalized.contains("non-symbolic regular file")) {
                return UNSAFE;
            }
            return cause instanceof IOException ? UNREADABLE : INVALID_ZIP;
        }
    }

    /// Opens one immutable imported bundle on the EDT.
    @NotNullByDefault
    @FunctionalInterface
    interface CrashWindowFactory {
        /// Opens the successfully parsed bundle with its non-null application owner.
        ///
        /// @param owner stable application owner
        /// @param bundle immutable parsed bundle
        void open(Component owner, ExportedCrashBundle bundle);
    }

    /// Reports one rejected imported bundle on the EDT.
    @NotNullByDefault
    @FunctionalInterface
    interface ImportFailureReporter {
        /// Shows one stable import failure category.
        ///
        /// @param owner stable application owner
        /// @param archive rejected archive
        /// @param failure stable localized category
        void show(Component owner, Path archive, ImportFailure failure);
    }
}
