// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Reports an optimistic conflict before a draft changes committed files.
@NotNullByDefault
public final class GameRepositoryDraftConflictException extends IOException {
    /// Creates a non-sensitive conflict diagnostic.
    public GameRepositoryDraftConflictException(String message) {
        super(message);
    }
}
