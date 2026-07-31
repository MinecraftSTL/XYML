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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// Parses solid colors and JavaFX-compatible serialized gradients without retaining JavaFX at runtime.
@NotNullByDefault
final class WindowBackgroundPaintParser {
    /// Maximum accepted persisted paint expression length.
    private static final int MAXIMUM_EXPRESSION_LENGTH = 4_096;

    /// Utility class constructor.
    private WindowBackgroundPaintParser() {
    }

    /// Parses one complete background paint expression.
    ///
    /// @param expression persisted solid color or gradient expression
    /// @return immutable renderer paint
    /// @throws IOException when the expression is unsupported or malformed
    static WindowBackgroundPaint parse(String expression) throws IOException {
        String source = validateSource(expression);
        try {
            String lower = source.toLowerCase(Locale.ROOT);
            if (lower.startsWith("linear-gradient(")) {
                return parseLinear(unwrapFunction(source, "linear-gradient"));
            }
            if (lower.startsWith("radial-gradient(")) {
                return parseRadial(unwrapFunction(source, "radial-gradient"));
            }
            return WindowBackgroundPaint.solid(parseColorValue(source));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Unsupported background paint: " + expression, failure);
        }
    }

    /// Parses only a solid color expression.
    ///
    /// @param expression persisted solid color expression
    /// @return parsed AWT color
    /// @throws IOException when the expression is a gradient or malformed color
    static Color parseColor(String expression) throws IOException {
        String source = validateSource(expression);
        try {
            return parseColorValue(source);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Unsupported background paint: " + expression, failure);
        }
    }

    /// Validates shared source constraints before parsing.
    ///
    /// @param expression caller-provided expression
    /// @return trimmed nonempty source
    /// @throws IOException when the source is empty or unreasonably long
    private static String validateSource(String expression) throws IOException {
        String source = Objects.requireNonNull(expression, "expression").trim();
        if (source.isEmpty()) {
            throw new IOException("Background paint is blank");
        }
        if (source.length() > MAXIMUM_EXPRESSION_LENGTH) {
            throw new IOException("Background paint exceeds the length limit");
        }
        return source;
    }

    /// Removes one case-insensitive function wrapper.
    ///
    /// @param source complete function expression
    /// @param function expected function name
    /// @return inner function text
    private static String unwrapFunction(String source, String function) {
        String prefix = function + "(";
        if (!source.regionMatches(true, 0, prefix, 0, prefix.length()) || !source.endsWith(")")) {
            throw new IllegalArgumentException("Malformed " + function + " expression");
        }
        return source.substring(prefix.length(), source.length() - 1);
    }

    /// Parses a JavaFX linear-gradient expression body.
    ///
    /// @param body comma-separated gradient body
    /// @return immutable linear gradient
    private static WindowBackgroundPaint.Linear parseLinear(String body) {
        @Unmodifiable List<String> segments = splitTopLevel(body, ',');
        int index = 0;
        double startX = 0.5;
        double startY = 0.0;
        double endX = 0.5;
        double endY = 1.0;
        boolean proportional = true;

        @Unmodifiable List<String> direction = splitWhitespace(segments.get(index));
        if (equalsToken(direction.get(0), "from")) {
            if (direction.size() != 6 || !equalsToken(direction.get(3), "to")) {
                throw new IllegalArgumentException("Linear gradient requires from x y to x y");
            }
            ParsedCoordinate parsedStartX = parseCoordinate(direction.get(1));
            ParsedCoordinate parsedStartY = parseCoordinate(direction.get(2));
            ParsedCoordinate parsedEndX = parseCoordinate(direction.get(4));
            ParsedCoordinate parsedEndY = parseCoordinate(direction.get(5));
            proportional = requireCommonMode(List.of(parsedStartX, parsedStartY, parsedEndX, parsedEndY));
            startX = parsedStartX.value();
            startY = parsedStartY.value();
            endX = parsedEndX.value();
            endY = parsedEndY.value();
            index++;
        } else if (equalsToken(direction.get(0), "to")) {
            if (direction.size() < 2 || direction.size() > 3) {
                throw new IllegalArgumentException("Linear gradient direction requires one side or one corner");
            }
            startX = 0.5;
            startY = 0.5;
            endX = 0.5;
            endY = 0.5;
            boolean horizontal = false;
            boolean vertical = false;
            for (int tokenIndex = 1; tokenIndex < direction.size(); tokenIndex++) {
                switch (direction.get(tokenIndex).toLowerCase(Locale.ROOT)) {
                    case "left" -> {
                        if (horizontal) {
                            throw new IllegalArgumentException("Linear direction repeats a horizontal side");
                        }
                        startX = 1.0;
                        endX = 0.0;
                        horizontal = true;
                    }
                    case "right" -> {
                        if (horizontal) {
                            throw new IllegalArgumentException("Linear direction repeats a horizontal side");
                        }
                        startX = 0.0;
                        endX = 1.0;
                        horizontal = true;
                    }
                    case "top" -> {
                        if (vertical) {
                            throw new IllegalArgumentException("Linear direction repeats a vertical side");
                        }
                        startY = 1.0;
                        endY = 0.0;
                        vertical = true;
                    }
                    case "bottom" -> {
                        if (vertical) {
                            throw new IllegalArgumentException("Linear direction repeats a vertical side");
                        }
                        startY = 0.0;
                        endY = 1.0;
                        vertical = true;
                    }
                    default -> throw new IllegalArgumentException("Unknown linear gradient side");
                }
            }
            index++;
        }

        CycleParse cycle = parseCycleAt(segments, index);
        index = cycle.nextIndex();
        double length = proportional ? 0.0 : Math.hypot(endX - startX, endY - startY);
        @Unmodifiable List<WindowBackgroundPaint.GradientStop> stops =
                parseStops(segments.subList(index, segments.size()), proportional, length);
        return new WindowBackgroundPaint.Linear(
                startX,
                startY,
                endX,
                endY,
                proportional,
                cycle.cycle(),
                stops);
    }

