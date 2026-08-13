/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.Renderer;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.hardware.GraphicsCard;
import space.minecraftstl.xyml.util.platform.hardware.HardwareVendor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Linux high-performance GPU launch-environment selection.
@NotNullByDefault
final class HighPerformanceGpuEnvironmentTest {
    /// Resolves NVIDIA PRIME variables for an enabled Intel/NVIDIA hybrid setup.
    @Test
    void resolvesPrimeVariablesForEnabledNvidiaHybridGraphics() {
        Map<String, String> environment = HighPerformanceGpuEnvironment.resolve(
                true,
                Renderer.DEFAULT,
                OperatingSystem.LINUX,
                List.of(integratedIntel(), discreteNvidia()));

        assertEquals("1", environment.get("__NV_PRIME_RENDER_OFFLOAD"));
        assertEquals("nvidia", environment.get("__GLX_VENDOR_LIBRARY_NAME"));
        assertEquals("NVIDIA_only", environment.get("__VK_LAYER_NV_optimus"));
    }

    /// Resolves no variables when the preference is disabled.
    @Test
    void ignoresHybridGraphicsWhenPreferenceIsDisabled() {
        assertTrue(HighPerformanceGpuEnvironment.resolve(
                false,
                Renderer.DEFAULT,
                OperatingSystem.LINUX,
                List.of(integratedIntel(), discreteNvidia())).isEmpty());
    }

    /// Leaves an explicit renderer selection authoritative.
    @Test
    void ignoresHybridGraphicsForExplicitRenderer() {
        assertTrue(HighPerformanceGpuEnvironment.resolve(
                true,
                Renderer.OpenGL.LLVMPIPE,
                OperatingSystem.LINUX,
                List.of(integratedIntel(), discreteNvidia())).isEmpty());
    }

    /// Rejects a hybrid setup whose discrete adapter is not NVIDIA.
    @Test
    void ignoresNonNvidiaDiscreteGraphics() {
        GraphicsCard discreteAmd = GraphicsCard.builder()
                .setName("AMD Graphics")
                .setVendor(HardwareVendor.AMD)
                .setType(GraphicsCard.Type.Discrete)
                .build();

        assertTrue(HighPerformanceGpuEnvironment.resolve(
                true,
                Renderer.DEFAULT,
                OperatingSystem.LINUX,
                List.of(integratedIntel(), discreteAmd)).isEmpty());
    }

    /// Builds the integrated adapter fixture.
    ///
    /// @return Intel integrated adapter
    private static GraphicsCard integratedIntel() {
        return GraphicsCard.builder()
                .setName("Intel Graphics")
                .setVendor(HardwareVendor.INTEL)
                .setType(GraphicsCard.Type.Integrated)
                .build();
    }

    /// Builds the discrete adapter fixture.
    ///
    /// @return NVIDIA discrete adapter
    private static GraphicsCard discreteNvidia() {
        return GraphicsCard.builder()
                .setName("NVIDIA Graphics")
                .setVendor(HardwareVendor.NVIDIA)
                .setType(GraphicsCard.Type.Discrete)
                .build();
    }
}
