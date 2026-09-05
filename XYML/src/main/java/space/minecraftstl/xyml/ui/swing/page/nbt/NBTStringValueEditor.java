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
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.BoxView;
import javax.swing.text.Caret;
import javax.swing.text.ComponentView;
import javax.swing.text.Element;
import javax.swing.text.GlyphView;
import javax.swing.text.IconView;
import javax.swing.text.LabelView;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.ParagraphView;
import javax.swing.text.Position;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.TabExpander;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Shape;
import java.io.Serial;
import java.util.Map;
import java.util.Objects;

/// Editable NBT String field with optional in-place Minecraft formatting effects.
///
/// The styled document always retains every original character, including section signs and code
/// characters. Preview updates only character attributes, so editing offsets, clipboard text, and
/// the value submitted to XoyzNBT remain identical to the user's raw input.
@NotNullByDefault
final class NBTStringValueEditor extends JTextPane implements DocumentListener {
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

    /// Magenta quadrant color used by the fixed obfuscated-text placeholder.
    private static final Color OBFUSCATED_MAGENTA = new Color(0xF800F8);

    /// Black quadrant color used by the fixed obfuscated-text placeholder.
    private static final Color OBFUSCATED_BLACK = new Color(0x000000);

    /// Private styled-document marker selecting the obfuscated glyph painter.
    private static final Object OBFUSCATED_ATTRIBUTE = new Object();

    /// Private styled-document marker selecting the hidden formatting-code painter.
    private static final Object HIDDEN_ATTRIBUTE = new Object();

    /// View factory matching Swing's styled defaults except for content label views.
    private static final ViewFactory VIEW_FACTORY = NBTStringValueEditor::createView;

    /// Whether formatting effects are currently applied to the raw editor text.
    private boolean formattingEnabled;

    /// Coalesces consecutive insert and remove notifications into one style pass.
    private boolean refreshScheduled;

    /// Retains a pending foreground refresh even while formatting effects are disabled.
    private boolean refreshWhenDisabled;

    /// Creates an editable pane whose document owns its live formatting listener.
    NBTStringValueEditor() {
        setEditorKit(new ObfuscatingEditorKit());
        getDocument().addDocumentListener(this);
        addPropertyChangeListener("foreground", event -> scheduleFormatting(true));
    }

    /// Replaces the document directly so CR, LF, and CRLF remain byte-for-character exact.
    ///
    /// JEditorPane's implementation routes through EditorKit text parsing, which normalizes line
    /// endings and is therefore unsuitable for an exact NBT String value.
    ///
    /// @param text exact replacement text, or `null` to clear the editor
    @Override
    public void setText(@Nullable String text) {
        StyledDocument document = getStyledDocument();
        String replacement = Objects.requireNonNullElse(text, "");
        try {
            ((AbstractDocument) document).replace(0, document.getLength(), replacement, null);
            if (!replacement.equals(document.getText(0, document.getLength()))) {
                throw new IllegalStateException("The editor document changed exact NBT String text");
            }
        } catch (BadLocationException failure) {
            throw new IllegalStateException("Could not replace exact NBT String text", failure);
        }
    }

    /// Reads the document directly without EditorKit line-ending serialization.
    ///
    /// @return exact document text
    @Override
    public String getText() {
        StyledDocument document = getStyledDocument();
        try {
            return document.getText(0, document.getLength());
        } catch (BadLocationException failure) {
            throw new IllegalStateException("Could not read exact NBT String text", failure);
        }
    }

    /// Enables or disables in-place formatting without changing document text or selection.
    ///
    /// @param enabled whether recognized formatting codes affect following characters
    void setFormattingEnabled(boolean enabled) {
        if (formattingEnabled == enabled) {
            return;
        }
        formattingEnabled = enabled;
        refreshFormatting();
    }

    /// Schedules a formatting pass after raw text insertion.
    ///
    /// @param event inserted document range
    @Override
    public void insertUpdate(DocumentEvent event) {
        Objects.requireNonNull(event, "event");
        scheduleFormatting();
    }

