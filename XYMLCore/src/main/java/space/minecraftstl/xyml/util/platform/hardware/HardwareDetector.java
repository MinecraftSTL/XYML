// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.platform.hardware;

import org.jetbrains.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * @author Glavo
 */
public class HardwareDetector {
    private static final boolean USE_FAST_FETCH = "true".equalsIgnoreCase(System.getProperty("xyml.hardware.fastfetch", "true"));

    public @Nullable CentralProcessor detectCentralProcessor() {
        return USE_FAST_FETCH ? FastFetchUtils.detectCentralProcessor() : null;
    }

    public @Nullable List<GraphicsCard> detectGraphicsCards() {
        return USE_FAST_FETCH ? FastFetchUtils.detectGraphicsCards() : null;
    }

    public long getTotalMemorySize() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
                return bean.getTotalMemorySize();
            }
        } catch (NoClassDefFoundError ignored) {
        }

        return 0L;
    }

    public long getFreeMemorySize() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
                return bean.getFreeMemorySize();
            }
        } catch (NoClassDefFoundError ignored) {
        }

        return 0L;
    }
}
