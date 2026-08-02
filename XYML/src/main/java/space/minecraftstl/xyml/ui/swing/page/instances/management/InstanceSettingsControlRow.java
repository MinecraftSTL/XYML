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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.Objects;

/// Responsive inherited setting row that protects both localized labels and usable editor width.
///
/// Wide rows align editors after a stable label column. Narrow rows move the editor beneath the label instead of
/// compressing or clipping it at the disabled horizontal-scroll boundary.
@NotNullByDefault
final class InstanceSettingsControlRow extends JPanel {
    /// Stable allocation for the inheritance control column.
    private static final int OVERRIDE_COLUMN_WIDTH = 26;

    /// Horizontal gap between the inheritance control and label.
    private static final int OVERRIDE_LABEL_GAP = 8;

    /// Minimum wide-layout label column width.
    private static final int LABEL_COLUMN_MINIMUM = 320;

    /// Explicit gap between a complete label and its editor.
    private static final int LABEL_EDITOR_GAP = 16;

    /// Minimum editor width retained before switching to the stacked layout.
    private static final int EDITOR_MINIMUM_WIDTH = 180;

    /// Vertical separation between stacked label and editor rows.
    private static final int STACKED_VERTICAL_GAP = 6;

    /// Inheritance control rendered at the logical leading edge.
    private final JCheckBox overrideBox;

    /// Localized editor label.
    private final JLabel label;

    /// Editor or editor scroll pane laid out responsively.
    private final JComponent editor;

    /// Creates a responsive row whose label targets the visible editor.
    ///
    /// @param labelText localized label text
    /// @param overrideBox inherited-setting control
    /// @param editor visible value editor
    InstanceSettingsControlRow(
            String labelText,
            JCheckBox overrideBox,
            JComponent editor) {
        this(labelText, overrideBox, editor, editor);
    }

    /// Creates a responsive row with a separate accessibility target and visible editor wrapper.
    ///
    /// @param labelText localized label text
    /// @param overrideBox inherited-setting control
    /// @param labelTarget component described by the label
    /// @param editor visible editor or scroll wrapper
    InstanceSettingsControlRow(
            String labelText,
            JCheckBox overrideBox,
            JComponent labelTarget,
            JComponent editor) {
        this.overrideBox = Objects.requireNonNull(overrideBox, "overrideBox");
        JComponent validatedTarget = Objects.requireNonNull(labelTarget, "labelTarget");
        this.editor = Objects.requireNonNull(editor, "editor");
        String validatedLabel = Objects.requireNonNull(labelText, "labelText");
        label = new JLabel(validatedLabel);
        label.setLabelFor(validatedTarget);
        label.setName(validatedTarget.getName() + "Label");
        this.overrideBox.getAccessibleContext().setAccessibleName(validatedLabel);
        setName(validatedTarget.getName() + "Row");
        setLayout(null);
        setOpaque(false);
        add(this.overrideBox);
        add(label);
        add(this.editor);
    }

    /// Lays out a wide three-column row or a narrow two-line row.
    @Override
    public void doLayout() {
        Insets insets = getInsets();
        int contentWidth = Math.max(0, getWidth() - insets.left - insets.right);
        if (usesStackedLayout(contentWidth)) {
            layoutStacked(insets, contentWidth);
        } else {
            layoutWide(insets, contentWidth);
        }
    }

    /// Returns a width-aware preferred size so the parent allocates the stacked row's second line.
    ///
    /// @return preferred row size
    @Override
    public Dimension getPreferredSize() {
        Insets insets = getInsets();
        int availableWidth = availableWidth();
        Dimension labelSize = label.getPreferredSize();
        Dimension editorSize = editor.getPreferredSize();
        int overrideHeight = overrideBox.getPreferredSize().height;
        int contentHeight = usesStackedLayout(availableWidth)
                ? Math.max(overrideHeight, labelSize.height) + STACKED_VERTICAL_GAP + editorSize.height
                : Math.max(Math.max(overrideHeight, labelSize.height), editorSize.height);
        int preferredWidth = availableWidth > 0
                ? availableWidth
                : wideMinimumWidth();
        return new Dimension(
                preferredWidth + insets.left + insets.right,
                contentHeight + insets.top + insets.bottom);
    }

    /// Returns the smallest useful stacked-row size.
    ///
    /// @return minimum row size
    @Override
    public Dimension getMinimumSize() {
        Insets insets = getInsets();
        int firstLineHeight = Math.max(
                overrideBox.getMinimumSize().height,
                label.getMinimumSize().height);
        int height = firstLineHeight + STACKED_VERTICAL_GAP + editor.getMinimumSize().height;
        int width = labelStart() + EDITOR_MINIMUM_WIDTH;
        return new Dimension(
                width + insets.left + insets.right,
                height + insets.top + insets.bottom);
    }