    /// Schedules a formatting pass after raw text removal.
    ///
    /// @param event removed document range
    @Override
    public void removeUpdate(DocumentEvent event) {
        Objects.requireNonNull(event, "event");
        scheduleFormatting();
    }

    /// Ignores character-attribute notifications produced by the preview itself.
    ///
    /// @param event changed style range
    @Override
    public void changedUpdate(DocumentEvent event) {
        Objects.requireNonNull(event, "event");
    }

    /// Coalesces live text changes while avoiding recursive style updates.
    private void scheduleFormatting() {
        scheduleFormatting(false);
    }

    /// Schedules one coalesced style pass, optionally for a disabled formatting preview.
    ///
    /// @param evenWhenDisabled whether a component-color change requires default attributes
    private void scheduleFormatting(boolean evenWhenDisabled) {
        refreshWhenDisabled |= evenWhenDisabled;
        if ((!formattingEnabled && !refreshWhenDisabled) || refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        SwingUtilities.invokeLater(() -> {
            boolean refresh = formattingEnabled || refreshWhenDisabled;
            refreshScheduled = false;
            refreshWhenDisabled = false;
            if (refresh) {
                refreshFormatting();
            }
        });
    }

    /// Reapplies default and parsed attributes while preserving caret direction and offsets.
    private void refreshFormatting() {
        StyledDocument document = getStyledDocument();
        Caret caret = getCaret();
        int dot = caret.getDot();
        int mark = caret.getMark();
        try {
            String source = document.getText(0, document.getLength());
            MutableAttributeSet base = baseAttributes();
            applyAttributes(document, 0, source.length(), base);
            if (formattingEnabled) {
                applyFormatting(document, source, base);
            }
        } catch (BadLocationException failure) {
            throw new IllegalStateException("Could not update in-place String formatting", failure);
        } finally {
            clearPreviewInputAttributes();
            if (caret.getDot() != dot || caret.getMark() != mark) {
                caret.setDot(mark);
                caret.moveDot(dot);
            }
        }
    }

    /// Prevents preview-only attributes from leaking into text inserted after a toggle or reset.
    private void clearPreviewInputAttributes() {
        MutableAttributeSet input = ((StyledEditorKit) getEditorKit()).getInputAttributes();
        input.removeAttribute(StyleConstants.Foreground);
        input.removeAttribute(StyleConstants.Bold);
        input.removeAttribute(StyleConstants.Italic);
        input.removeAttribute(StyleConstants.Underline);
        input.removeAttribute(StyleConstants.StrikeThrough);
        input.removeAttribute(OBFUSCATED_ATTRIBUTE);
        input.removeAttribute(HIDDEN_ATTRIBUTE);
    }

    /// Applies legacy color, style, reset, and complete RGB sequences at raw document offsets.
    ///
    /// @param document destination styled document
    /// @param source unchanged raw NBT String draft
    /// @param base default component attributes
    private static void applyFormatting(
            StyledDocument document,
            String source,
            MutableAttributeSet base) {
        MutableAttributeSet active = new SimpleAttributeSet(base);
        int segmentStart = 0;
        int index = 0;
        while (index + 1 < source.length()) {
            if (source.charAt(index) != '\u00a7') {
                index++;
                continue;
            }
            char code = Character.toLowerCase(source.charAt(index + 1));
            if (code == '\u00a7') {
                applyAttributes(document, segmentStart, index - segmentStart, active);
                applyAttributes(document, index, 1, hiddenAttributes(base));
                applyAttributes(document, index + 1, 1, active);
                index += 2;
                segmentStart = index;
                continue;
            }
            @Nullable Color legacyColor = LEGACY_COLORS.get(code);
            @Nullable Color rgbColor = code == 'x' ? rgbColor(source, index) : null;
            @Nullable Color hashColor = code == '#' ? hashColor(source, index) : null;
            if (legacyColor == null && rgbColor == null && hashColor == null && "klmnor".indexOf(code) < 0) {
                applyAttributes(document, segmentStart, index - segmentStart, active);
                applyAttributes(document, index, 1, hiddenAttributes(base));
                index++;
                segmentStart = index;
                continue;
            }

            applyAttributes(document, segmentStart, index - segmentStart, active);
            int codeLength = rgbColor != null ? 14 : hashColor != null ? 8 : 2;
            applyAttributes(document, index, codeLength, hiddenAttributes(base));
            if (legacyColor != null || rgbColor != null || hashColor != null) {
                active = coloredAttributes(base,
                        legacyColor != null
                                ? legacyColor
                                : rgbColor != null ? rgbColor : Objects.requireNonNull(hashColor, "hashColor"));
            } else if (code == 'r') {
                active = new SimpleAttributeSet(base);
            } else if (code == 'k') {
                active.addAttribute(OBFUSCATED_ATTRIBUTE, Boolean.TRUE);
            } else {
                applyStyle(active, code);
            }
            index += codeLength;
            segmentStart = index;
        }
        int trailingSectionSign = source.endsWith("\u00a7") ? source.length() - 1 : -1;
        if (trailingSectionSign >= segmentStart) {
            applyAttributes(document, segmentStart, trailingSectionSign - segmentStart, active);
            applyAttributes(document, trailingSectionSign, 1, hiddenAttributes(base));
        } else {
            applyAttributes(document, segmentStart, source.length() - segmentStart, active);
        }
    }

    /// Creates attributes reset to the component's current foreground.
    ///
    /// @return mutable default attributes
    private MutableAttributeSet baseAttributes() {
        MutableAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, Objects.requireNonNullElse(getForeground(), Color.BLACK));
        StyleConstants.setBold(attributes, false);
        StyleConstants.setItalic(attributes, false);
        StyleConstants.setUnderline(attributes, false);
        StyleConstants.setStrikeThrough(attributes, false);
        attributes.addAttribute(OBFUSCATED_ATTRIBUTE, Boolean.FALSE);
        attributes.addAttribute(HIDDEN_ATTRIBUTE, Boolean.FALSE);
        return attributes;
    }

