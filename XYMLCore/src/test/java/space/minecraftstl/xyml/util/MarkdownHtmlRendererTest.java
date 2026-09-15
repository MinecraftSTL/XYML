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
package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies safe provider-Markdown rendering for the Swing changelog viewer.
@NotNullByDefault
final class MarkdownHtmlRendererTest {
    /// Escapes raw HTML, drops unsafe link targets, and emits each web link exactly once.
    @Test
    void rendersSafeLinksWithoutReprocessingGeneratedMarkup() {
        String html = Objects.requireNonNull(MarkdownHtmlRenderer.render(
                "<script>alert(1)</script> [bad](javascript:alert(1)) "
                        + "[good](https://example.com/version_a) https://example.org/plain_b",
                true));

        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("javascript:"));
        assertFalse(html.contains("<a href=\"<a"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("<a href=\"https://example.com/version_a\">good</a>"));
        assertTrue(html.contains("<a href=\"https://example.org/plain_b\">https://example.org/plain_b</a>"));
    }

    /// Preserves provider tables, paragraph spacing, and literal indented code.
    @Test
    void rendersCommonProviderBlocks() {
        String html = Objects.requireNonNull(MarkdownHtmlRenderer.render(
                "First\n\nSecond\n\n| Name | Value |\n| --- | --- |\n| A | B |\n\n    <literal>",
                true));

        assertTrue(html.contains("<p>First</p><p>Second</p>"));
        assertTrue(html.contains("<table><thead><tr><th>Name</th><th>Value</th>"));
        assertTrue(html.contains("<pre><code>&lt;literal&gt;"));
    }
}
