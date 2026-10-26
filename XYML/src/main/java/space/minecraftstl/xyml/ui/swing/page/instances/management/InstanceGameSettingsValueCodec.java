// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Converts persisted instance-setting numbers to and from their editable text representation.
@NotNullByDefault
final class InstanceGameSettingsValueCodec {
    /// Prevents instantiation of this stateless value converter.
    private InstanceGameSettingsValueCodec() {
    }

    /// Parses one required integer within an inclusive range.
    ///
    /// @param rawValue raw editor text
    /// @param fieldName user-facing field name used in validation messages
    /// @param minimum inclusive minimum
    /// @param maximum inclusive maximum
    /// @return parsed integer
    static int parseRequiredInteger(String rawValue, String fieldName, int minimum, int maximum) {
        String value = Objects.requireNonNull(rawValue, "rawValue").trim();
        String displayName = Objects.requireNonNull(fieldName, "fieldName");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        displayName + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(displayName + " must be a whole number", exception);
        }
    }

    /// Parses one finite non-negative decimal dimension.
    ///
    /// @param rawValue raw editor text
    /// @param fieldName user-facing field name used in validation messages
    /// @return parsed dimension
    static double parseRequiredDouble(String rawValue, String fieldName) {
        String value = Objects.requireNonNull(rawValue, "rawValue").trim();
        String displayName = Objects.requireNonNull(fieldName, "fieldName");
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0.0D) {
                throw new IllegalArgumentException(displayName + " must be finite and non-negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(displayName + " must be a number", exception);
        }
    }

    /// Formats one dimension without adding `.0` to integral persisted values.
    ///
    /// @param value finite non-negative dimension
    /// @return lossless editor text
    static String formatWindowDimension(double value) {
        if (value == Math.rint(value) && value <= Long.MAX_VALUE) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /// Parses one optional integer within an inclusive range.
    ///
    /// @param rawValue raw editor text
    /// @param fieldName user-facing field name used in validation messages
    /// @param minimum inclusive minimum
    /// @param maximum inclusive maximum
    /// @return parsed integer, or `null` when blank
    static @Nullable Integer parseOptionalInteger(
            String rawValue,
            String fieldName,
            int minimum,
            int maximum) {
        String value = Objects.requireNonNull(rawValue, "rawValue").trim();
        return value.isEmpty() ? null : parseRequiredInteger(value, fieldName, minimum, maximum);
    }
}
