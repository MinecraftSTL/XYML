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
package space.minecraftstl.xyml.util.versioning;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies chronological decimal ordering across the four XYML release channels.
@NotNullByDefault
final class ReleaseVersionOrderingTest {
    /// Orders one candidate from stable through dev, alpha, beta, and its eventual patch release.
    @Test
    void ordersPromotionLifecycle() {
        assertBefore("1.0.0", "1.0.0.0.0.1");
        assertBefore("1.0.0.0.0.1", "1.0.0.0.1");
        assertBefore("1.0.0.0.1", "1.0.0.1");
        assertBefore("1.0.0.1", "1.0.1");
    }

    /// Keeps an emergency stable patch ahead of an older beta while permitting a later stable landing.
    @Test
    void ordersEmergencyStableAdvancement() {
        assertBefore("1.0.0.1", "1.0.1");
        assertBefore("1.0.1", "1.0.2");
    }

    /// Asserts strict ordering in both comparison directions.
    ///
    /// @param earlier earlier release version
    /// @param later later release version
    private static void assertBefore(String earlier, String later) {
        assertTrue(VersionNumber.compare(earlier, later) < 0);
        assertTrue(VersionNumber.compare(later, earlier) > 0);
    }
}
