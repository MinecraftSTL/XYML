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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests linked cooperative cancellation without transferring signal ownership.
@NotNullByDefault
public final class LoadCancellationTest {
    /// Verifies either upstream signal is observed without changing its peer.
    @Test
    public void linkedSignalObservesEitherUpstream() {
        LoadCancellation first = new LoadCancellation();
        LoadCancellation second = new LoadCancellation();
        LoadCancellation linked = LoadCancellation.linkedTo(first, second);

        assertFalse(linked.isCancelled());
        first.cancel();

        assertTrue(linked.isCancelled());
        assertFalse(second.isCancelled());
    }

    /// Verifies cancelling a linked signal does not mutate caller-owned upstream signals.
    @Test
    public void linkedSignalCancellationRemainsLocal() {
        LoadCancellation first = new LoadCancellation();
        LoadCancellation second = new LoadCancellation();
        LoadCancellation linked = LoadCancellation.linkedTo(first, second);

        linked.cancel();

        assertTrue(linked.isCancelled());
        assertFalse(first.isCancelled());
        assertFalse(second.isCancelled());
    }
}
