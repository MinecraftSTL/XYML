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
// Added by MinecraftSTL in 2026 for explicit tolerant-read diagnostics.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// One immutable diagnostic emitted by a tolerant NBT read.
@NotNullByDefault
public final class NBTReadIssue {
    /// Diagnostic severity ordered from recoverable to data loss.
    @NotNullByDefault
    public enum Severity {
        /// The source is readable and no repair publication is required.
        INFORMATIONAL,
        /// The reader repaired framing or ignored a non-semantic defect.
        RECOVERED,
        /// Some bytes or values could not be reconstructed.
        PARTIAL_DATA_LOSS,
        /// No trustworthy root could be produced.
        ERROR
    }

    private final Severity severity;
    private final String code;
    private final String path;
    private final String message;

    /// Creates one diagnostic.
    ///
    /// @param severity severity
    /// @param code stable machine-readable code
    /// @param path logical NBT path, or empty when not applicable
    /// @param message concise human-readable explanation
    public NBTReadIssue(Severity severity, String code, String path, String message) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = requireText(code, "code");
        this.path = Objects.requireNonNull(path, "path");
        this.message = requireText(message, "message");
    }

    /// Returns the severity.
    public Severity severity() {
        return severity;
    }

    /// Returns the stable issue code.
    public String code() {
        return code;
    }

    /// Returns the logical path associated with the issue.
    public String path() {
        return path;
    }

    /// Returns the human-readable explanation.
    public String message() {
        return message;
    }

    /// Returns a compact diagnostic string.
    @Override
    public String toString() {
        return severity + "[" + code + "] " + (path.isEmpty() ? message : path + ": " + message);
    }

    /// Compares all diagnostic fields.
    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof NBTReadIssue other
                && severity == other.severity
                && code.equals(other.code)
                && path.equals(other.path)
                && message.equals(other.message);
    }

    /// Returns a hash code consistent with [#equals(Object)].
    @Override
    public int hashCode() {
        return Objects.hash(severity, code, path, message);
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }
}
