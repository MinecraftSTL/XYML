// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;

/// Indicates that a requested installed game instance is absent from a repository snapshot.
@NotNullByDefault
public class NoSuchGameInstanceException extends RuntimeException {
    /// Missing instance identifier.
    private final GameInstanceID id;

    /// Creates an exception for the missing identifier.
    ///
    /// @param id missing instance identifier
    public NoSuchGameInstanceException(GameInstanceID id) {
        super(id.id());
        this.id = id;
    }

    /// Returns the missing instance identifier.
    public GameInstanceID getId() {
        return id;
    }
}
