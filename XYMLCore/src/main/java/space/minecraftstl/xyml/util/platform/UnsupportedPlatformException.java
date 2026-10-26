// SPDX-FileCopyrightText: Copyright (C) 2024 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.platform;

/**
 * @author Glavo
 */
public final class UnsupportedPlatformException extends Exception {
    public UnsupportedPlatformException() {
    }

    public UnsupportedPlatformException(String message) {
        super(message);
    }
}
