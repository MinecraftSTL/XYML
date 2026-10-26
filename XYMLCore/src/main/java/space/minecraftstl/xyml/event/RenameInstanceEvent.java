// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.event;

import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.util.ToStringBuilder;
import org.jetbrains.annotations.NotNullByDefault;

/// Fired before an installed game instance is renamed.
///
/// Listeners may deny the event to cancel the rename.
@NotNullByDefault
public final class RenameInstanceEvent extends Event {

    /// Current instance identifier.
    private final GameInstanceID from;

    /// Requested destination instance identifier.
    private final GameInstanceID to;

    /// Creates an instance-rename event.
    ///
    /// @param source repository renaming the instance
    /// @param from current instance identifier
    /// @param to requested destination instance identifier
    public RenameInstanceEvent(Object source, GameInstanceID from, GameInstanceID to) {
        super(source);
        this.from = from;
        this.to = to;
    }

    /// Returns the current instance identifier.
    public GameInstanceID getFrom() {
        return from;
    }

    /// Returns the requested destination instance identifier.
    public GameInstanceID getTo() {
        return to;
    }

    /// Returns whether listeners may allow or deny this event.
    @Override
    public boolean hasResult() {
        return true;
    }

    /// Returns a diagnostic representation of this event.
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("source", source)
                .append("from", from)
                .append("to", to)
                .toString();
    }
}