    /// Creates attributes which suppress one formatting-control range during painting.
    ///
    /// @param base default component attributes
    /// @return mutable hidden attributes
    private static MutableAttributeSet hiddenAttributes(MutableAttributeSet base) {
        MutableAttributeSet attributes = new SimpleAttributeSet(base);
        attributes.addAttribute(HIDDEN_ATTRIBUTE, Boolean.TRUE);
        return attributes;
    }

    /// Creates a color code's style-reset attributes.
    ///
    /// @param base default attributes
    /// @param color decoded Minecraft color
    /// @return mutable attributes carrying only the selected color over defaults
    private static MutableAttributeSet coloredAttributes(MutableAttributeSet base, Color color) {
        MutableAttributeSet attributes = new SimpleAttributeSet(base);
        StyleConstants.setForeground(attributes, Objects.requireNonNull(color, "color"));
        return attributes;
    }

    /// Applies one non-color legacy style code to the active range attributes.
    ///
    /// @param attributes active mutable attributes
    /// @param code lowercase legacy style code
    private static void applyStyle(MutableAttributeSet attributes, char code) {
        switch (code) {
            case 'l' -> StyleConstants.setBold(attributes, true);
            case 'm' -> StyleConstants.setStrikeThrough(attributes, true);
            case 'n' -> StyleConstants.setUnderline(attributes, true);
            case 'o' -> StyleConstants.setItalic(attributes, true);
            default -> throw new AssertionError("Unhandled formatting code: " + code);
        }
    }

    /// Merges preview-owned character attributes into a non-empty raw source range.
    ///
    /// Merging retains unrelated document metadata such as the input method's composed-text
    /// attribute. The base attribute set explicitly resets every style owned by this preview.
    ///
    /// @param document destination document
    /// @param offset raw source offset
    /// @param length raw source length
    /// @param attributes complete range attributes
    private static void applyAttributes(
            StyledDocument document,
            int offset,
            int length,
            MutableAttributeSet attributes) {
        if (length > 0) {
            document.setCharacterAttributes(offset, length, attributes, false);
        }
    }

