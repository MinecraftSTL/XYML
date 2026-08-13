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
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Converts provider Markdown into a small, safe HTML subset suitable for Swing rendering.
///
/// The renderer deliberately escapes source HTML and rejects non-web link schemes. Provider
/// changelogs are untrusted network input and must never be handed to a browser-like component as
/// raw markup.
@NotNullByDefault
public final class MarkdownHtmlRenderer {
    /// ATX heading syntax.
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /// Unordered list-item syntax.
    private static final Pattern UNORDERED_ITEM = Pattern.compile("^\\s*[-*+]\\s+(.+)$");

    /// Ordered list-item syntax.
    private static final Pattern ORDERED_ITEM = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$");

    /// Inline Markdown link syntax with an optional title.
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)\\s]+)(?:\\s+\\\"[^\\\"]*\\\")?\\)");

    /// Inline code-span syntax.
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    /// Plain HTTP and HTTPS URLs that may be turned into links.
    private static final Pattern AUTO_LINK = Pattern.compile("(?i)(https?://[^\\s<]+)");

    /// Prevents utility-class construction.
    private MarkdownHtmlRenderer() {
    }

    /// Renders Markdown as escaped HTML with conservative block and inline support.
    ///
    /// @param markdown source Markdown, or `null` when no changelog was supplied
    /// @param rawIndentedBlocks whether indented blocks should be represented as literal code
    /// @return safe HTML fragment, or `null` for a null source
    public static @Nullable String render(@Nullable String markdown, boolean rawIndentedBlocks) {
        if (markdown == null) {
            return null;
        }
        if (markdown.isBlank()) {
            return "";
        }

        List<String> lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        StringBuilder body = new StringBuilder(markdown.length() + 64);
        boolean inFence = false;
        StringBuilder code = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
                if (inFence) {
                    appendCode(body, code.toString());
                    code.setLength(0);
                    inFence = false;
                } else {
                    flushParagraph(body, paragraph);
                    inFence = true;
                }
                index++;
                continue;
            }
            if (inFence) {
                code.append(line).append('\n');
                index++;
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(body, paragraph);
                int level = Math.min(6, heading.group(1).length());
                body.append("<h").append(level).append('>')
                        .append(renderInline(heading.group(2)))
                        .append("</h").append(level).append('>');
                index++;
                continue;
            }
            if (isTableHeader(lines, index)) {
                flushParagraph(body, paragraph);
                index = appendTable(body, lines, index);
                continue;
            }
            Matcher unordered = UNORDERED_ITEM.matcher(line);
            Matcher ordered = ORDERED_ITEM.matcher(line);
            if (unordered.matches() || ordered.matches()) {
                flushParagraph(body, paragraph);
                boolean numbered = ordered.matches();
                body.append(numbered ? "<ol>" : "<ul>");
                while (index < lines.size()) {
                    Matcher item = (numbered ? ORDERED_ITEM : UNORDERED_ITEM).matcher(lines.get(index));
                    if (!item.matches()) {
                        break;
                    }
                    body.append("<li>").append(renderInline(item.group(1))).append("</li>");
                    index++;
                }
                body.append(numbered ? "</ol>" : "</ul>");
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(body, paragraph);
            } else if (rawIndentedBlocks && line.startsWith("    ")) {
                flushParagraph(body, paragraph);
                int start = index;
                StringBuilder indented = new StringBuilder();
                while (index < lines.size() && (lines.get(index).startsWith("    ") || lines.get(index).isBlank())) {
                    String indentedLine = lines.get(index);
                    indented.append(indentedLine.length() >= 4 ? indentedLine.substring(4) : "").append('\n');
                    index++;
                }
                if (index == start) {
                    index++;
                } else {
                    appendCode(body, indented.toString());
                }
                continue;
            } else if (line.startsWith(">")) {
                flushParagraph(body, paragraph);
                body.append("<blockquote>").append(renderInline(line.substring(1).strip())).append("</blockquote>");
            } else {
                paragraph.add(line);
            }
            index++;
        }
        if (inFence) {
            appendCode(body, code.toString());
        }
        flushParagraph(body, paragraph);
        return "<html><head><style>body{font-family:sans-serif;}p{margin:8px 0;}li{margin:3px 0;}"
                + "blockquote{margin:8px 0;padding-left:10px;border-left:3px solid #999;}"
                + "pre{white-space:pre-wrap;}table{border-collapse:collapse;}th,td{border:1px solid #999;padding:4px;}"
                + "</style></head><body>" + body + "</body></html>";
    }

    /// Appends and clears one accumulated paragraph.
    ///
    /// @param body destination HTML
    /// @param paragraph source lines
    private static void flushParagraph(StringBuilder body, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        body.append("<p>");
        for (int index = 0; index < paragraph.size(); index++) {
            if (index > 0) {
                body.append("<br>");
            }
            body.append(renderInline(paragraph.get(index)));
        }
        body.append("</p>");
        paragraph.clear();
    }

    /// Appends one escaped block-code element.
    ///
    /// @param body destination HTML
    /// @param code literal code text
    private static void appendCode(StringBuilder body, String code) {
        body.append("<pre><code>").append(escapeHtml(code)).append("</code></pre>");
    }

    /// Tests whether the indexed line starts a Markdown table.
    ///
    /// @param lines source lines
    /// @param index candidate header index
    /// @return whether a valid delimiter row follows the candidate
    private static boolean isTableHeader(List<String> lines, int index) {
        return index + 1 < lines.size()
                && lines.get(index).contains("|")
                && lines.get(index + 1).matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*");
    }

    /// Appends one table beginning at the indexed header row.
    ///
    /// @param body destination HTML
    /// @param lines source lines
    /// @param index header-row index
    /// @return index of the first line after the table
    private static int appendTable(StringBuilder body, List<String> lines, int index) {
        body.append("<table><thead><tr>");
        appendCells(body, lines.get(index), "th");
        body.append("</tr></thead><tbody>");
        index += 2;
        while (index < lines.size() && lines.get(index).contains("|") && !lines.get(index).isBlank()) {
            body.append("<tr>");
            appendCells(body, lines.get(index), "td");
            body.append("</tr>");
            index++;
        }
        body.append("</tbody></table>");
        return index;
    }

    /// Appends escaped cells from one pipe-delimited row.
    ///
    /// @param body destination HTML
    /// @param line source row
    /// @param tag cell tag name
    private static void appendCells(StringBuilder body, String line, String tag) {
        String value = line.strip();
        if (value.startsWith("|")) {
            value = value.substring(1);
        }
        if (value.endsWith("|")) {
            value = value.substring(0, value.length() - 1);
        }
        for (String cell : value.split("\\|", -1)) {
            body.append('<').append(tag).append('>').append(renderInline(cell.strip()))
                    .append("</").append(tag).append('>');
        }
    }

    /// Renders inline syntax while isolating generated markup from later substitutions.
    ///
    /// @param source source text
    /// @return safe inline HTML
    private static String renderInline(String source) {
        List<String> tokens = new ArrayList<>();
        String tokenPrefix = uniqueTokenPrefix(source);
        String tokenized = replaceCode(source, tokenPrefix, tokens);
        tokenized = replaceLinks(tokenized, tokenPrefix, tokens);
        tokenized = replaceAutoLinks(tokenized, tokenPrefix, tokens);

        String escaped = escapeHtml(tokenized);
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*|__([^_]+)__", "<strong>$1$2</strong>");
        escaped = escaped.replaceAll("~~([^~]+)~~", "<del>$1</del>");
        escaped = escaped.replaceAll("(?<!\\w)\\*([^*]+)\\*|(?<!\\w)_([^_]+)_", "<em>$1$2</em>");
        escaped = escaped.replaceAll("==([^=]+)==", "<ins>$1</ins>");
        for (int index = 0; index < tokens.size(); index++) {
            escaped = escaped.replace(token(tokenPrefix, index), tokens.get(index));
        }
        return escaped;
    }

    /// Replaces code spans with opaque tokens.
    ///
    /// @param source source text
    /// @param tokenPrefix collision-free token prefix
    /// @param tokens generated HTML token values
    /// @return tokenized text
    private static String replaceCode(String source, String tokenPrefix, List<String> tokens) {
        Matcher matcher = CODE.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = addToken(tokenPrefix, tokens, "<code>" + escapeHtml(matcher.group(1)) + "</code>");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /// Replaces explicit Markdown links with opaque safe-HTML tokens.
    ///
    /// @param source source text
    /// @param tokenPrefix collision-free token prefix
    /// @param tokens generated HTML token values
    /// @return tokenized text
    private static String replaceLinks(String source, String tokenPrefix, List<String> tokens) {
        Matcher matcher = LINK.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            @Nullable String safeUrl = safeUrl(matcher.group(2));
            String html = safeUrl == null
                    ? escapeHtml(matcher.group(1))
                    : "<a href=\"" + escapeHtml(safeUrl) + "\">" + escapeHtml(matcher.group(1)) + "</a>";
            String replacement = addToken(tokenPrefix, tokens, html);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /// Replaces remaining plain web URLs with opaque safe-HTML tokens.
    ///
    /// @param source source text
    /// @param tokenPrefix collision-free token prefix
    /// @param tokens generated HTML token values
    /// @return tokenized text
    private static String replaceAutoLinks(String source, String tokenPrefix, List<String> tokens) {
        Matcher matcher = AUTO_LINK.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1);
            String html = "<a href=\"" + escapeHtml(url) + "\">" + escapeHtml(url) + "</a>";
            matcher.appendReplacement(result, Matcher.quoteReplacement(addToken(tokenPrefix, tokens, html)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /// Selects a token prefix that cannot collide with provider input.
    ///
    /// @param source provider input
    /// @return collision-free alphanumeric prefix
    private static String uniqueTokenPrefix(String source) {
        String prefix = "XYMLMARKDOWNTOKEN";
        while (source.contains(prefix)) {
            prefix += "X";
        }
        return prefix;
    }

    /// Stores generated HTML and returns its opaque source token.
    ///
    /// @param tokenPrefix collision-free token prefix
    /// @param tokens generated HTML token values
    /// @param html safe generated HTML
    /// @return opaque token
    private static String addToken(String tokenPrefix, List<String> tokens, String html) {
        int index = tokens.size();
        tokens.add(html);
        return token(tokenPrefix, index);
    }

    /// Builds one opaque token value.
    ///
    /// @param tokenPrefix collision-free token prefix
    /// @param index token index
    /// @return opaque token
    private static String token(String tokenPrefix, int index) {
        return tokenPrefix + index + "END";
    }

    /// Accepts only absolute HTTP and HTTPS links.
    ///
    /// @param value candidate URI text
    /// @return original text when safe, or null otherwise
    private static @Nullable String safeUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            String normalized = scheme.toLowerCase(Locale.ROOT);
            return normalized.equals("http") || normalized.equals("https") ? value : null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    /// Escapes text for HTML element content and quoted attributes.
    ///
    /// @param value untrusted text
    /// @return escaped text
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
