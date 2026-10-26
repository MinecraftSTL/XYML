// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;

/// Reports a known unsupported loader installation combination by stable reason code.
@NotNullByDefault
public class UnsupportedInstallationException extends Exception {

    private final int reason;

    public UnsupportedInstallationException(int reason) {
        this.reason = reason;
    }

    public int getReason() {
        return reason;
    }

    // e.g. Forge is not compatible with fabric.
    public static final int UNSUPPORTED_LAUNCH_WRAPPER = 1;

    // 1.17: OptiFine>=H1 Pre2 is compatible with Forge.
    public static final int FORGE_1_17_OPTIFINE_H1_PRE2 = 2;

    public static final int FABRIC_NOT_COMPATIBLE_WITH_FORGE = 3;

    /// Cleanroom and Forge cannot be installed into the same instance.
    public static final int CLEANROOM_NOT_COMPATIBLE_WITH_FORGE = 4;
}
