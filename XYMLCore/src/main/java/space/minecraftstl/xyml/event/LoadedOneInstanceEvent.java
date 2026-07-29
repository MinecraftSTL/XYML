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
import space.minecraftstl.xyml.game.Version;
import space.minecraftstl.xyml.util.ToStringBuilder;

/// Fired when one installed instance manifest has been loaded during a repository refresh.
///
/// Listeners may deny the event to exclude the instance from the refreshed repository snapshot.
///
/// @author huangyuhui
@NotNullByDefault
public final class LoadedOneInstanceEvent extends Event {

    /// Loaded instance version manifest.
    private final Version instance;

    /// Creates an instance-loaded event.
    ///
    /// @param source repository that loaded the instance
    /// @param instance loaded instance version manifest
    public LoadedOneInstanceEvent(Object source, Version instance) {
        super(source);
        this.instance = instance;
    }

    /// Returns the loaded instance version manifest.
    public Version getInstance() {
        return instance;
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
                .append("instance", instance)
                .toString();
    }
}