    /// Parses a JavaFX radial-gradient expression body.
    ///
    /// @param body comma-separated gradient body
    /// @return immutable radial gradient
    private static WindowBackgroundPaint.Radial parseRadial(String body) {
        @Unmodifiable List<String> segments = splitTopLevel(body, ',');
        int index = 0;
        double focusAngle = 0.0;
        double focusDistance = 0.0;
        @Nullable ParsedCoordinate parsedCenterX = null;
        @Nullable ParsedCoordinate parsedCenterY = null;

        @Unmodifiable List<String> tokens = splitWhitespace(segments.get(index));
        if (equalsToken(tokens.get(0), "focus-angle")) {
            requireTokenCount(tokens, 2, "focus-angle");
            focusAngle = parseAngle(tokens.get(1));
            index++;
        }

        tokens = splitWhitespace(requireSegment(segments, index, "focus-distance, center, or radius"));
        if (equalsToken(tokens.get(0), "focus-distance")) {
            requireTokenCount(tokens, 2, "focus-distance");
            focusDistance = parsePercentage(tokens.get(1));
            index++;
        }

        tokens = splitWhitespace(requireSegment(segments, index, "center or radius"));
        if (equalsToken(tokens.get(0), "center")) {
            requireTokenCount(tokens, 3, "center");
            parsedCenterX = parseCoordinate(tokens.get(1));
            parsedCenterY = parseCoordinate(tokens.get(2));
            requireCommonMode(List.of(parsedCenterX, parsedCenterY));
            index++;
        }

        tokens = splitWhitespace(requireSegment(segments, index, "radius"));
        if (!equalsToken(tokens.get(0), "radius")) {
            throw new IllegalArgumentException("Radial gradient radius is required");
        }
        requireTokenCount(tokens, 2, "radius");
        ParsedCoordinate parsedRadius = parseCoordinate(tokens.get(1));
        if (parsedRadius.value() <= 0.0) {
            throw new IllegalArgumentException("Radial gradient radius must be positive");
        }
        index++;

        boolean proportional = parsedRadius.proportional();
        double centerX = 0.0;
        double centerY = 0.0;
        if (parsedCenterX != null && parsedCenterY != null) {
            if (parsedCenterX.proportional() != proportional) {
                throw new IllegalArgumentException("Radial gradient cannot mix proportional and absolute coordinates");
            }
            centerX = parsedCenterX.value();
            centerY = parsedCenterY.value();
        }

        CycleParse cycle = parseCycleAt(segments, index);
        index = cycle.nextIndex();
        @Unmodifiable List<WindowBackgroundPaint.GradientStop> stops = parseStops(
                segments.subList(index, segments.size()),
                proportional,
                parsedRadius.value());
        return new WindowBackgroundPaint.Radial(
                focusAngle,
                focusDistance,
                centerX,
                centerY,
                parsedRadius.value(),
                proportional,
                cycle.cycle(),
                stops);
    }

