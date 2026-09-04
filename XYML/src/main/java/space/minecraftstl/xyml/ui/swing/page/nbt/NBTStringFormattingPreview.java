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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.io.Serial;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/// Read-only Swing renderer for Minecraft legacy section-sign formatting.
///
/// Parsing remains entirely in XYML presentation code. The backing NBT string is never rewritten,
/// and unknown codes remain visible so malformed user data is not silently hidden.
@NotNullByDefault
final class NBTStringFormattingPreview extends JTextPane {
    /// Serialization identifier for the Swing component superclass contract.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Canonical Minecraft legacy colors keyed by lowercase formatting code.
    private static final @Unmodifiable Map<Character, Color> LEGACY_COLORS = Map.ofEntries(
            Map.entry('0', new Color(0x000000)),
            Map.entry('1', new Color(0x0000AA)),
            Map.entry('2', new Color(0x00AA00)),
            Map.entry('3', new Color(0x00AAAA)),
            Map.entry('4', new Color(0xAA0000)),
            Map.entry('5', new Color(0xAA00AA)),
            Map.entry('6', new Color(0xFFAA00)),
            Map.entry('7', new Color(0xAAAAAA)),
            Map.entry('8', new Color(0x555555)),
            Map.entry('9', new Color(0x5555FF)),
            Map.entry('a', new Color(0x55FF55)),
            Map.entry('b', new Color(0x55FFFF)),
            Map.entry('c', new Color(0xFF5555)),
            Map.entry('d', new Color(0xFF55FF)),
            Map.entry('e', new Color(0xFFFF55)),
            Map.entry('f', new Color(0xFFFFFF)));

    /// Creates a non-editable preview which follows the active Swing surface colors.
    NBTStringFormattingPreview() {
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
    }

    /// Renders a string after interpreting legacy colors, style codes, reset, and RGB sequences.
    ///
    /// @param source exact NBT string draft
    void render(String source) {
        String input = Objects.requireNonNull(source, "source");
        StyledDocument document = getStyledDocument();
        try {
            document.remove(0, document.getLength());
            MutableAttributeSet attributes = resetAttributes();
            boolean obfuscated = false;
            StringBuilder plain = new StringBuilder();
            for (int index = 0; index < input.length(); index++) {
                char value = input.charAt(index);
                if (value != '\u00a7' || index + 1 >= input.length()) {
                    appendVisible(plain, value, obfuscated);
                    continue;
                }
                char code = Character.toLowerCase(input.charAt(index + 1));
                @Nullable Color legacyColor = LEGACY_COLORS.get(code);
                @Nullable Color rgbColor = code == 'x' ? rgbColor(input, index) : null;
                if (legacyColor == null && rgbColor == null && "klmnor".indexOf(code) < 0) {
                    appendVisible(plain, value, obfuscated);
                    continue;
                }
                flush(document, plain, attributes);
                if (legacyColor != null || rgbColor != null) {
                    attributes = resetAttributes();
                    StyleConstants.setForeground(attributes, legacyColor == null ? rgbColor : legacyColor);
                    obfuscated = false;
                    index += rgbColor == null ? 1 : 13;
                    continue;
                }
                index++;
                switch (code) {
                    case 'k' -> obfuscated = true;
                    case 'l' -> StyleConstants.setBold(attributes, true);
                    case 'm' -> StyleConstants.setStrikeThrough(attributes, true);
                    case 'n' -> StyleConstants.setUnderline(attributes, true);
                    case 'o' -> StyleConstants.setItalic(attributes, true);
                    case 'r' -> {
                        attributes = resetAttributes();
                        obfuscated = false;
                    }
                    default -> throw new AssertionError("Unhandled formatting code: " + code);
                }
            }
            flush(document, plain, attributes);
            setCaretPosition(0);
        } catch (BadLocationException failure) {
            throw new IllegalStateException("Could not update the formatting preview", failure);
        }
    }

    /// Creates formatting attributes reset to the current component foreground.
    ///
    /// @return new mutable attributes
    private MutableAttributeSet resetAttributes() {
        MutableAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, getForeground());
        return attributes;
    }

    /// Appends one source character, masking visible obfuscated characters deterministically.
    ///
    /// @param target pending visible segment
    /// @param value source character
    /// @param obfuscated whether the obfuscated effect is active
    private static void appendVisible(StringBuilder target, char value, boolean obfuscated) {
        target.append(obfuscated && !Character.isWhitespace(value) ? '\u25a0' : value);
    }

    /// Flushes one same-style segment into the preview document.
    ///
    /// @param document destination document
    /// @param text pending visible characters
    /// @param attributes active formatting attributes
    /// @throws BadLocationException if Swing rejects insertion
    private static void flush(
            StyledDocument document,
            StringBuilder text,
            MutableAttributeSet attributes) throws BadLocationException {
        if (text.length() == 0) {
            return;
        }
        document.insertString(document.getLength(), text.toString(), attributes);
        text.setLength(0);
    }

    /// Parses a complete modern `section-x` RGB sequence beginning at one section sign.
    ///
    /// @param source complete source string
    /// @param offset index of the section sign before `x`
    /// @return decoded color, or `null` when the sequence is incomplete or malformed
    private static @Nullable Color rgbColor(String source, int offset) {
        if (offset + 13 >= source.length()) {
            return null;
        }
        StringBuilder digits = new StringBuilder(6);
        for (int pair = 0; pair < 6; pair++) {
            int marker = offset + 2 + pair * 2;
            if (source.charAt(marker) != '\u00a7') {
                return null;
            }
            char digit = source.charAt(marker + 1);
            if (Character.digit(digit, 16) < 0) {
                return null;
            }
            digits.append(digit);
        }
        return new Color(Integer.parseInt(digits.toString().toLowerCase(Locale.ROOT), 16));
    }
}
