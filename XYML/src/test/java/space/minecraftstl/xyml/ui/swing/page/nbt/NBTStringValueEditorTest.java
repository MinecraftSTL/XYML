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
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.Position;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledEditorKit;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that Minecraft formatting is rendered directly over unchanged editable String text.
@NotNullByDefault
final class NBTStringValueEditorTest {
    /// Preserves raw text, caret direction, and reset semantics while toggling the preview.
    @Test
    void stylesRawOffsetsWithoutChangingTheDraftOrSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = new NBTStringValueEditor();
            Color base = editor.getForeground();
            editor.setText("A\u00a7cB\u00a7lC\u00a7rD");
            editor.setCaretPosition(1);
            editor.moveCaretPosition(editor.getText().length());
            int dot = editor.getCaret().getDot();
            int mark = editor.getCaret().getMark();

            editor.setFormattingEnabled(true);

            assertEquals("A\u00a7cB\u00a7lC\u00a7rD", editor.getText());
            assertEquals(base, foreground(editor, 1));
            assertEquals(new Color(0xFF5555), foreground(editor, 3));
            assertEquals(new Color(0xFF5555), foreground(editor, 6));
            assertTrue(StyleConstants.isBold(editor.getStyledDocument()
                    .getCharacterElement(6).getAttributes()));
            assertEquals(base, foreground(editor, 9));
            assertFalse(StyleConstants.isBold(editor.getStyledDocument()
                    .getCharacterElement(9).getAttributes()));
            assertEquals(dot, editor.getCaret().getDot());
            assertEquals(mark, editor.getCaret().getMark());