    /// Parses an optional cycle token at one segment index.
    ///
    /// @param segments complete gradient segments
    /// @param index possible cycle index
    /// @return cycle and first stop index
    private static CycleParse parseCycleAt(@Unmodifiable List<String> segments, int index) {
        if (index >= segments.size()) {
            return new CycleParse(WindowBackgroundPaint.Cycle.PAD, index);
        }
        @Nullable WindowBackgroundPaint.Cycle cycle = switch (segments.get(index).trim().toLowerCase(Locale.ROOT)) {
            case "pad" -> WindowBackgroundPaint.Cycle.PAD;
            case "reflect" -> WindowBackgroundPaint.Cycle.REFLECT;
            case "repeat" -> WindowBackgroundPaint.Cycle.REPEAT;
            default -> null;
        };
        return cycle == null
                ? new CycleParse(WindowBackgroundPaint.Cycle.PAD, index)
                : new CycleParse(cycle, index + 1);
    }

    /// Parses and normalizes JavaFX/CSS color stops.
    ///
    /// @param segments color-stop segments only
    /// @param proportional whether the gradient uses percentage coordinates
    /// @param absoluteLength absolute gradient length used by pixel stop positions
    /// @return immutable normalized stops covering zero through one
    private static @Unmodifiable List<WindowBackgroundPaint.GradientStop> parseStops(
            List<String> segments,
            boolean proportional,
            double absoluteLength) {
        if (segments.size() < 2) {
            throw new IllegalArgumentException("A gradient requires at least two color stops");
        }
        List<RawStop> rawStops = new ArrayList<>(segments.size());
        for (String segment : segments) {
            @Unmodifiable List<String> tokens = splitWhitespace(segment);
            if (tokens.size() > 2) {
                throw new IllegalArgumentException("Unexpected content in gradient color stop");
            }
            Color color = parseColorValue(tokens.get(0));
            @Nullable Double offset = tokens.size() == 2
                    ? parseStopOffset(tokens.get(1), proportional, absoluteLength)
                    : null;
            rawStops.add(new RawStop(color, offset));
        }
        return normalizeStops(rawStops);
    }

