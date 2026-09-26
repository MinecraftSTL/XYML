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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Container;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Exercises movement and posture selector synchronization.
@NotNullByDefault
public final class OfflineSkinPreviewControlsTest {
    /// Sprinting forces standing, while changing posture away from standing downgrades sprinting to walking.
    @Test
    public void synchronizesSprintingWithStanding() {
        EdtDispatcher.executeAndWait(() -> {
            OfflineSkinPreviewControls controls = new OfflineSkinPreviewControls();
            JComboBox<?> motion = find(controls, "offlineSkinPreviewMotion");
            JComboBox<?> posture = find(controls, "offlineSkinPreviewPosture");

            posture.setSelectedItem(SkinPreviewPosture.SNEAKING);
            motion.setSelectedItem(SkinPreviewMotion.SPRINTING);
            assertAll(
                    () -> assertEquals(SkinPreviewPosture.STANDING, posture.getSelectedItem()),
                    () -> assertEquals(SkinPreviewMotion.SPRINTING, controls.preview().motion()),
                    () -> assertEquals(SkinPreviewPosture.STANDING, controls.preview().posture()));

            posture.setSelectedItem(SkinPreviewPosture.PRONE);
            assertAll(
                    () -> assertEquals(SkinPreviewMotion.WALKING, motion.getSelectedItem()),
                    () -> assertEquals(SkinPreviewMotion.WALKING, controls.preview().motion()),
                    () -> assertEquals(SkinPreviewPosture.PRONE, controls.preview().posture()));
        });
    }

    /// Finds one named selector recursively.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @return matching selector
    private static JComboBox<?> find(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> selector && Objects.equals(name, selector.getName())) {
                return selector;
            }
            if (component instanceof Container nested) {
                @Nullable JComboBox<?> result = findOrNull(nested, name);
                if (result != null) {
                    return result;
                }
            }
        }
        throw new AssertionError("Missing component: " + name);
    }

    /// Finds one named selector when present.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @return matching selector, or null
    private static @Nullable JComboBox<?> findOrNull(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> selector && Objects.equals(name, selector.getName())) {
                return selector;
            }
            if (component instanceof Container nested) {
                @Nullable JComboBox<?> result = findOrNull(nested, name);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}