    /// Returns the current or parent-provided content width for preferred-size decisions.
    ///
    /// @return non-negative available width
    private int availableWidth() {
        Insets insets = getInsets();
        if (getParent() != null && getParent().getWidth() > 0) {
            return Math.max(0, getParent().getWidth() - insets.left - insets.right);
        }
        if (getWidth() > 0) {
            return Math.max(0, getWidth() - insets.left - insets.right);
        }
        return 0;
    }

    /// Returns whether the current width requires the two-line layout.
    ///
    /// @param contentWidth available content width
    /// @return true when a wide row cannot retain a usable editor
    private boolean usesStackedLayout(int contentWidth) {
        return contentWidth > 0 && contentWidth < wideMinimumWidth();
    }

    /// Returns the logical starting coordinate of the label and stacked editor.
    ///
    /// @return leading offset in pixels
    private static int labelStart() {
        return OVERRIDE_COLUMN_WIDTH + OVERRIDE_LABEL_GAP;
    }

    /// Returns the wide label-column width for the current localized text and font.
    ///
    /// @return label column width in pixels
    private int labelColumnWidth() {
        return Math.max(LABEL_COLUMN_MINIMUM, label.getPreferredSize().width);
    }

    /// Returns the width at which the editor can remain beside the complete label.
    ///
    /// @return wide-layout minimum width
    private int wideMinimumWidth() {
        return labelStart() + labelColumnWidth() + LABEL_EDITOR_GAP + EDITOR_MINIMUM_WIDTH;
    }

    /// Assigns component bounds for the wide aligned layout.
    ///
    /// @param insets row insets
    /// @param contentWidth available content width
    private void layoutWide(Insets insets, int contentWidth) {
        Dimension overrideSize = overrideBox.getPreferredSize();
        Dimension labelSize = label.getPreferredSize();
        Dimension editorSize = editor.getPreferredSize();
        int rowHeight = Math.max(Math.max(overrideSize.height, labelSize.height), editorSize.height);
        int labelStart = labelStart();
        int editorStart = labelStart + labelColumnWidth() + LABEL_EDITOR_GAP;
        setLogicalBounds(
                overrideBox,
                insets,
                Math.max(0, (OVERRIDE_COLUMN_WIDTH - overrideSize.width) / 2),
                Math.max(0, (rowHeight - overrideSize.height) / 2),
                Math.min(OVERRIDE_COLUMN_WIDTH, overrideSize.width),
                overrideSize.height,
                contentWidth);
        setLogicalBounds(
                label,
                insets,
                labelStart,
                Math.max(0, (rowHeight - labelSize.height) / 2),
                labelColumnWidth(),
                labelSize.height,
                contentWidth);
        setLogicalBounds(
                editor,
                insets,
                editorStart,
                Math.max(0, (rowHeight - editorSize.height) / 2),
                Math.max(0, contentWidth - editorStart),
                editorSize.height,
                contentWidth);
    }

    /// Assigns component bounds for the narrow stacked layout.
    ///
    /// @param insets row insets
    /// @param contentWidth available content width
    private void layoutStacked(Insets insets, int contentWidth) {
        Dimension overrideSize = overrideBox.getPreferredSize();
        Dimension labelSize = label.getPreferredSize();
        Dimension editorSize = editor.getPreferredSize();
        int firstLineHeight = Math.max(overrideSize.height, labelSize.height);
        int labelStart = labelStart();
        setLogicalBounds(
                overrideBox,
                insets,
                Math.max(0, (OVERRIDE_COLUMN_WIDTH - overrideSize.width) / 2),
                Math.max(0, (firstLineHeight - overrideSize.height) / 2),
                Math.min(OVERRIDE_COLUMN_WIDTH, overrideSize.width),
                overrideSize.height,
                contentWidth);
        setLogicalBounds(
                label,
                insets,
                labelStart,
                Math.max(0, (firstLineHeight - labelSize.height) / 2),
                Math.max(0, contentWidth - labelStart),
                labelSize.height,
                contentWidth);
        setLogicalBounds(
                editor,
                insets,
                labelStart,
                firstLineHeight + STACKED_VERTICAL_GAP,
                Math.max(0, contentWidth - labelStart),
                editorSize.height,
                contentWidth);
    }

    /// Applies logical leading-edge bounds for both left-to-right and right-to-left locales.
    ///
    /// @param component component receiving bounds
    /// @param insets row insets
    /// @param logicalX logical leading coordinate
    /// @param y vertical coordinate within content
    /// @param width component width
    /// @param height component height
    /// @param contentWidth available content width
    private void setLogicalBounds(
            Component component,
            Insets insets,
            int logicalX,
            int y,
            int width,
            int height,
            int contentWidth) {
        int boundedWidth = Math.max(0, Math.min(width, contentWidth));
        int x = getComponentOrientation().isLeftToRight()
                ? logicalX
                : contentWidth - logicalX - boundedWidth;
        component.setBounds(
                insets.left + Math.max(0, x),
                insets.top + Math.max(0, y),
                boundedWidth,
                Math.max(0, height));
    }
}
