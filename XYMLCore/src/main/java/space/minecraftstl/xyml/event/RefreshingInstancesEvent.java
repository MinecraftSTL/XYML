/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
