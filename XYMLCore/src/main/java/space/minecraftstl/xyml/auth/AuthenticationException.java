// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
