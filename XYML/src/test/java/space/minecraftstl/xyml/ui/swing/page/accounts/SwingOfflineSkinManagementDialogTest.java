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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;

/// Exercises provider persistence and read-only control behavior in the native offline skin dialog.
@NotNullByDefault
public final class SwingOfflineSkinManagementDialogTest {
    /// Temporary valid skin image fixture.
    @TempDir
    private Path temporaryDirectory;

    /// LittleSkin and a valid custom endpoint are persisted exactly after explicit Save commands.
    @Test
    public void persistsSelectedProviderSources() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        FakeOfflineSkinStore store = new FakeOfflineSkinStore(true);

        EdtDispatcher.executeAndWait(() -> {
            SwingOfflineSkinManagementDialog dialog = new SwingOfflineSkinManagementDialog(
                    null,
                    store,
                    "offline-1",
                    Runnable::run);
            try {
                JComboBox<?> sources = findNamed(dialog.getContentPane(), "offlineSkinSourceType", JComboBox.class);
                AbstractButton save = findNamed(dialog.getContentPane(), "offlineSkinSave", AbstractButton.class);
                sources.setSelectedItem(Skin.Type.LITTLE_SKIN);
                assertTrue(save.isEnabled());
                save.doClick();
                assertEquals(Skin.Type.LITTLE_SKIN, Objects.requireNonNull(store.persisted.get()).type());

                sources.setSelectedItem(Skin.Type.CUSTOM_SKIN_LOADER_API);
                JTextField api = findNamed(dialog.getContentPane(), "offlineSkinCustomApi", JTextField.class);
                api.setText("not a valid endpoint");
                assertFalse(save.isEnabled());
                api.setText("skins.example.test/csl");
                assertTrue(save.isEnabled());
                save.doClick();

                @Nullable Skin persisted = store.persisted.get();
                assertAll(
                        () -> assertNotNull(persisted),
                        () -> assertEquals(Skin.Type.CUSTOM_SKIN_LOADER_API, persisted.type()),
                        () -> assertEquals("skins.example.test/csl", persisted.cslApi()));
            } finally {
                dialog.close();
            }
        });
    }

    /// A read-only account renders its current preview while every persistence control stays disabled.
    @Test
    public void disablesMutationControlsForReadOnlyAccount() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        FakeOfflineSkinStore store = new FakeOfflineSkinStore(false);

        EdtDispatcher.executeAndWait(() -> {
            SwingOfflineSkinManagementDialog dialog = new SwingOfflineSkinManagementDialog(
                    null,
                    store,
                    "offline-1",
                    Runnable::run);
            try {
                assertAll(
                        () -> assertFalse(findNamed(
                                dialog.getContentPane(),
                                "offlineSkinSourceType",
                                JComboBox.class).isEnabled()),
                        () -> assertFalse(findNamed(
                                dialog.getContentPane(),
                                "offlineSkinSave",
                                AbstractButton.class).isEnabled()),
                        () -> assertFalse(findNamed(
                                dialog.getContentPane(),
                                "offlineSkinRestoreDefault",
                                AbstractButton.class).isEnabled()),
                        () -> assertTrue(findNamed(
                                dialog.getContentPane(),
                                "offlineSkinClose",
                                AbstractButton.class).isEnabled()));
            } finally {
                dialog.close();
            }
        });
    }

    /// A dropped PNG switches to the local source and enters the existing image-validation flow.
    @Test
    public void stagesDroppedPngAsLocalSkin() throws IOException {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path skin = temporaryDirectory.resolve("Dropped.PNG");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        FakeOfflineSkinStore store = new FakeOfflineSkinStore(true);

        EdtDispatcher.executeAndWait(() -> {
            SwingOfflineSkinManagementDialog dialog = new SwingOfflineSkinManagementDialog(
                    null,
                    store,
                    "offline-1",
                    Runnable::run);
            JComponent preview = findNamed(
                    dialog.getContentPane(),
                    "offlineSkinPreview",
                    JComponent.class);
            try {
                TransferHandler handler = Objects.requireNonNull(preview.getTransferHandler());

                assertTrue(handler.importData(fileTransfer(preview, List.of(skin))));
                assertEquals(
                        Skin.Type.LOCAL_FILE,
                        findNamed(
                                dialog.getContentPane(),
                                "offlineSkinSourceType",
                                JComboBox.class).getSelectedItem());
                assertEquals(
                        skin.toString(),
                        findNamed(
                                dialog.getContentPane(),
                                "offlineSkinLocalFile",
                                JTextField.class).getText());
            } finally {
                dialog.close();
            }
            assertNull(preview.getTransferHandler());
        });
    }

    /// Locates one named Swing component recursively.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends JComponent> T findNamed(Container root, String name, Class<T> type) {
        @Nullable T result = findNamedOrNull(root, name, type);
        if (result == null) {
            throw new AssertionError("Missing component: " + name);
        }
        return result;
    }

    /// Locates one named Swing component recursively when present.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or null
    private static <T extends JComponent> @Nullable T findNamedOrNull(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamedOrNull(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// In-memory offline skin store with selectable mutability.
    @NotNullByDefault
    private static final class FakeOfflineSkinStore implements OfflineSkinStore {
        /// Whether the fake account accepts persistence.
        private final boolean writable;

        /// Latest persisted skin, or null for the launcher default.
        private final AtomicReference<@Nullable Skin> persisted = new AtomicReference<>();

        /// Creates one fake offline account store.
        ///
        /// @param writable whether mutations are accepted
        private FakeOfflineSkinStore(boolean writable) {
            this.writable = writable;
        }

        /// Returns the current fake offline account state.
        ///
        /// @param accountId stable account identifier
        /// @return current fake snapshot
        @Override
        public Optional<OfflineSkinSnapshot> snapshot(String accountId) {
            return Optional.of(new OfflineSkinSnapshot(
                    Objects.requireNonNull(accountId, "accountId"),
                    "Player",
                    persisted.get(),
                    writable,
                    null));
        }

        /// Stores the selected skin when this fake account is writable.
        ///
        /// @param accountId stable account identifier
        /// @param skin replacement skin, or null for the launcher default
        @Override
        public void setSkin(String accountId, @Nullable Skin skin) {
            Objects.requireNonNull(accountId, "accountId");
            if (!writable) {
                throw new IllegalStateException("Read-only fake account");
            }
            persisted.set(skin);
        }
    }
}
