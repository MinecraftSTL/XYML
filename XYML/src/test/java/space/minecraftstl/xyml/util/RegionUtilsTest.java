// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies exact time zone lookup and preservation of candidate regions.
@NotNullByDefault
final class RegionUtilsTest {
    /// Reads candidates in source order and exposes an unmodifiable list.
    @Test
    void loadsUnmodifiableCandidateMapping() throws IOException {
        assertEquals(List.of("CN"), RegionUtils.findRegionsByTimeZone("Asia/Shanghai"));
        @Unmodifiable List<String> candidates = RegionUtils.findRegionsByTimeZone("Asia/Tokyo");
        assertEquals(List.of("JP", "AU"), candidates);
        assertThrows(UnsupportedOperationException.class, () -> candidates.add("US"));
    }

    /// Does not match missing identifiers, partial names, or region fields.
    @Test
    void matchesCompleteTimeZoneField() throws IOException {
        for (String zoneId : List.of("Etc/UTC", "Asia/Shang", "CN", "")) {
            assertEquals(List.of(), RegionUtils.findRegionsByTimeZone(zoneId));
        }
    }

    /// Finds system time zone aliases without dropping the target's candidate countries.
    @Test
    void loadsCompatibilityAliases() throws IOException {
        assertEquals(List.of("BE", "LU", "NL"), RegionUtils.findRegionsByTimeZone("Europe/Amsterdam"));
        assertEquals(List.of("DE", "DK", "NO", "SE", "SJ"), RegionUtils.findRegionsByTimeZone("Europe/Stockholm"));
    }

    /// Resolves a unique time-zone region without consulting native geographical data.
    @Test
    void uniqueRegionDoesNotProbeWindows() {
        assertEquals("CN", RegionUtils.selectRegion(List.of("CN"), "US", () -> {
            throw new AssertionError("A unique mapping must not need native region lookup");
        }));
    }

    /// Uses the matching locale to disambiguate a shared time zone before native lookup.
    @Test
    void sharedRegionPrefersMatchingLocale() {
        assertEquals("DK", RegionUtils.selectRegion(List.of("DE", "DK"), "DK", () -> {
            throw new AssertionError("A matching locale must take precedence over native region lookup");
        }));
    }

    /// Uses a matching Windows region when the system locale contradicts the time-zone candidates.
    @Test
    void sharedRegionFallsBackToMatchingWindowsRegion() {
        assertEquals("DK", RegionUtils.selectRegion(List.of("DE", "DK"), "US", () -> "DK"));
    }

    /// Retains source ordering when neither locale nor native geography matches a shared time zone.
    @Test
    void sharedRegionFallsBackToFirstCandidate() {
        assertEquals("DE", RegionUtils.selectRegion(List.of("DE", "DK"), "US", () -> "GB"));
    }

    /// Allows a valid locale region when a time-zone mapping is unavailable.
    @Test
    void unmappedTimeZoneUsesLocaleWithoutOffsetRestriction() {
        assertEquals("CN", RegionUtils.selectRegion(List.of(), "CN", () -> {
            throw new AssertionError("A valid locale is sufficient without a time-zone mapping");
        }));
    }

    /// Rejects non-country locale regions while retaining the native fallback.
    @Test
    void unmappedTimeZoneFallsBackToWindowsRegion() {
        assertEquals("GB", RegionUtils.selectRegion(List.of(), "419", () -> "GB"));
    }

    /// Returns null when no mapping, valid locale, or native geography is available.
    @Test
    void absentRegionDataReturnsNull() {
        assertNull(RegionUtils.selectRegion(List.of(), null, () -> null));
        assertNull(RegionUtils.selectRegion(List.of(), "", () -> null));
    }

    /// Includes the non-UTC+8 mainland time zone in the bundled mapping.
    ///
    /// @throws IOException if the bundled mapping cannot be read
    @Test
    void mapsUrumqiToChina() throws IOException {
        assertEquals(List.of("CN"), RegionUtils.findRegionsByTimeZone("Asia/Urumqi"));
    }
}
