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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the compact segmented progress state used by the ordinary launch button.
@NotNullByDefault
public final class LaunchProgressButtonTest {
    /// The strip remains hidden until the ordinary launch command becomes active.
    @Test
    public void hidesProgressUntilLaunchStarts() {
        LaunchProgressButton button = new LaunchProgressButton();

        assertAll(
                () -> assertFalse(button.isProgressVisible()),
                () -> assertEquals(0, button.filledSegmentCount()));

        button.setProgressVisible(true);
        button.setProgress(OptionalDouble.of(0.5));

        assertAll(
                () -> assertTrue(button.isProgressVisible()),
                () -> assertEquals(4, button.filledSegmentCount()));
    }

    /// Unknown progress still exposes an active gray block, while known progress fills left to right.
    @Test
    public void representsIndeterminateAndCompleteProgress() {
        LaunchProgressButton button = new LaunchProgressButton();
        button.setProgressVisible(true);

        button.setProgress(OptionalDouble.empty());
        assertEquals(1, button.filledSegmentCount());

        button.setProgress(OptionalDouble.of(1.0));
        assertEquals(LaunchProgressButton.SEGMENT_COUNT, button.filledSegmentCount());
    }
}
