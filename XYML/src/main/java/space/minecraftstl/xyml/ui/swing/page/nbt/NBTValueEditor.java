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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.glavo.nbt.NBTElement;
import org.glavo.nbt.tag.ByteTag;
import org.glavo.nbt.tag.DoubleTag;
import org.glavo.nbt.tag.FloatTag;
import org.glavo.nbt.tag.IntTag;
import org.glavo.nbt.tag.LongTag;
import org.glavo.nbt.tag.ShortTag;
import org.glavo.nbt.tag.StringTag;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Applies text input only through concrete scalar setters supplied by HelloNBT.
@NotNullByDefault
final class NBTValueEditor {
    /// Prevents construction of this stateless utility.
    private NBTValueEditor() {
    }

    /// Returns whether the exact element has a supported type-preserving setter.
    ///
    /// @param element concrete HelloNBT element
    /// @return whether scalar text editing is supported
    static boolean isEditable(NBTElement element) {
        NBTElement candidate = Objects.requireNonNull(element, "element");
        return candidate instanceof ByteTag
                || candidate instanceof ShortTag
                || candidate instanceof IntTag
                || candidate instanceof LongTag
                || candidate instanceof FloatTag
                || candidate instanceof DoubleTag
                || candidate instanceof StringTag;
    }

    /// Parses and applies input without converting between NBT types.
    ///
    /// Numeric input is trimmed before parsing. String input is preserved exactly, including leading
    /// whitespace and line breaks.
    ///
    /// @param element concrete HelloNBT scalar
    /// @param text proposed value text
    /// @return success or a validation reason without partial mutation
    static NBTValueEditResult apply(NBTElement element, String text) {
        NBTElement candidate = Objects.requireNonNull(element, "element");
        String input = Objects.requireNonNull(text, "text");
        try {
            if (candidate instanceof ByteTag byteTag) {
                byteTag.set(Byte.parseByte(input.trim()));
            } else if (candidate instanceof ShortTag shortTag) {
                shortTag.set(Short.parseShort(input.trim()));
            } else if (candidate instanceof IntTag intTag) {
                intTag.set(Integer.parseInt(input.trim()));
            } else if (candidate instanceof LongTag longTag) {
                longTag.set(Long.parseLong(input.trim()));
            } else if (candidate instanceof FloatTag floatTag) {
                floatTag.set(parseFiniteFloat(input));
            } else if (candidate instanceof DoubleTag doubleTag) {
                doubleTag.set(parseFiniteDouble(input));
            } else if (candidate instanceof StringTag stringTag) {
                stringTag.set(input);
            } else {
                return NBTValueEditResult.failure("The selected NBT element is read-only");
            }
            return NBTValueEditResult.success();
        } catch (NumberFormatException failure) {
            return NBTValueEditResult.failure(Objects.requireNonNullElse(
                    failure.getMessage(),
                    "The value is outside the selected NBT type's range"));
        }
    }

    /// Parses a finite single-precision value.
    ///
    /// @param text numeric input
    /// @return parsed finite value
    /// @throws NumberFormatException when syntax is invalid or the value is not finite
    private static float parseFiniteFloat(String text) {
        float value = Float.parseFloat(text.trim());
        if (!Float.isFinite(value)) {
            throw new NumberFormatException("Float values must be finite");
        }
        return value;
    }

    /// Parses a finite double-precision value.
    ///
    /// @param text numeric input
    /// @return parsed finite value
    /// @throws NumberFormatException when syntax is invalid or the value is not finite
    private static double parseFiniteDouble(String text) {
        double value = Double.parseDouble(text.trim());
        if (!Double.isFinite(value)) {
            throw new NumberFormatException("Double values must be finite");
        }
        return value;
    }
}
