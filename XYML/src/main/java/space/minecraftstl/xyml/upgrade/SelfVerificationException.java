// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Distinguishes local launcher integrity failure from update transport failures.
@NotNullByDefault
public final class SelfVerificationException extends IOException {
    /// Creates a stable diagnostic without an endpoint or response payload.
    public SelfVerificationException() {
        super("Launcher integrity verification failed");
    }
}