    /// Parses a complete modern section-x RGB sequence beginning at one section sign.
    ///
    /// @param source complete raw source string
    /// @param offset index of the section sign before `x`
    /// @return decoded color, or `null` for an incomplete or malformed sequence
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
        return new Color(Integer.parseInt(digits.toString(), 16));
    }

    /// Parses a compact `§#RRGGBB` color sequence.
    ///
    /// @param source complete source string
    /// @param offset index of the section sign before `#`
    /// @return decoded color, or `null` when the sequence is incomplete or malformed
    private static @Nullable Color hashColor(String source, int offset) {
        if (offset + 7 >= source.length()) {
            return null;
        }
        String digits = source.substring(offset + 2, offset + 8);
        for (int index = 0; index < digits.length(); index++) {
            if (Character.digit(digits.charAt(index), 16) < 0) {
                return null;
            }
        }
        return new Color(Integer.parseInt(digits, 16));
    }

    /// Creates the standard Swing styled views with an obfuscation-aware content view.
    ///
    /// @param element styled document element
    /// @return matching Swing view
    private static View createView(Element element) {
        @Nullable String kind = Objects.requireNonNull(element, "element").getName();
        if (AbstractDocument.ContentElementName.equals(kind)) {
            return new ObfuscatingLabelView(element);
        }
        if (AbstractDocument.ParagraphElementName.equals(kind)) {
            return new ParagraphView(element);
        }
        if (AbstractDocument.SectionElementName.equals(kind)) {
            return new BoxView(element, View.Y_AXIS);
        }
        if (StyleConstants.ComponentElementName.equals(kind)) {
            return new ComponentView(element);
        }
        if (StyleConstants.IconElementName.equals(kind)) {
            return new IconView(element);
        }
        return new ObfuscatingLabelView(element);
    }

    /// Per-editor styled kit installing obfuscation-aware content views.
    @NotNullByDefault
    private static final class ObfuscatingEditorKit extends StyledEditorKit {
        /// Serialization identifier for the Swing editor-kit superclass contract.
        @Serial
        private static final long serialVersionUID = 1L;

        /// Returns the view factory used by this editor kit.
        ///
        /// @return obfuscation-aware styled view factory
        @Override
        public ViewFactory getViewFactory() {
            return VIEW_FACTORY;
        }
    }

    /// Content label view which decorates Swing's current glyph painter when requested by style.
    @NotNullByDefault
    private static final class ObfuscatingLabelView extends LabelView {
        /// Creates a content view for one styled document element.
        ///
        /// @param element represented content element
        private ObfuscatingLabelView(Element element) {
            super(Objects.requireNonNull(element, "element"));
        }

        /// Keeps the decorator synchronized if Swing replaces its platform glyph painter.
        @Override
        protected void checkPainter() {
            super.checkPainter();
            GlyphPainter painter = Objects.requireNonNull(getGlyphPainter(), "glyphPainter");
            boolean obfuscated = Boolean.TRUE.equals(getAttributes().getAttribute(OBFUSCATED_ATTRIBUTE));
            boolean hidden = Boolean.TRUE.equals(getAttributes().getAttribute(HIDDEN_ATTRIBUTE));
            if ((obfuscated || hidden) && !(painter instanceof ObfuscatingGlyphPainter)) {
                setGlyphPainter(new ObfuscatingGlyphPainter(painter));
            } else if (!obfuscated && !hidden && painter instanceof ObfuscatingGlyphPainter decorated) {
                setGlyphPainter(decorated.delegate());
            }
        }
    }

    /// Glyph painter that delegates all geometry while substituting only rendered characters.
    @NotNullByDefault
    private static final class ObfuscatingGlyphPainter extends GlyphView.GlyphPainter {
        /// Platform painter retaining authoritative layout and caret geometry.
        private final GlyphView.GlyphPainter delegate;

        /// Wraps Swing's selected platform glyph painter.
        ///
        /// @param delegate platform painter
        private ObfuscatingGlyphPainter(GlyphView.GlyphPainter delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns the wrapped platform painter.
        ///
        /// @return wrapped painter
        private GlyphView.GlyphPainter delegate() {
            return delegate;
        }

        /// Delegates authoritative horizontal span calculation.
        ///
        /// @param view glyph view
        /// @param startOffset first model offset
        /// @param endOffset exclusive model offset
        /// @param expander tab expansion policy
        /// @param x starting horizontal coordinate
        /// @return platform-calculated span
        @Override
        public float getSpan(GlyphView view, int startOffset, int endOffset, TabExpander expander, float x) {
            return hidden(view) ? 0.0F : delegate.getSpan(view, startOffset, endOffset, expander, x);
        }

        /// Delegates authoritative line height calculation.
        ///
        /// @param view glyph view
        /// @return platform-calculated height
        @Override
        public float getHeight(GlyphView view) {
            return delegate.getHeight(view);
        }

        /// Delegates authoritative ascent calculation.
        ///
        /// @param view glyph view
        /// @return platform-calculated ascent
        @Override
        public float getAscent(GlyphView view) {
            return delegate.getAscent(view);
        }

        /// Delegates authoritative descent calculation.
        ///
        /// @param view glyph view
        /// @return platform-calculated descent
        @Override
        public float getDescent(GlyphView view) {
            return delegate.getDescent(view);
        }

        /// Paints fixed placeholders inside obfuscated character cells and suppresses controls.
        ///
        /// Swing invokes this method separately for styled runs. Hidden runs paint nothing, while
        /// obfuscated runs use a deterministic missing-texture placeholder.
        ///
        /// @param view glyph view
        /// @param graphics active graphics context carrying Swing's chosen text color
        /// @param allocation allocated view shape
        /// @param startOffset first model offset to paint
        /// @param endOffset exclusive model offset to paint
        @Override
        public void paint(GlyphView view, Graphics graphics, Shape allocation, int startOffset, int endOffset) {
            if (hidden(view)) {
                return;
            }
            boolean obfuscated = Boolean.TRUE.equals(view.getAttributes().getAttribute(OBFUSCATED_ATTRIBUTE));
            if (!obfuscated) {
                delegate.paint(view, graphics, allocation, startOffset, endOffset);
                return;
            }
            String source;
            try {
                source = view.getDocument().getText(startOffset, endOffset - startOffset);
            } catch (BadLocationException failure) {
                throw new IllegalStateException("Could not paint obfuscated String text", failure);
            }
            int relativeOffset = 0;
            while (relativeOffset < source.length()) {
                int codePoint = source.codePointAt(relativeOffset);
                int characterCount = Character.charCount(codePoint);
                int absoluteOffset = startOffset + relativeOffset;
                int nextOffset = Math.min(endOffset, absoluteOffset + characterCount);
                if (!Character.isWhitespace(codePoint)) {
                    paintObfuscatedCell(view, graphics, allocation, absoluteOffset, nextOffset);
                }
                relativeOffset += nextOffset - absoluteOffset;
            }
        }

        /// Paints one fixed four-quadrant placeholder without changing the platform-calculated cell.
        ///
        /// @param view glyph view
        /// @param graphics active graphics context
        /// @param allocation allocated view shape
        /// @param startOffset character start offset
        /// @param endOffset character end offset
        private void paintObfuscatedCell(
                GlyphView view,
                Graphics graphics,
                Shape allocation,
                int startOffset,
                int endOffset) {
            try {
                Rectangle characterBounds = view.modelToView(
                        startOffset,
                        Position.Bias.Forward,
                        endOffset,
                        Position.Bias.Backward,
                        allocation).getBounds();
                int left = characterBounds.x;
                int right = characterBounds.x + characterBounds.width;
                if (right <= left) {
                    return;
                }
                int width = right - left;
                Graphics cellGraphics = graphics.create();
                try {
                    cellGraphics.clipRect(left, allocation.getBounds().y, width, allocation.getBounds().height);
                    int top = allocation.getBounds().y;
                    int height = allocation.getBounds().height;
                    int halfWidth = Math.max(1, width / 2);
                    int halfHeight = Math.max(1, height / 2);
                    cellGraphics.setColor(OBFUSCATED_BLACK);
                    cellGraphics.fillRect(left, top, halfWidth, halfHeight);
                    cellGraphics.setColor(OBFUSCATED_MAGENTA);
                    cellGraphics.fillRect(left + halfWidth, top, width - halfWidth, halfHeight);
                    cellGraphics.fillRect(left, top + halfHeight, halfWidth, height - halfHeight);
                    cellGraphics.setColor(OBFUSCATED_BLACK);
                    cellGraphics.fillRect(left + halfWidth, top + halfHeight,
                            width - halfWidth, height - halfHeight);
                } finally {
                    cellGraphics.dispose();
                }
            } catch (BadLocationException failure) {
                throw new IllegalStateException("Could not locate obfuscated String glyph", failure);
            }
        }

        /// Delegates model-to-view geometry.
        ///
        /// @param view glyph view
        /// @param position model position
        /// @param bias position bias
        /// @param allocation allocated view shape
        /// @return platform-calculated view shape
        /// @throws BadLocationException if the position is outside the view
        @Override
        public Shape modelToView(GlyphView view, int position, Position.Bias bias, Shape allocation)
                throws BadLocationException {
            return delegate.modelToView(view, position, bias, allocation);
        }

        /// Delegates view-to-model hit testing.
        ///
        /// @param view glyph view
        /// @param x horizontal coordinate
        /// @param y vertical coordinate
        /// @param allocation allocated view shape
        /// @param biasReturn destination for resolved position bias
        /// @return platform-calculated model position
        @Override
        public int viewToModel(
                GlyphView view,
                float x,
                float y,
                Shape allocation,
                Position.Bias[] biasReturn) {
            return delegate.viewToModel(view, x, y, allocation, biasReturn);
        }

        /// Delegates bounded line-breaking geometry.
        ///
        /// @param view glyph view
        /// @param startOffset first model offset
        /// @param x starting horizontal coordinate
        /// @param length available horizontal span
        /// @return platform-calculated break position
        @Override
        public int getBoundedPosition(GlyphView view, int startOffset, float x, float length) {
            return hidden(view)
                    ? view.getEndOffset()
                    : delegate.getBoundedPosition(view, startOffset, x, length);
        }

        /// Returns whether one formatted control range is collapsed from the preview.
        ///
        /// @param view glyph view
        /// @return whether the view occupies no visible width
        private static boolean hidden(GlyphView view) {
            return Boolean.TRUE.equals(view.getAttributes().getAttribute(HIDDEN_ATTRIBUTE));
        }

        /// Wraps any fragment-specific platform painter.
        ///
        /// @param view glyph view
        /// @param startOffset fragment start offset
        /// @param endOffset fragment end offset
        /// @return wrapped fragment painter, or `null` when Swing will install one later
        @Override
        public @Nullable GlyphView.GlyphPainter getPainter(GlyphView view, int startOffset, int endOffset) {
            @Nullable GlyphView.GlyphPainter fragment = delegate.getPainter(view, startOffset, endOffset);
            return fragment == null ? null : new ObfuscatingGlyphPainter(fragment);
        }

        /// Delegates visual caret navigation.
        ///
        /// @param view glyph view
        /// @param position current model position
        /// @param bias current position bias
        /// @param allocation allocated view shape
        /// @param direction Swing direction constant
        /// @param biasReturn destination for resolved position bias
        /// @return next platform-calculated model position
        /// @throws BadLocationException if the current position is invalid
        @Override
        public int getNextVisualPositionFrom(
                GlyphView view,
                int position,
                Position.Bias bias,
                Shape allocation,
                int direction,
                Position.Bias[] biasReturn) throws BadLocationException {
            return delegate.getNextVisualPositionFrom(view, position, bias, allocation, direction, biasReturn);
        }
    }
}
