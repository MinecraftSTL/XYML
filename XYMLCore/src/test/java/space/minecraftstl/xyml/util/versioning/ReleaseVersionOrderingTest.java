// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
