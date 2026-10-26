// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
