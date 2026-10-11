/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
