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
package space.minecraftstl.xyml.util.io;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Signals that a provider candidate list was unexpectedly empty before any request could be attempted.
@NotNullByDefault
public final class NoCandidatesException extends IOException {
    /// Serialization identifier for this immutable exception type.
    private static final long serialVersionUID = 1L;

    /// Creates an exception with the stable provider-candidate diagnostic.
    public NoCandidatesException() {
        super("No candidates found");
    }
}
