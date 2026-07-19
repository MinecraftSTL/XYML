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
package space.minecraftstl.xyml.ui.account;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the illegal offline username confirmation text rules.
@NotNullByDefault
public final class CreateAccountPaneTest {
    /// Verifies that punctuation is displayed as spaces and spacing is ignored when matching.
    @Test
    public void confirmationIgnoresPunctuationReplacementAndWhitespace() {
        String source = "我明知我使用非法用户名，会导致我无法加入大部分服务器，并可能导致游戏崩溃，我确认使用";
        String expected = CreateAccountPane.replacePunctuationWithSpaces(source);

        assertEquals("我明知我使用非法用户名 会导致我无法加入大部分服务器 并可能导致游戏崩溃 我确认使用", expected);
        assertTrue(CreateAccountPane.matchesConfirmation(
                "我明知我使用非法用户名会导致我无法加入大部分服务器\u3000并可能导致游戏崩溃我确认使用",
                expected));
    }

    /// Verifies that changing a confirmation character or retaining punctuation does not pass.
    @Test
    public void confirmationRejectsChangedText() {
        String expected = CreateAccountPane.replacePunctuationWithSpaces(
                "我明知我使用非法用户名，会导致我无法加入大部分服务器，并可能导致游戏崩溃，我确认使用");

        assertFalse(CreateAccountPane.matchesConfirmation(
                "我明知我使用非法用户名会导致我无法加入大部分服务器并可能导致游戏崩溃我确认不用",
                expected));
        assertFalse(CreateAccountPane.matchesConfirmation(
                "我明知我使用非法用户名，会导致我无法加入大部分服务器，并可能导致游戏崩溃，我确认使用",
                expected));
    }
}
