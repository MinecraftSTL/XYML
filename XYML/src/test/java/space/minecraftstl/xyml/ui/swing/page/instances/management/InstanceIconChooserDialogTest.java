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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.setting.InstanceIconType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JToggleButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the complete fixed-size single-select instance icon dialog content.
@NotNullByDefault
final class InstanceIconChooserDialogTest {
    /// Temporary directory used to normalize a custom image selection.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Exposes the exact fourteen built-in choices plus the custom-image entry.
    @Test
    void containsCustomEntryAndFourteenLegacyBuiltIns() {
        InstanceIconImages.preloadBuiltIns();
        AtomicReference<InstanceIconChooserDialog> dialogReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> dialogReference.set(new InstanceIconChooserDialog(
                InstanceIconType.FORGE,
                false,
                InstanceOverviewStrings.english())));

        EdtDispatcher.executeAndWait(() -> {
            InstanceIconChooserDialog dialog = Objects.requireNonNull(dialogReference.get());
            List<JToggleButton> buttons = descendants(dialog.content(), JToggleButton.class);
            assertEquals(15, buttons.size());
            assertEquals(14, InstanceIconChooserDialog.builtInTypes().size());
            assertEquals(14, InstanceIconChooserDialog.builtInTypes().stream().distinct().count());
            assertFalse(InstanceIconChooserDialog.builtInTypes().contains(InstanceIconType.DEFAULT));

            for (JToggleButton button : buttons) {
                assertEquals(new Dimension(64, 64), button.getPreferredSize());
                assertNotNull(button.getIcon());
                assertNotNull(button.getToolTipText());
                assertNotNull(button.getAccessibleContext().getAccessibleName());
            }
            assertTrue(findNamed(buttons, "instanceIconFORGE").isSelected());
            assertFalse(findNamed(buttons, "instanceIconCustom").isSelected());
        });
    }

    /// Resolves a bundled selection without invoking the custom file chooser.
    @Test
    void resolvesBuiltInWithoutOpeningCustomFileChooser() {
        InstanceIconImages.preloadBuiltIns();
        AtomicReference<InstanceIconChoice> choiceReference = new AtomicReference<>();
        AtomicInteger customChooserCount = new AtomicInteger();
        EdtDispatcher.executeAndWait(() -> {
            InstanceIconChooserDialog dialog = new InstanceIconChooserDialog(
                    InstanceIconType.NEO_FORGE,
                    false,
                    InstanceOverviewStrings.english());
            choiceReference.set(dialog.selectedChoice(() -> {
                customChooserCount.incrementAndGet();
                return Objects.requireNonNull(temporaryDirectory, "temporaryDirectory")
                        .resolve("unused.png");
            }));
        });

        InstanceIconChoice.BuiltIn choice = assertInstanceOf(
                InstanceIconChoice.BuiltIn.class,
                choiceReference.get());
        assertEquals(InstanceIconType.NEO_FORGE, choice.iconType());
        assertEquals(0, customChooserCount.get());
    }

    /// Keeps a current custom image selected and normalizes the acquired path.
    @Test
    void resolvesCustomImageThroughExistingFileChooserBoundary() {
        InstanceIconImages.preloadBuiltIns();
        AtomicReference<InstanceIconChoice> choiceReference = new AtomicReference<>();
        Path relativeSelection = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory")
                .resolve("folder")
                .resolve("..")
                .resolve("icon.png");
        EdtDispatcher.executeAndWait(() -> {
            InstanceIconChooserDialog dialog = new InstanceIconChooserDialog(
                    InstanceIconType.DEFAULT,
                    true,
                    InstanceOverviewStrings.english());
            List<JToggleButton> buttons = descendants(dialog.content(), JToggleButton.class);
            assertTrue(findNamed(buttons, "instanceIconCustom").isSelected());
            choiceReference.set(dialog.selectedChoice(() -> relativeSelection));
        });

        InstanceIconChoice.Custom choice = assertInstanceOf(
                InstanceIconChoice.Custom.class,
                choiceReference.get());
        assertEquals(relativeSelection.toAbsolutePath().normalize(), choice.file());
    }

    /// Collects every descendant assignable to the requested component type.
    ///
    /// @param root component tree root
    /// @param type requested component type
    /// @param <T> component type
    /// @return immutable matching component list in visual order
    private static <T extends Component> @Unmodifiable List<T> descendants(Container root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                matches.add(type.cast(component));
            }
            if (component instanceof Container child) {
                matches.addAll(descendants(child, type));
            }
        }
        return List.copyOf(matches);
    }

    /// Finds one named toggle from a previously collected list.
    ///
    /// @param buttons candidate buttons
    /// @param name stable component name
    /// @return matching button
    private static JToggleButton findNamed(List<JToggleButton> buttons, String name) {
        return buttons.stream()
                .filter(button -> name.equals(button.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing button: " + name));
    }
}
