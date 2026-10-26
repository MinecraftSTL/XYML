// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Verifies page-scoped Java path classification and route lifecycle.
@NotNullByDefault
public final class JavaRuntimeDropControllerTest {
    /// Temporary Java homes and executable fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Routes homes and executables separately from archives while input remains available.
    @Test
    public void routesSupportedJavaPathsAndDetachesOnClose() throws Exception {
        Path javaHome = Files.createDirectory(temporaryDirectory.resolve("jdk-21"));
        Path javaExecutable = Files.createFile(temporaryDirectory.resolve("JAVA.EXE"));
        Path archive = temporaryDirectory.resolve("runtime.TAR.GZ");
        Path unsupported = temporaryDirectory.resolve("readme.txt");
        AtomicBoolean available = new AtomicBoolean(true);
        List<Path> runtimes = new ArrayList<>();
        List<Path> archives = new ArrayList<>();

        EdtDispatcher.executeAndWait(() -> {
            JPanel panel = new JPanel();
            JavaRuntimeDropController controller = JavaRuntimeDropController.install(
                    panel,
                    available::get,
                    JavaRuntimeDropControllerTest::isArchive,
                    runtimes::add,
                    archives::add);
            TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());

            assertTrue(handler.importData(fileTransfer(panel, List.of(javaHome))));
            assertTrue(handler.importData(fileTransfer(panel, List.of(javaExecutable))));
            assertTrue(handler.importData(fileTransfer(panel, List.of(archive))));
            assertFalse(handler.canImport(fileTransfer(panel, List.of(unsupported))));
            assertEquals(List.of(javaHome, javaExecutable), runtimes);
            assertEquals(List.of(archive), archives);

            available.set(false);
            assertFalse(handler.canImport(fileTransfer(panel, List.of(javaHome))));
            controller.close();
            assertNull(panel.getTransferHandler());
        });
    }

    /// Returns whether one path has a supported runtime archive suffix.
    ///
    /// @param path candidate path
    /// @return whether the path ends in ZIP or TAR.GZ
    private static boolean isArchive(Path path) {
        String name = Objects.requireNonNull(path, "path").toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip") || name.endsWith(".tar.gz");
    }
}