            editor.setFormattingEnabled(false);
            assertEquals("A\u00a7cB\u00a7lC\u00a7rD", editor.getText());
            assertEquals(base, foreground(editor, 3));
            assertEquals(dot, editor.getCaret().getDot());
            assertEquals(mark, editor.getCaret().getMark());
        });
    }

    /// Applies complete RGB sequences and refreshes styles after live document edits.
    @Test
    void refreshesRgbFormattingAfterTextChanges() throws Exception {
        AtomicReference<@Nullable NBTStringValueEditor> editorReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = new NBTStringValueEditor();
            editor.setText("A\u00a7x\u00a71\u00a72\u00a73\u00a74\u00a75\u00a76B\u00a7zC");
            editor.setFormattingEnabled(true);
            assertEquals(new Color(0x123456), foreground(editor, 15));
            assertEquals(new Color(0x123456), foreground(editor, 18));
            editor.setText("A\u00a7aB");
            editorReference.set(editor);
        });
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = Objects.requireNonNull(editorReference.get(), "editor");
            assertEquals("A\u00a7aB", editor.getText());
            assertEquals(new Color(0x55FF55), foreground(editor, 3));
        });
    }

    /// Preserves mixed line endings exactly instead of applying JEditorPane normalization.
    @Test
    void preservesMixedLineEndingsExactly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = new NBTStringValueEditor();
            String original = "carriage\rline\nwindows\r\nend";
            editor.setText(original);
            assertEquals(original, editor.getText());
            editor.setCaretPosition(editor.getDocument().getLength());
            editor.replaceSelection("\nnext\r");
            assertEquals(original + "\nnext\r", editor.getText());
            editor.setFormattingEnabled(true);
            assertEquals(original + "\nnext\r", editor.getText());
        });
    }

    /// Preserves input-method metadata and follows foreground changes without moving the caret.
    @Test
    void preservesUnownedAttributesAndCaretEvents() throws Exception {
        AtomicReference<@Nullable NBTStringValueEditor> editorReference = new AtomicReference<>();
        AtomicReference<@Nullable AttributedString> composedTextReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = new NBTStringValueEditor();
            editor.setText("A\u00a7c\u00a7kB");
            AttributedString composedText = new AttributedString("B");
            SimpleAttributeSet inputMethodAttributes = new SimpleAttributeSet();
            inputMethodAttributes.addAttribute(StyleConstants.ComposedTextAttribute, composedText);
            editor.getStyledDocument().setCharacterAttributes(5, 1, inputMethodAttributes, false);
            editor.setCaretPosition(1);
            editor.moveCaretPosition(6);
            AtomicInteger caretEvents = new AtomicInteger();
            editor.addCaretListener(event -> caretEvents.incrementAndGet());

            editor.setFormattingEnabled(true);
            editor.setSize(200, 60);
            render(editor, false);
            editor.setFormattingEnabled(false);

            assertEquals(0, caretEvents.get());
            assertEquals(composedText, editor.getStyledDocument().getCharacterElement(5)
                    .getAttributes().getAttribute(StyleConstants.ComposedTextAttribute));
            editor.setForeground(new Color(0x224466));
            editorReference.set(editor);
            composedTextReference.set(composedText);
        });
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = Objects.requireNonNull(editorReference.get(), "editor");
            assertEquals(new Color(0x224466), foreground(editor, 5));
            assertEquals(composedTextReference.get(), editor.getStyledDocument().getCharacterElement(5)
                    .getAttributes().getAttribute(StyleConstants.ComposedTextAttribute));
            assertEquals("A\u00a7c\u00a7kB", editor.getText());
        });
    }

    /// Masks obfuscated glyphs in place and leaves malformed formatting sequences untouched.
    @Test
    void masksObfuscatedTextWithoutChangingRawCharacters() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = new NBTStringValueEditor();
            Color baseForeground = editor.getForeground();
            editor.setText("\u00a7kAB C\u00a7rD A\u00a7x12B\u00a7");
            editor.setFormattingEnabled(true);

            assertEquals("\u00a7kAB C\u00a7rD A\u00a7x12B\u00a7", editor.getText());
            assertEquals(baseForeground, foreground(editor, 8));
            assertEquals(baseForeground, foreground(editor, 14));
            assertEquals(baseForeground, foreground(editor, editor.getText().length() - 1));
            editor.select(2, 4);
            assertEquals("AB", editor.getSelectedText());
        });
    }

    /// Prevents formatting attributes from leaking into later input after the preview is disabled.
    @Test
    void clearsPreviewAttributesFromSubsequentInput() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = new NBTStringValueEditor();
            Color base = editor.getForeground();
            editor.setText("\u00a7c\u00a7l\u00a7kA");
            editor.setFormattingEnabled(true);
            moveCaretIntoLastFormattingRun(editor);
            MutableAttributeSet inherited = ((StyledEditorKit) editor.getEditorKit()).getInputAttributes();
            assertEquals(new Color(0xFF5555), StyleConstants.getForeground(inherited));
            assertTrue(StyleConstants.isBold(inherited));
            assertTrue(hasEnabledBooleanAttribute(inherited));
            editor.setFormattingEnabled(false);
            editor.replaceSelection("Z");

            assertEquals(base, foreground(editor, editor.getDocument().getLength() - 1));
            assertFalse(StyleConstants.isBold(editor.getStyledDocument()
                    .getCharacterElement(editor.getDocument().getLength() - 1).getAttributes()));
            assertNoEnabledPreviewAttribute(editor, editor.getDocument().getLength() - 1);

            editor.setText("\u00a7kA");
            editor.setFormattingEnabled(true);
            moveCaretIntoLastFormattingRun(editor);
            assertTrue(hasEnabledBooleanAttribute(
                    ((StyledEditorKit) editor.getEditorKit()).getInputAttributes()));
            editor.setText("");
            editor.setFormattingEnabled(false);
            editor.replaceSelection("Z");
            assertEquals("Z", editor.getText());
            assertEquals(base, foreground(editor, 0));
            assertNoEnabledPreviewAttribute(editor, 0);
        });
    }

    /// Paints fixed magenta-black obfuscated cells even inside a native Swing selection.
    @Test
    void paintsSubstituteGlyphsWithoutRevealingSelectedRawText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage plain = render("\u00a7kA", false, false);
            BufferedImage obfuscated = render("\u00a7kA", true, false);
            assertNotEquals(pixelSignature(plain), pixelSignature(obfuscated));
            assertTrue(pixelCount(obfuscated, new Color(0xF800F8)) > 0);

            BufferedImage selectedRaw = render("\u00a7kA", false, true);
            BufferedImage selectedObfuscated = render("\u00a7kA", true, true);
            assertNotEquals(pixelSignature(selectedRaw), pixelSignature(selectedObfuscated));
            assertTrue(pixelCount(selectedObfuscated, new Color(0xF800F8)) > 0);
        });
    }

    /// Collapses formatting controls while retaining one raw model position per source character.
    @Test
    void collapsesFormattingControlGeometry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = configuredEditor(
                    "A\u00a7kWide \u5bbd\ud83d\ude00i \u05d0\u05d1\u05d2\tZ\u00a7rQ");
            editor.setFormattingEnabled(true);
            List<Rectangle2D> formatted = positions(editor);

            assertEquals(editor.getDocument().getLength() + 1, formatted.size());
            assertEquals(formatted.get(1).getX(), formatted.get(3).getX(), 0.01);
            int reset = editor.getText().indexOf("\u00a7r");
            assertEquals(formatted.get(reset).getX(), formatted.get(reset + 2).getX(), 0.01);
            assertEquals(pixelSignature(render("AB", false, false)),
                    pixelSignature(render("A\u00a70B", true, false)));
        });
    }

    /// Supports compact RGB, escaped section signs, and inert unknown formatting markers.
    @Test
    void parsesCompactRgbEscapesAndUnknownCodes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = configuredEditor("A\u00a7#123456B\u00a7\u00a7C\u00a7zD");
            editor.setFormattingEnabled(true);
            List<Rectangle2D> positions = positions(editor);

            assertEquals(new Color(0x123456), foreground(editor, 9));
            assertEquals(new Color(0x123456), foreground(editor, 11));
            assertEquals(positions.get(1).getX(), positions.get(9).getX(), 0.01);
            assertEquals(positions.get(10).getX(), positions.get(11).getX(), 0.01);
            assertTrue(positions.get(12).getX() > positions.get(11).getX());
            assertEquals(positions.get(13).getX(), positions.get(14).getX(), 0.01);
            assertTrue(positions.get(15).getX() > positions.get(14).getX());
            assertEquals(pixelSignature(render("A", false, false)),
                    pixelSignature(render("A\u00a7", true, false)));
            assertEquals("A\u00a7#123456B\u00a7\u00a7C\u00a7zD", editor.getText());
        });
    }

    /// Confines one selected LTR placeholder before an RTL run to its Forward-to-Backward cell.
    @Test
    void keepsRtlObfuscationPaintingInsideItsModelRange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NBTStringValueEditor editor = configuredEditor("\u00a7c\u00a7kA\u00a7r\u05d0\u05d1Z");
            editor.setFormattingEnabled(true);
            editor.select(4, 5);
            editor.getCaret().setSelectionVisible(true);
            Rectangle expected;
            try {
                Rectangle2D start = editor.getUI().modelToView2D(editor, 4, Position.Bias.Forward);
                Rectangle2D end = editor.getUI().modelToView2D(editor, 5, Position.Bias.Backward);
                expected = start.getBounds();
                expected.add(end.getBounds());
                expected.grow(2, 2);
            } catch (javax.swing.text.BadLocationException failure) {
                throw new AssertionError("Could not locate RTL obfuscation range", failure);
            }
            BufferedImage image = render(editor, false);
            int placeholderPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    Color pixel = new Color(image.getRGB(x, y), true);
                    if (pixel.getRGB() == new Color(0xF800F8).getRGB()) {
                        placeholderPixels++;
                        assertTrue(expected.contains(x, y), "RTL replacement escaped its model range");
                    }
                }
            }
            assertTrue(placeholderPixels > 0);
        });
    }

    /// Returns one raw character's effective foreground.
    ///
    /// @param editor styled value editor
    /// @param offset raw document offset
    /// @return effective foreground color
    private static Color foreground(NBTStringValueEditor editor, int offset) {
        return StyleConstants.getForeground(editor.getStyledDocument()
                .getCharacterElement(offset).getAttributes());
    }

    /// Asserts that no boolean-on preview attribute is inherited by one newly inserted character.
    ///
    /// @param editor styled value editor
    /// @param offset inserted character offset
    private static void assertNoEnabledPreviewAttribute(NBTStringValueEditor editor, int offset) {
        AttributeSet attributes = editor.getStyledDocument().getCharacterElement(offset).getAttributes();
        assertFalse(hasEnabledBooleanAttribute(attributes));
    }

    /// Returns whether an attribute set contains any enabled boolean flag.
    ///
    /// @param attributes inspected character or input attributes
    /// @return whether any attribute value is Boolean true
    private static boolean hasEnabledBooleanAttribute(AttributeSet attributes) {
        Enumeration<?> names = attributes.getAttributeNames();
        while (names.hasMoreElements()) {
            Object name = names.nextElement();
            if (Boolean.TRUE.equals(attributes.getAttribute(name))) {
                return true;
            }
        }
        return false;
    }

    /// Moves the caret across an element boundary so StyledEditorKit captures the active run.
    ///
    /// @param editor formatted editor
    private static void moveCaretIntoLastFormattingRun(NBTStringValueEditor editor) {
        editor.setCaretPosition(0);
        editor.setCaretPosition(editor.getDocument().getLength());
    }

    /// Creates a deterministic editor surface for rendering and geometry assertions.
    ///
    /// @param text raw editor text
    /// @return configured editor
    private static NBTStringValueEditor configuredEditor(String text) {
        NBTStringValueEditor editor = new NBTStringValueEditor();
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        editor.setForeground(Color.BLACK);
        editor.setBackground(Color.WHITE);
        editor.setSelectionColor(new Color(0x2266AA));
        editor.setSelectedTextColor(Color.WHITE);
        editor.setText(text);
        editor.setSize(360, 80);
        editor.doLayout();
        return editor;
    }

    /// Renders one editor state into a deterministic bitmap.
    ///
    /// @param text raw editor text
    /// @param formatting whether formatting effects are enabled
    /// @param selected whether the final character is selected
    /// @return rendered bitmap
    private static BufferedImage render(String text, boolean formatting, boolean selected) {
        NBTStringValueEditor editor = configuredEditor(text);
        editor.setFormattingEnabled(formatting);
        return render(editor, selected);
    }

    /// Renders one already-configured editor state into a deterministic bitmap.
    ///
    /// @param editor configured editor
    /// @param selected whether the final character is selected
    /// @return rendered bitmap
    private static BufferedImage render(NBTStringValueEditor editor, boolean selected) {
        if (selected) {
            editor.select(editor.getDocument().getLength() - 1, editor.getDocument().getLength());
            editor.getCaret().setSelectionVisible(true);
        }
        BufferedImage image = new BufferedImage(editor.getWidth(), editor.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            editor.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Records every model offset's view rectangle.
    ///
    /// @param editor configured editor
    /// @return ordered view rectangles
    private static List<Rectangle2D> positions(NBTStringValueEditor editor) {
        List<Rectangle2D> positions = new ArrayList<>();
        try {
            for (int offset = 0; offset <= editor.getDocument().getLength(); offset++) {
                positions.add(editor.modelToView2D(offset).getBounds2D());
            }
        } catch (javax.swing.text.BadLocationException failure) {
            throw new AssertionError("Could not inspect editor geometry", failure);
        }
        return positions;
    }

    /// Computes a stable pixel signature for exact rendering comparisons.
    ///
    /// @param image rendered editor image
    /// @return stable pixel hash
    private static int pixelSignature(BufferedImage image) {
        int signature = 1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                signature = 31 * signature + image.getRGB(x, y);
            }
        }
        return signature;
    }

    /// Counts exact pixels of one opaque color in a rendered image.
    ///
    /// @param image rendered editor image
    /// @param color expected opaque color
    /// @return matching pixel count
    private static int pixelCount(BufferedImage image, Color color) {
        int expected = color.getRGB();
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == expected) {
                    count++;
                }
            }
        }
        return count;
    }

}
