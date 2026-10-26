// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
