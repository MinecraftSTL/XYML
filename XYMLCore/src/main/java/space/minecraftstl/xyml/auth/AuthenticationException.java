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
package space.minecraftstl.xyml.auth;

import org.jetbrains.annotations.NotNullByDefault;

/// Base checked exception for account authentication failures.
@NotNullByDefault
public class AuthenticationException extends Exception {

    /// Creates an authentication failure without a detail message.
    public AuthenticationException() {
    }

    /// Creates an authentication failure with a detail message.
    ///
    /// @param message detail message
    public AuthenticationException(String message) {
        super(message);
    }

    /// Creates an authentication failure with a detail message and cause.
    ///
    /// @param message detail message
    /// @param cause underlying failure
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }

    /// Creates an authentication failure from an underlying cause.
    ///
    /// @param cause underlying failure
    public AuthenticationException(Throwable cause) {
        super(cause);
    }
}
