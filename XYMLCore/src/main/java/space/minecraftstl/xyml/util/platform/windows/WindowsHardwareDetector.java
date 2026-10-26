// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.platform.windows;

import space.minecraftstl.xyml.util.platform.NativeUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.hardware.CentralProcessor;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;
import space.minecraftstl.xyml.util.platform.hardware.HardwareDetector;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/**
 * @author Glavo
 */
public final class WindowsHardwareDetector extends HardwareDetector {

    @Override
    public @Nullable CentralProcessor detectCentralProcessor() {
        if (!OperatingSystem.isWindows7OrLater())
            return null;
        return WindowsCPUDetector.detect();
    }

    @Override
    public List<GraphicsCard> detectGraphicsCards() {
        if (!OperatingSystem.isWindows7OrLater())
            return null;
        return WindowsGPUDetector.detect();
    }

    @Override
    public long getTotalMemorySize() {
        if (NativeUtils.USE_JNA) {
            Kernel32 kernel32 = Kernel32.INSTANCE;
            if (kernel32 != null) {
                WinTypes.MEMORYSTATUSEX status = new WinTypes.MEMORYSTATUSEX();
                if (kernel32.GlobalMemoryStatusEx(status))
                    return status.ullTotalPhys;
                else
                    LOG.warning("Failed to get memory status: " + kernel32.GetLastError());
            }
        }

        return super.getTotalMemorySize();
    }

    @Override
    public long getFreeMemorySize() {
        if (NativeUtils.USE_JNA) {
            Kernel32 kernel32 = Kernel32.INSTANCE;
            if (kernel32 != null) {
                WinTypes.MEMORYSTATUSEX status = new WinTypes.MEMORYSTATUSEX();
                if (kernel32.GlobalMemoryStatusEx(status))
                    return status.ullAvailPhys;
                else
                    LOG.warning("Failed to get memory status: " + kernel32.GetLastError());
            }
        }

        return super.getFreeMemorySize();
    }
}
