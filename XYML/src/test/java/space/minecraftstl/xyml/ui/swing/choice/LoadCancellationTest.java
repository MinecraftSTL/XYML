// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
