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
package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for detached launcher state behavior.
@NotNullByDefault
public final class LauncherStateTest {
    /// Tests that neutral properties and maps publish aggregate state revisions.
    @Test
    public void publishesNeutralStateChanges() {
        LauncherState state = new LauncherState();
        long initialRevision = Objects.requireNonNull(state.changes().getValue());

        state.setWidth(1280.0);
        long afterProperty = Objects.requireNonNull(state.changes().getValue());
        assertTrue(afterProperty > initialRevision);

        state.getShownTips().put("javaVersionTip", 21);
        assertTrue(Objects.requireNonNull(state.changes().getValue()) > afterProperty);
    }
}
