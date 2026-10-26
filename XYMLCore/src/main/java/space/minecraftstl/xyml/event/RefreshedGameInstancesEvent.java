// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.event;

import org.jetbrains.annotations.NotNullByDefault;

/// Fired after all installed game instances in a `.minecraft` folder have been loaded.
///
/// This event is fired on [EventBus#EVENT_BUS].
///
/// @author huangyuhui
@NotNullByDefault
public final class RefreshedGameInstancesEvent extends Event {

    /// Creates an instances-refreshed event.
    ///
    /// @param source repository that loaded the instances
    public RefreshedGameInstancesEvent(Object source) {
        super(source);
    }
}
