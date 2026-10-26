// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;

/// Joins owner-managed state to a repository draft's commit and reverse-order rollback.
/// Validation must not publish state. Apply and rollback run outside the repository monitor;
/// implementations must marshal state access to its owning thread and drain pending saves.
@NotNullByDefault
public interface GameRepositoryDraftParticipant {
    /// Checks that the captured owner state is still current without modifying it.
    void validate() throws Exception;

    /// Applies the prepared change and waits for its owner-managed persistence.
    void apply() throws Exception;

    /// Restores this participant's owned fields after an attempted apply.
    void rollback() throws Exception;
}
