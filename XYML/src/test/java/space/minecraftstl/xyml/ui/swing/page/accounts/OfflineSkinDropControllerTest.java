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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Verifies PNG-only offline-skin drop classification and route lifecycle.
@NotNullByDefault
public final class OfflineSkinDropControllerTest {
    /// Accepts one PNG only while writable and detaches its route on close.
    @Test
    public void acceptsOnePngWhileWritableAndDetachesOnClose() {
        AtomicBoolean writable = new AtomicBoolean(true);
        List<Path> selected = new ArrayList<>();

        EdtDispatcher.executeAndWait(() -> {
            JPanel preview = new JPanel();
            OfflineSkinDropController controller = OfflineSkinDropController.install(
                    preview,
                    writable::get,
                    selected::add);
            TransferHandler handler = Objects.requireNonNull(preview.getTransferHandler());
            Path skin = Path.of("Player.PNG");

            assertTrue(handler.importData(fileTransfer(preview, List.of(skin))));
            assertFalse(handler.canImport(fileTransfer(preview, List.of(Path.of("cape.jpg")))));
            assertFalse(handler.canImport(fileTransfer(preview, List.of(skin, Path.of("second.png")))));
            assertEquals(List.of(skin.toAbsolutePath().normalize()), selected);

            writable.set(false);
            assertFalse(handler.canImport(fileTransfer(preview, List.of(Path.of("other.png")))));
            controller.close();
            assertNull(preview.getTransferHandler());
        });
    }
}
