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
package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;

/// Validates the RFC Bearer `b64token` credential grammar without retaining credential text.
@NotNullByDefault
final class BearerCredentialSyntax {
    /// Prevents construction of this stateless syntax helper.
    private BearerCredentialSyntax() {
    }

    /// Checks for one or more token68 data characters followed only by optional equals padding.
    ///
    /// @param token token text
    /// @return whether the complete token satisfies the Bearer credential grammar
    static boolean isValid(String token) {
        boolean paddingStarted = false;
        int dataCharacters = 0;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character == '=') {
                paddingStarted = true;
            } else if (paddingStarted || !isDataCharacter(character)) {
                return false;
            } else {
                dataCharacters++;
            }
        }
        return dataCharacters > 0;
    }

    /// Returns whether one character belongs to the non-padding portion of the Bearer grammar.
    ///
    /// @param character candidate credential character
    /// @return whether the character is legal before optional trailing padding
    private static boolean isDataCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '-'
                || character == '.'
                || character == '_'
                || character == '~'
                || character == '+'
                || character == '/';
    }
}
