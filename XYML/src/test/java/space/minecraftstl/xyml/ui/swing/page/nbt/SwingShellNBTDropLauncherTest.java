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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Verifies application-wide NBT filtering and editor lifecycle delegation.
@NotNullByDefault
final class SwingShellNBTDropLauncherTest {
    /// Accepts every supported NBT family throughout the shell and detaches on close.
    @Test
    void acceptsSupportedNbtThroughoutShell() {
        AtomicInteger editorCloseCount = new AtomicInteger();
        List<Path> opened = new ArrayList<>();

        EdtDispatcher.executeAndWait(() -> {
            JPanel shell = new JPanel();
            SwingShellNBTDropLauncher launcher = SwingShellNBTDropLauncher.install(
                    shell,
                    opened::add,
                    editorCloseCount::incrementAndGet);
            TransferHandler handler = Objects.requireNonNull(shell.getTransferHandler());

            assertTrue(handler.importData(fileTransfer(shell, List.of(Path.of("level.DAT")))));
            assertTrue(handler.importData(fileTransfer(shell, List.of(Path.of("player.nbt")))));
            assertTrue(handler.importData(fileTransfer(shell, List.of(Path.of("r.0.0.MCA")))));
            assertTrue(handler.importData(fileTransfer(shell, List.of(Path.of("r.0.0.mcr")))));
            assertFalse(handler.canImport(fileTransfer(shell, List.of(Path.of("version.json")))));

            launcher.close();
            launcher.close();
            assertNull(shell.getTransferHandler());
        });

        assertEquals(List.of(
                Path.of("level.DAT").toAbsolutePath().normalize(),
                Path.of("player.nbt").toAbsolutePath().normalize(),
                Path.of("r.0.0.MCA").toAbsolutePath().normalize(),
                Path.of("r.0.0.mcr").toAbsolutePath().normalize()), opened);
        assertEquals(1, editorCloseCount.get());
    }
}
