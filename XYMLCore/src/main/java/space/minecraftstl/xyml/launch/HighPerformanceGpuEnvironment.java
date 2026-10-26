// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;
import space.minecraftstl.xyml.util.platform.hardware.HardwareVendor;

import java.util.List;
import java.util.Map;

/// Selects automatic process-environment overrides for an opted-in high-performance GPU launch.
@NotNullByDefault
public final class HighPerformanceGpuEnvironment {
    /// Immutable NVIDIA PRIME offload environment.
    private static final @Unmodifiable Map<String, String> NVIDIA_PRIME_ENVIRONMENT = Map.of(
            "__NV_PRIME_RENDER_OFFLOAD", "1",
            "__GLX_VENDOR_LIBRARY_NAME", "nvidia",
            "__VK_LAYER_NV_optimus", "NVIDIA_only");

    /// Prevents utility-class instantiation.
    private HighPerformanceGpuEnvironment() {
    }

    /// Resolves automatic environment variables for the selected launch configuration.
    ///
    /// Automatic selection is limited to Linux with the default renderer and exactly one integrated and one
    /// discrete adapter. The discrete adapter must be an NVIDIA device.
    ///
    /// @param enabled whether the user requested the high-performance GPU
    /// @param renderer selected renderer
    /// @param operatingSystem current operating system
    /// @param graphicsCards detected graphics cards, or `null` when detection failed
    /// @return immutable automatic environment variables, empty when the hardware is not eligible
    public static @Unmodifiable Map<String, String> resolve(
            boolean enabled,
            Renderer renderer,
            OperatingSystem operatingSystem,
            @Nullable List<GraphicsCard> graphicsCards) {
        if (!enabled
                || renderer != Renderer.DEFAULT
                || operatingSystem != OperatingSystem.LINUX
                || graphicsCards == null
                || graphicsCards.size() != 2) {
            return Map.of();
        }

        GraphicsCard first = graphicsCards.get(0);
        GraphicsCard second = graphicsCards.get(1);
        @Nullable GraphicsCard.Type firstType = first.getType();
        @Nullable GraphicsCard.Type secondType = second.getType();
        if (firstType == null || secondType == null || firstType == secondType) {
            return Map.of();
        }

        GraphicsCard discrete = firstType == GraphicsCard.Type.Discrete ? first : second;
        return HardwareVendor.NVIDIA.equals(discrete.getVendor()) ? NVIDIA_PRIME_ENVIRONMENT : Map.of();
    }
}
