// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.event;

import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.util.ToStringBuilder;
import org.jetbrains.annotations.NotNullByDefault;

/// Fired before an installed game instance is removed.
///
/// This event is fired on the [space.minecraftstl.xyml.event.EventBus#EVENT_BUS]
///
/// @author huangyuhui
@NotNullByDefault
public class RemoveInstanceEvent extends Event {

    /// Identifier of the instance scheduled for removal.
    private final GameInstanceID instanceId;

    /// Creates an instance-removal event.
    ///
    /// @param source repository removing the instance
    /// @param instanceId identifier of the instance scheduled for removal
    public RemoveInstanceEvent(Object source, GameInstanceID instanceId) {
        super(source);
        this.instanceId = instanceId;
    }

    /// Returns the identifier of the instance scheduled for removal.
    public GameInstanceID getInstanceId() {
        return instanceId;
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
                .append("instanceId", instanceId)
                .toString();
    }
}