    /// Parses one percentage or absolute pixel color-stop offset.
    ///
    /// @param token offset token
    /// @param proportional whether only percentage offsets are legal
    /// @param absoluteLength absolute gradient length
    /// @return unbounded fraction for later JavaFX-style normalization
    private static double parseStopOffset(String token, boolean proportional, double absoluteLength) {
        String value = token.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("%")) {
            return parseFinite(value.substring(0, value.length() - 1), "stop percentage") / 100.0;
        }
        if (proportional) {
            throw new IllegalArgumentException("A proportional gradient requires percentage color stops");
        }
        if (absoluteLength <= 0.0) {
            throw new IllegalArgumentException("An absolute zero-length gradient cannot use pixel color stops");
        }
        String number = value.endsWith("px") ? value.substring(0, value.length() - 2) : value;
        return parseFinite(number, "stop length") / absoluteLength;
    }

    /// Resolves omitted positions, nondecreasing CSS ordering, and JavaFX endpoint rules.
    ///
    /// @param rawStops parsed colors with optional unbounded positions
    /// @return immutable normalized stops
    private static @Unmodifiable List<WindowBackgroundPaint.GradientStop> normalizeStops(
            List<RawStop> rawStops) {
        int size = rawStops.size();
        double[] offsets = new double[size];
        boolean[] specified = new boolean[size];
        for (int index = 0; index < size; index++) {
            @Nullable Double offset = rawStops.get(index).offset();
            if (offset != null) {
                offsets[index] = offset;
                specified[index] = true;
            }
        }
        if (!specified[0]) {
            offsets[0] = 0.0;
            specified[0] = true;
        }
        if (!specified[size - 1]) {
            offsets[size - 1] = 1.0;
            specified[size - 1] = true;
        }

        double maximum = offsets[0];
        for (int index = 1; index < size; index++) {
            if (specified[index]) {
                offsets[index] = Math.max(maximum, offsets[index]);
                maximum = offsets[index];
            }
        }
        int index = 1;
        while (index < size - 1) {
            if (specified[index]) {
                index++;
                continue;
            }
            int first = index;
            while (index < size && !specified[index]) {
                index++;
            }
            int count = index - first + 1;
            double step = (offsets[index] - offsets[first - 1]) / count;
            for (int missing = first; missing < index; missing++) {
                offsets[missing] = offsets[first - 1] + step * (missing - first + 1);
            }
        }

        @Nullable Color zeroColor = null;
        @Nullable Color oneColor = null;
        List<WindowBackgroundPaint.GradientStop> interior = new ArrayList<>();
        for (int stopIndex = 0; stopIndex < size; stopIndex++) {
            double offset = offsets[stopIndex];
            Color color = rawStops.get(stopIndex).color();
            if (offset <= 0.0) {
                zeroColor = color;
            } else if (offset >= 1.0) {
                if (oneColor == null) {
                    oneColor = color;
                }
            } else {
                int interiorSize = interior.size();
                if (interiorSize >= 2
                        && interior.get(interiorSize - 1).offset() == offset
                        && interior.get(interiorSize - 2).offset() == offset) {
                    interior.set(interiorSize - 1, new WindowBackgroundPaint.GradientStop(offset, color));
                } else {
                    interior.add(new WindowBackgroundPaint.GradientStop(offset, color));
                }
            }
        }

        if (zeroColor == null) {
            zeroColor = interior.isEmpty()
                    ? Objects.requireNonNull(oneColor, "normalized one-stop color")
                    : interior.get(0).color();
        }
        if (oneColor == null) {
            oneColor = interior.isEmpty() ? zeroColor : interior.get(interior.size() - 1).color();
        }
        List<WindowBackgroundPaint.GradientStop> normalized = new ArrayList<>(interior.size() + 2);
        normalized.add(new WindowBackgroundPaint.GradientStop(0.0, zeroColor));
        normalized.addAll(interior);
        normalized.add(new WindowBackgroundPaint.GradientStop(1.0, oneColor));
        return List.copyOf(normalized);
    }

    /// Parses one JavaFX gradient coordinate.
    ///
    /// @param token percentage, pixel, or unitless pixel value
    /// @return parsed coordinate and proportional mode
    private static ParsedCoordinate parseCoordinate(String token) {
        String value = token.trim().toLowerCase(Locale.ROOT);
        boolean proportional = value.endsWith("%");
        String number;
        if (proportional) {
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("px")) {
            number = value.substring(0, value.length() - 2);
        } else {
            number = value;
        }
        double parsed = parseFinite(number, "gradient coordinate");
        return new ParsedCoordinate(proportional ? parsed / 100.0 : parsed, proportional);
    }

    /// Verifies that all coordinates use the same proportional mode.
    ///
    /// @param coordinates nonempty coordinate sequence
    /// @return shared proportional mode
    private static boolean requireCommonMode(@Unmodifiable List<ParsedCoordinate> coordinates) {
        boolean proportional = coordinates.get(0).proportional();
        for (ParsedCoordinate coordinate : coordinates) {
            if (coordinate.proportional() != proportional) {
                throw new IllegalArgumentException("A gradient cannot mix proportional and absolute coordinates");
            }
        }
        return proportional;
    }

    /// Parses a JavaFX angle unit and converts it to degrees.
    ///
    /// @param token angle token
    /// @return finite degrees
    private static double parseAngle(String token) {
        String value = token.trim().toLowerCase(Locale.ROOT);
        double degrees;
        if (value.endsWith("deg")) {
            degrees = parseFinite(value.substring(0, value.length() - 3), "focus angle");
        } else if (value.endsWith("grad")) {
            degrees = parseFinite(value.substring(0, value.length() - 4), "focus angle") * 0.9;
        } else if (value.endsWith("rad")) {
            degrees = Math.toDegrees(parseFinite(value.substring(0, value.length() - 3), "focus angle"));
        } else if (value.endsWith("turn")) {
            degrees = parseFinite(value.substring(0, value.length() - 4), "focus angle") * 360.0;
        } else {
            throw new IllegalArgumentException("Focus angle requires deg, grad, rad, or turn");
        }
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("Focus angle must be finite");
        }
        return degrees;
    }

    /// Parses a required percentage token.
    ///
    /// @param token percentage token
    /// @return fraction
    private static double parsePercentage(String token) {
        String value = token.trim().toLowerCase(Locale.ROOT);
        if (!value.endsWith("%")) {
            throw new IllegalArgumentException("Focus distance must be a percentage");
        }
        return parseFinite(value.substring(0, value.length() - 1), "focus distance") / 100.0;
    }

    /// Parses one solid color form used by JavaFX persistence and existing Swing settings.
    ///
    /// @param source color expression
    /// @return parsed color
    private static Color parseColorValue(String source) {
        String value = source.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#") || value.startsWith("0x")) {
            int prefixLength = value.startsWith("#") ? 1 : 2;
            return parseHexColor(value.substring(prefixLength));
        }
        return switch (value) {
            case "black" -> Color.BLACK;
            case "white" -> Color.WHITE;
            case "red" -> Color.RED;
            case "green" -> new Color(0x008000);
            case "lime" -> new Color(0x00FF00);
            case "blue" -> Color.BLUE;
            case "gray", "grey" -> Color.GRAY;
            case "silver" -> new Color(0xC0C0C0);
            case "yellow" -> Color.YELLOW;
            case "orange" -> new Color(0xFFA500);
            case "purple" -> new Color(0x800080);
            case "violet" -> new Color(0xEE82EE);
            case "cyan", "aqua" -> Color.CYAN;
            case "magenta", "fuchsia" -> Color.MAGENTA;
            case "navy" -> new Color(0x000080);
            case "teal" -> new Color(0x008080);
            case "maroon" -> new Color(0x800000);
            case "olive" -> new Color(0x808000);
            case "darkgray", "darkgrey" -> new Color(0xA9A9A9);
            case "dimgray", "dimgrey" -> new Color(0x696969);
            case "transparent" -> new Color(0, 0, 0, 0);
            default -> parseFunctionalColor(value);
        };
    }

    /// Parses short or long RGB hexadecimal values with trailing alpha.
    ///
    /// @param digits hexadecimal digits without prefix
    /// @return parsed color
    private static Color parseHexColor(String digits) {
        return switch (digits.length()) {
            case 3 -> new Color(
                    Integer.parseInt(digits.substring(0, 1).repeat(2), 16),
                    Integer.parseInt(digits.substring(1, 2).repeat(2), 16),
                    Integer.parseInt(digits.substring(2, 3).repeat(2), 16));
            case 4 -> new Color(
                    Integer.parseInt(digits.substring(0, 1).repeat(2), 16),
                    Integer.parseInt(digits.substring(1, 2).repeat(2), 16),
                    Integer.parseInt(digits.substring(2, 3).repeat(2), 16),
                    Integer.parseInt(digits.substring(3, 4).repeat(2), 16));
            case 6 -> new Color(Integer.parseInt(digits, 16));
            case 8 -> new Color(
                    Integer.parseInt(digits.substring(0, 2), 16),
                    Integer.parseInt(digits.substring(2, 4), 16),
                    Integer.parseInt(digits.substring(4, 6), 16),
                    Integer.parseInt(digits.substring(6, 8), 16));
            default -> throw new IllegalArgumentException("Unsupported hexadecimal color length");
        };
    }

    /// Parses integer or percentage RGB channels with optional decimal or legacy integer alpha.
    ///
    /// @param value functional color expression
    /// @return parsed RGB color
    private static Color parseFunctionalColor(String value) {
        boolean rgba = value.startsWith("rgba(") && value.endsWith(")");
        boolean rgb = value.startsWith("rgb(") && value.endsWith(")");
        if (!rgb && !rgba) {
            throw new IllegalArgumentException("Unsupported color expression");
        }
        String body = value.substring(value.indexOf('(') + 1, value.length() - 1);
        @Unmodifiable List<String> components = splitTopLevel(body, ',');
        if (components.size() != (rgba ? 4 : 3)) {
            throw new IllegalArgumentException("Invalid functional color component count");
        }
        int red = parseRgbComponent(components.get(0));
        int green = parseRgbComponent(components.get(1));
        int blue = parseRgbComponent(components.get(2));
        if (!rgba) {
            return new Color(red, green, blue);
        }
        String alphaText = components.get(3).trim();
        double parsedAlpha = parseFinite(alphaText, "alpha");
        int alpha = alphaText.contains(".") || alphaText.contains("e") || parsedAlpha <= 1.0
                ? (int) Math.round(clamp(parsedAlpha, 0.0, 1.0) * 255.0)
                : (int) clamp(parsedAlpha, 0.0, 255.0);
        return new Color(red, green, blue, alpha);
    }

    /// Parses one integer or percentage RGB component.
    ///
    /// @param component channel token
    /// @return inclusive zero-to-255 channel
    private static int parseRgbComponent(String component) {
        String value = component.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("%")) {
            double percentage = parseFinite(value.substring(0, value.length() - 1), "RGB percentage");
            return (int) Math.round(clamp(percentage, 0.0, 100.0) * 2.55);
        }
        return (int) Math.round(clamp(parseFinite(value, "RGB component"), 0.0, 255.0));
    }

    /// Splits one expression at top-level delimiters while preserving nested color functions.
    ///
    /// @param source expression body
    /// @param delimiter delimiter character
    /// @return immutable nonempty trimmed tokens
    private static @Unmodifiable List<String> splitTopLevel(String source, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("Unbalanced paint expression parentheses");
                }
            }
            if (character == delimiter && depth == 0) {
                addToken(tokens, current);
            } else {
                current.append(character);
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("Unbalanced paint expression parentheses");
        }
        addToken(tokens, current);
        return List.copyOf(tokens);
    }

    /// Splits one segment at top-level whitespace while preserving nested color functions.
    ///
    /// @param source one direction, directive, or color-stop segment
    /// @return immutable nonempty tokens
    private static @Unmodifiable List<String> splitWhitespace(String source) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("Unbalanced color function parentheses");
                }
            }
            if (Character.isWhitespace(character) && depth == 0) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("Unbalanced color function parentheses");
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Paint expression contains an empty token");
        }
        return List.copyOf(tokens);
    }

    /// Adds one trimmed nonempty token and clears its buffer.
    ///
    /// @param tokens destination token list
    /// @param current token buffer
    private static void addToken(List<String> tokens, StringBuilder current) {
        String token = current.toString().trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Paint expression contains an empty token");
        }
        tokens.add(token);
        current.setLength(0);
    }

    /// Returns one required segment or throws a parse failure.
    ///
    /// @param segments complete segment list
    /// @param index required index
    /// @param expected diagnostic expected content
    /// @return required segment
    private static String requireSegment(@Unmodifiable List<String> segments, int index, String expected) {
        if (index >= segments.size()) {
            throw new IllegalArgumentException("Radial gradient requires " + expected);
        }
        return segments.get(index);
    }

    /// Validates the exact token count for one radial directive.
    ///
    /// @param tokens directive tokens
    /// @param expected expected token count including directive name
    /// @param directive diagnostic directive name
    private static void requireTokenCount(
            @Unmodifiable List<String> tokens,
            int expected,
            String directive) {
        if (tokens.size() != expected) {
            throw new IllegalArgumentException(directive + " has an invalid argument count");
        }
    }

    /// Compares one token case-insensitively.
    ///
    /// @param actual actual token
    /// @param expected expected token
    /// @return whether both tokens match
    private static boolean equalsToken(String actual, String expected) {
        return actual.equalsIgnoreCase(expected);
    }

    /// Parses and validates one finite decimal number.
    ///
    /// @param value decimal source
    /// @param description diagnostic description
    /// @return finite parsed number
    private static double parseFinite(String value, String description) {
        double parsed = Double.parseDouble(value.trim());
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(description + " must be finite");
        }
        return parsed;
    }

    /// Clamps one numeric value inclusively.
    ///
    /// @param value source value
    /// @param minimum inclusive minimum
    /// @param maximum inclusive maximum
    /// @return clamped value
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Parsed coordinate with explicit proportional mode.
    ///
    /// @param value raw pixel value or normalized fraction
    /// @param proportional whether the value is a normalized fraction
    @NotNullByDefault
    private record ParsedCoordinate(double value, boolean proportional) {
    }

    /// Parsed color stop before omitted-position and endpoint normalization.
    ///
    /// @param color exact parsed color
    /// @param offset explicit unbounded fraction, or `null` when omitted
    @NotNullByDefault
    private record RawStop(Color color, @Nullable Double offset) {
        /// Validates one raw stop.
        private RawStop {
            Objects.requireNonNull(color, "color");
            if (offset != null && !Double.isFinite(offset)) {
                throw new IllegalArgumentException("Gradient stop offset must be finite");
            }
        }
    }

    /// Optional cycle parse result and next unread segment index.
    ///
    /// @param cycle parsed cycle, defaulting to pad
    /// @param nextIndex first color-stop segment index
    @NotNullByDefault
    private record CycleParse(WindowBackgroundPaint.Cycle cycle, int nextIndex) {
        /// Validates one cycle result.
        private CycleParse {
            Objects.requireNonNull(cycle, "cycle");
            if (nextIndex < 0) {
                throw new IllegalArgumentException("Next segment index must be non-negative");
            }
        }
    }
}
