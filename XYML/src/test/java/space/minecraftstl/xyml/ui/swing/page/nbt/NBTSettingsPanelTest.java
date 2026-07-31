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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies the embeddable settings entry and its owned editor lifecycle.
@NotNullByDefault
final class NBTSettingsPanelTest {
    /// The detached page exposes one localized command but does not open a native chooser.
    @Test
    void exposesSettingsCommandAndWaitsForContainingFrame() {
        AtomicInteger chooserCalls = new AtomicInteger();
        AtomicReference<@Nullable NBTSettingsPanel> panelReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            SwingNBTEditorLauncher launcher = new SwingNBTEditorLauncher(
                    () -> {
                        chooserCalls.incrementAndGet();
                        return Path.of("level.dat");
                    },
                    closedObserver -> new RecordingEditorWindow(closedObserver));
            NBTSettingsPanel panel = new NBTSettingsPanel(NBTEditorStrings.english(), launcher);
            panelReference.set(panel);
            assertEquals("NBT Editor", panel.tabTitle());
            assertEquals("Open NBT file", panel.openButton().getText());
            assertEquals("settingsOpenNbtFile", panel.openButton().getName());
            panel.openButton().doClick();
        });

        assertEquals(0, chooserCalls.get());
        Objects.requireNonNull(panelReference.get()).close();
    }

    /// Closing the settings page releases its current modeless editor and disables the command.
    @Test
    void closesOwnedEditorLifecycle() {
        AtomicReference<@Nullable RecordingEditorWindow> windowReference = new AtomicReference<>();
        AtomicReference<@Nullable NBTSettingsPanel> panelReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            SwingNBTEditorLauncher launcher = new SwingNBTEditorLauncher(
                    () -> Path.of("level.dat"),
                    closedObserver -> {
                        RecordingEditorWindow window = new RecordingEditorWindow(closedObserver);
                        windowReference.set(window);
                        return window;
                    });
            NBTSettingsPanel panel = new NBTSettingsPanel(NBTEditorStrings.english(), launcher);
            panelReference.set(panel);
            launcher.chooseAndOpen();
            panel.close();
            assertFalse(panel.openButton().isEnabled());
        });

        assertEquals(1, Objects.requireNonNull(windowReference.get()).closeCount());
        Objects.requireNonNull(panelReference.get()).close();
    }

    /// Minimal editor-window fake used to observe settings-owned closure.
    @NotNullByDefault
    private static final class RecordingEditorWindow implements SwingNBTEditorLauncher.EditorWindow {
        /// Terminal disposal observer supplied by the launcher.
        private final Consumer<SwingNBTEditorLauncher.EditorWindow> closedObserver;

        /// Number of terminal close commands.
        private int closeCount;

        /// Creates one fake editor window.
        ///
        /// @param closedObserver terminal disposal observer
        private RecordingEditorWindow(
                Consumer<SwingNBTEditorLauncher.EditorWindow> closedObserver) {
            this.closedObserver = Objects.requireNonNull(closedObserver, "closedObserver");
        }

        /// Accepts the selected path without external I/O.
        ///
        /// @param source supported normalized source
        @Override
        public void open(Path source) {
            Objects.requireNonNull(source, "source");
        }

        /// Performs no native visibility action.
        @Override
        public void showOrFocus() {
            // Headless fake has no native window.
        }

        /// Performs no user-confirmed closure action.
        @Override
        public void requestClose() {
            // Headless fake has no dirty-document prompt.
        }

        /// Records terminal closure and reports disposal.
        @Override
        public void close() {
            ++closeCount;
            closedObserver.accept(this);
        }

        /// Returns the terminal close count.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount;
        }
    }
}
