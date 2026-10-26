// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
import static org.junit.jupiter.api.Assertions.assertFalse;

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

            posture.setSelectedItem(SkinPreviewPosture.SWIMMING);
            assertAll(
                    () -> assertEquals(SkinPreviewMotion.WALKING, motion.getSelectedItem()),
                    () -> assertEquals(SkinPreviewMotion.WALKING, controls.preview().motion()),
                    () -> assertEquals(SkinPreviewPosture.SWIMMING, controls.preview().posture()));

            posture.setSelectedItem(SkinPreviewPosture.RIDING);
            assertAll(
                    () -> assertFalse(motion.isEnabled()),
                    () -> assertEquals(SkinPreviewMotion.IDLE, motion.getSelectedItem()),
                    () -> assertEquals(SkinPreviewMotion.IDLE, controls.preview().motion()),
                    () -> assertEquals(SkinPreviewPosture.RIDING, controls.preview().posture()));
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
