/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
