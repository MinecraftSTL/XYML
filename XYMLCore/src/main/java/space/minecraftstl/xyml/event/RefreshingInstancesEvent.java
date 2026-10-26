// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.event;

import org.jetbrains.annotations.NotNullByDefault;

/// Fired before installed game instances in a `.minecraft` folder are loaded.
///
/// This event is fired on the [space.minecraftstl.xyml.event.EventBus#EVENT_BUS]
///
/// @author huangyuhui
@NotNullByDefault
public final class RefreshingInstancesEvent extends Event {

    /// Constructor.
    public RefreshingInstancesEvent(Object source) {
        super(source);
    }

    /// Returns whether listeners may allow or deny this event.
    @Override
    public boolean hasResult() {
        return true;
    }
}
