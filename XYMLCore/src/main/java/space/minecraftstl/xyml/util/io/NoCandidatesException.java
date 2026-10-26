// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
