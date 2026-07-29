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
import space.minecraftstl.xyml.util.ToStringBuilder;

/// Fired before an installed game instance is renamed.
///
/// Listeners may deny the event to cancel the rename.
///
/// @author huangyuhui
@NotNullByDefault
public final class RenameInstanceEvent extends Event {

    /// Current instance identifier.
    private final String fromInstanceId;

    /// Requested destination instance identifier.
    private final String toInstanceId;

    /// Creates an instance-rename event.
    ///
    /// @param source repository renaming the instance
    /// @param fromInstanceId current instance identifier
    /// @param toInstanceId requested destination instance identifier
    public RenameInstanceEvent(Object source, String fromInstanceId, String toInstanceId) {
        super(source);
        this.fromInstanceId = fromInstanceId;
        this.toInstanceId = toInstanceId;
    }

    /// Returns the current instance identifier.
    public String getFromInstanceId() {
        return fromInstanceId;
    }

    /// Returns the requested destination instance identifier.
    public String getToInstanceId() {
        return toInstanceId;
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
                .append("fromInstanceId", fromInstanceId)
                .append("toInstanceId", toInstanceId)
                .toString();
    }
}
