// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.authlibinjector;

import space.minecraftstl.xyml.auth.AuthenticationException;

/**
 * @author yushijinhun
 */
public class AuthlibInjectorDownloadException extends AuthenticationException {

    public AuthlibInjectorDownloadException() {
    }

    public AuthlibInjectorDownloadException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuthlibInjectorDownloadException(String message) {
        super(message);
    }

    public AuthlibInjectorDownloadException(Throwable cause) {
        super(cause);
    }
}
