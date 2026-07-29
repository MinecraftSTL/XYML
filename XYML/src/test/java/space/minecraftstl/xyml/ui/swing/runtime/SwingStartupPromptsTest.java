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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.startup.StartupPlatformPrompt;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies pure policy mappings owned by the Swing startup-prompt adapter.
@NotNullByDefault
class SwingStartupPromptsTest {
    /// Keeps x86 systems silent on every operating system.
    @Test
    void x86DoesNotRequirePlatformPrompt() {
        assertEquals(
                StartupPlatformPrompt.NONE,
                SwingStartupPrompts.classifyPlatform(
                        OperatingSystem.WINDOWS,
                        Architecture.X86_64));
    }

    /// Marks Apple Silicon support without showing an unnecessary warning.
    @Test
    void appleSiliconIsMarkedSilently() {
        assertEquals(
                StartupPlatformPrompt.MARK_SUPPORTED,
                SwingStartupPrompts.classifyPlatform(
                        OperatingSystem.MACOS,
                        Architecture.ARM64));
    }

    /// Preserves the dedicated Windows-on-Arm notice.
    @Test
    void windowsArmUsesDedicatedPrompt() {
        assertEquals(
                StartupPlatformPrompt.WINDOWS_ARM64,
                SwingStartupPrompts.classifyPlatform(
                        OperatingSystem.WINDOWS,
                        Architecture.ARM64));
    }

    /// Preserves the dedicated LoongArch family notice.
    @Test
    void linuxLoongArchUsesDedicatedPrompt() {
        assertEquals(
                StartupPlatformPrompt.LOONGARCH,
                SwingStartupPrompts.classifyPlatform(
                        OperatingSystem.LINUX,
                        Architecture.LOONGARCH64));
    }

    /// Maps other non-x86 pairs to the general compatibility warning.
    @Test
    void otherArchitectureUsesGeneralPrompt() {
        assertEquals(
                StartupPlatformPrompt.OTHER_UNSUPPORTED,
                SwingStartupPrompts.classifyPlatform(
                        OperatingSystem.FREEBSD,
                        Architecture.ARM64));
    }

    /// Rejects missing, negative, and non-numeric version markers.
    @Test
    void nonNegativeTipRejectsInvalidMarkers() {
        assertTrue(SwingStartupPrompts.nonNegativeTip(null).isEmpty());
        assertTrue(SwingStartupPrompts.nonNegativeTip(-1).isEmpty());
        assertTrue(SwingStartupPrompts.nonNegativeTip("17").isEmpty());
        assertEquals(17, SwingStartupPrompts.nonNegativeTip(17L).orElseThrow());
    }

    /// Accepts only positive year markers required by the immutable snapshot.
    @Test
    void positiveYearTipRejectsNonPositiveMarkers() {
        assertTrue(SwingStartupPrompts.positiveYearTip(null).isEmpty());
        assertTrue(SwingStartupPrompts.positiveYearTip(0).isEmpty());
        assertTrue(SwingStartupPrompts.positiveYearTip(-2026).isEmpty());
        assertEquals(2026, SwingStartupPrompts.positiveYearTip(2026).orElseThrow());
    }
}
