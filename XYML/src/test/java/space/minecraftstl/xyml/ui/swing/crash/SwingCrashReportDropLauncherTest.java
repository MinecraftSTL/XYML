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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleReader;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Verifies standard crash-export routing, deferred I/O, lifecycle suppression, and stable failures.
@NotNullByDefault
final class SwingCrashReportDropLauncherTest {
    /// Temporary directory containing canonical and malformed archive fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// A canonical export is accepted, while a renamed ZIP is rejected before background work.
    @Test
    void acceptsCanonicalNameAndDefersReadingToExecutor() throws IOException {
        Path archive = writeArchive("minecraft-exported-crash-info-2026-09-15_12-30-00.zip", "failure");
        List<Runnable> background = new ArrayList<>();
        List<ExportedCrashBundle> opened = new ArrayList<>();
        List<SwingCrashReportDropLauncher.ImportFailure> failures = new ArrayList<>();
        JPanel owner = new JPanel();
        JPanel target = new JPanel();
        AtomicReference<Component> observedOwner = new AtomicReference<>();

        SwingCrashReportDropLauncher launcher = install(
                owner,
                target,
                background,
                opened,
                failures,
                observedOwner);
        try {
            EdtDispatcher.executeAndWait(() -> {
                TransferHandler handler = Objects.requireNonNull(target.getTransferHandler());
                assertTrue(handler.canImport(fileTransfer(target, List.of(archive))));
                assertFalse(handler.canImport(fileTransfer(
                        target,
                        List.of(temporaryDirectory.resolve("renamed.zip")))));
                assertTrue(handler.importData(fileTransfer(target, List.of(archive))));
                assertEquals(1, background.size());
                assertTrue(opened.isEmpty());
            });

            background.get(0).run();
            EdtDispatcher.executeAndWait(() -> { });
            assertEquals(1, opened.size());
            assertEquals(owner, observedOwner.get());
            assertTrue(failures.isEmpty());
        } finally {
            launcher.close();
        }
    }

    /// Closing after scheduling suppresses both successful and failed late callbacks.
    @Test
    void closeSuppressesLateBackgroundCallbacks() throws IOException {
        Path archive = writeArchive("minecraft-exported-crash-info-late.zip", "failure");
        List<Runnable> background = new ArrayList<>();
        List<ExportedCrashBundle> opened = new ArrayList<>();
        List<SwingCrashReportDropLauncher.ImportFailure> failures = new ArrayList<>();
        JPanel owner = new JPanel();
        JPanel target = new JPanel();
        AtomicReference<Component> observedOwner = new AtomicReference<>();
        SwingCrashReportDropLauncher launcher = install(
                owner,
                target,
                background,
                opened,
                failures,
                observedOwner);
        try {
            EdtDispatcher.executeAndWait(() -> assertTrue(
                    Objects.requireNonNull(target.getTransferHandler())
                            .importData(fileTransfer(target, List.of(archive)))));
            launcher.close();
            background.get(0).run();
            EdtDispatcher.executeAndWait(() -> { });
            assertTrue(opened.isEmpty());
            assertTrue(failures.isEmpty());
            assertNull(target.getTransferHandler());
        } finally {
            launcher.close();
        }
    }

    /// Core exception wording maps to stable localized categories without exposing raw text.
    @Test
    void classifiesImportFailuresStably() {
        assertEquals(
                SwingCrashReportDropLauncher.ImportFailure.INVALID_ZIP,
                SwingCrashReportDropLauncher.ImportFailure.from(new ZipException("encrypted")));
        assertEquals(
                SwingCrashReportDropLauncher.ImportFailure.TOO_LARGE,
                SwingCrashReportDropLauncher.ImportFailure.from(
                        new IOException("Crash export exceeds its expanded diagnostic-text limit")));
        assertEquals(
                SwingCrashReportDropLauncher.ImportFailure.UNSAFE,
                SwingCrashReportDropLauncher.ImportFailure.from(
                        new IOException("Crash export contains duplicate diagnostic entry")));
        assertEquals(
                SwingCrashReportDropLauncher.ImportFailure.NO_LOGS,
                SwingCrashReportDropLauncher.ImportFailure.from(
                        new IOException("Crash export contains no supported non-empty diagnostic text")));
        assertEquals(
                SwingCrashReportDropLauncher.ImportFailure.UNREADABLE,
                SwingCrashReportDropLauncher.ImportFailure.from(new IOException()));
    }

    /// Installs a test launcher with an executor queue and callback recorders.
    private SwingCrashReportDropLauncher install(
            JPanel owner,
            JPanel target,
            List<Runnable> background,
            List<ExportedCrashBundle> opened,
            List<SwingCrashReportDropLauncher.ImportFailure> failures,
            AtomicReference<Component> observedOwner) {
        AtomicReference<SwingCrashReportDropLauncher> launcher = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> launcher.set(SwingCrashReportDropLauncher.install(
                owner,
                target,
                background::add,
                new ExportedCrashBundleReader(),
                (callbackOwner, bundle) -> {
                    observedOwner.set(callbackOwner);
                    opened.add(bundle);
                },
                (callbackOwner, ignoredArchive, failure) -> {
                    observedOwner.set(callbackOwner);
                    failures.add(failure);
                })));
        return Objects.requireNonNull(launcher.get());
    }

    /// Writes one valid canonical crash-export fixture.
    private Path writeArchive(String fileName, String log) throws IOException {
        Path archive = temporaryDirectory.resolve(fileName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("minecraft.log"));
            output.write(log.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive;
    }
}
