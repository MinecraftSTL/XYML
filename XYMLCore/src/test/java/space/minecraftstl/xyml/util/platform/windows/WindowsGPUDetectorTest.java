// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.platform.windows;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies registry decoding and adapter classification without accessing the host registry.
@NotNullByDefault
final class WindowsGPUDetectorTest {
    /// Missing, empty, odd-length and NUL-terminated registry values decode predictably.
    @Test
    void decodesRegistryValues() {
        assertNull(WindowsGPUDetector.regValueToString(null));
        assertEquals("", WindowsGPUDetector.regValueToString(new byte[0]));
        assertEquals("", WindowsGPUDetector.regValueToString(new byte[] {65}));
        assertEquals("A", WindowsGPUDetector.regValueToString(new byte[] {65, 0, 66}));
        assertEquals("GPU", WindowsGPUDetector.regValueToString("GPU\0".getBytes(StandardCharsets.UTF_16LE)));
        assertEquals("A B", WindowsGPUDetector.regValueToString(new String[] {"A", "B"}));
        assertEquals("42", WindowsGPUDetector.regValueToString(42));
    }

    /// Empty adapter strings fall back to driver descriptions and known virtual IDs are case-insensitive.
    @Test
    void fallsBackToDriverNameAndMarksKnownVirtualAdapter() {
        var cards = WindowsGPUDetector.detectByRegistry(new Registry(Map.of(
                "HardwareInformation.AdapterString", "  ",
                "DriverDesc", "Fallback GPU",
                "MatchingDeviceId", "root\\gamevieweridddriver")));
        assertEquals(1, cards.size());
        assertEquals("Fallback", cards.get(0).getName());
        assertTrue(cards.get(0).isVirtualDisplayAdapter());
    }

    /// An explicit name takes priority and an unknown classification remains visible.
    @Test
    void preservesAdapterNameAndUnknownClassification() {
        var card = WindowsGPUDetector.detectByRegistry(new Registry(Map.of(
                "HardwareInformation.AdapterString", "Preferred GPU",
                "DriverDesc", "Fallback GPU"))).get(0);
        assertEquals("Preferred", card.getName());
        assertFalse(card.isVirtualDisplayAdapter());
        var unknown = WindowsGPUDetector.detectByRegistry(new Registry(Map.of())).get(0);
        assertEquals("Unknown", unknown.getName());
        assertFalse(unknown.isVirtualDisplayAdapter());
    }

    /// Read-only registry fixture exposing one display-adapter subkey.
    @NotNullByDefault
    private static final class Registry extends WinReg {
        /// Immutable values keyed by the queried registry value name.
        private final @Unmodifiable Map<String, Object> values;

        /// Copies registry values owned by this fixture.
        ///
        /// @param values synthetic adapter values
        private Registry(Map<String, Object> values) {
            this.values = Map.copyOf(values);
        }

        /// Reports the synthetic adapter key as present.
        @Override
        public boolean exists(HKEY root, String key) {
            return true;
        }

        /// Returns the selected value, or null when absent.
        @Override
        public @Nullable Object queryValue(HKEY root, String key, String valueName) {
            return values.get(valueName);
        }

        /// Exposes one stable adapter key.
        @Override
        public @Unmodifiable List<String> querySubKeyNames(HKEY root, String key) {
            return List.of("0000");
        }

        /// Rejects writes to this read-only fixture.
        @Override
        public boolean setValue(HKEY root, String key, String valueName, String value) {
            throw new UnsupportedOperationException("Read-only registry fixture");
        }

        /// Rejects deletion from this read-only fixture.
        @Override
        public boolean deleteValue(HKEY root, String key, String valueName) {
            throw new UnsupportedOperationException("Read-only registry fixture");
        }
    }
}
