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
import space.minecraftstl.xyml.library.nbt.io.NBTReadIssue;
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.io.StorageProfile;
import space.minecraftstl.xyml.nbt.NBTDocument;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Objects;

/// Persistent Swing warning band for tolerant NBT read diagnostics.
///
/// The view owns only presentation state.  Read reports and documents remain immutable and are
/// supplied by the editor panel whenever its snapshot changes.
@NotNullByDefault
final class NBTReadWarningView {
    /// Localized labels used by the warning controls.
    private final NBTEditorStrings strings;

    /// Unframed warning container inserted below the editor heading.
    private final JPanel band = new JPanel(new BorderLayout(8, 0));

    /// One-line recovery summary.
    private final JLabel label = new JLabel();

    /// Expand/collapse command for bounded diagnostics.
    private final JToggleButton detailsToggle = new JToggleButton();

    /// Plain-text issue list.
    private final JTextArea detailsArea = new JTextArea();

    /// Scroll container for the issue list.
    private final JScrollPane detailsScroll = new JScrollPane(detailsArea);

    /// Creates and initializes one warning view.
    ///
    /// @param strings localized editor strings
    NBTReadWarningView(NBTEditorStrings strings) {
        this.strings = java.util.Objects.requireNonNull(strings, "strings");
        configure();
    }

    /// Returns the component to insert into the editor layout.
    ///
    /// @return persistent warning band
    JComponent component() {
        return band;
    }

    /// Reconciles the band with the current immutable document report.
    ///
    /// @param document current document, or null before a successful open
    void render(@Nullable NBTDocument document) {
        if (document == null || (!document.requiresRepair() && !document.readReport().hasInformationalIssues())) {
            band.setVisible(false);
            detailsScroll.setVisible(false);
            detailsToggle.setSelected(false);
            detailsToggle.setText(strings.showReadDetailsText());
            return;
        }
        NBTReadReport report = document.readReport();
        boolean partial = report.hasPartialDataLoss();
        label.setText(partial
                ? strings.partialReadWarning()
                : report.requiresRepair() ? strings.recoveredReadWarning() : strings.extensionReadWarning());
        detailsArea.setText(formatReadReport(report, document.storageProfile(), strings));
        detailsArea.setCaretPosition(0);
        band.setBackground(partial
                ? warningBackground(new Color(255, 224, 224))
                : warningBackground(new Color(255, 244, 214)));
        band.setVisible(true);
    }

    /// Initializes component names, layout, and the expandable details command.
    private void configure() {
        band.setName("nbtEditorReadWarning");
        band.setBorder(BorderFactory.createEmptyBorder(5, 16, 5, 16));
        band.setOpaque(true);
        label.setName("nbtEditorReadWarningText");
        label.setToolTipText(null);

        detailsArea.setName("nbtEditorReadWarningDetails");
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setRows(5);
        detailsArea.setColumns(40);
        detailsArea.setCaretPosition(0);
        detailsArea.setOpaque(false);
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        detailsScroll.setVisible(false);
        detailsScroll.setPreferredSize(new Dimension(0, 96));

        detailsToggle.setName("nbtEditorReadWarningDetailsToggle");
        detailsToggle.setText(strings.showReadDetailsText());
        detailsToggle.addActionListener(event -> {
            boolean expanded = detailsToggle.isSelected();
            detailsScroll.setVisible(expanded);
            detailsToggle.setText(expanded
                    ? strings.hideReadDetailsText()
                    : strings.showReadDetailsText());
            band.revalidate();
            band.repaint();
        });

        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);
        body.add(label, BorderLayout.NORTH);
        body.add(detailsScroll, BorderLayout.CENTER);
        band.add(body, BorderLayout.CENTER);
        band.add(detailsToggle, BorderLayout.EAST);
        band.setVisible(false);
    }

    /// Chooses a look-and-feel color while retaining a deterministic fallback.
    ///
    /// @param fallback fallback color
    /// @return configured color or fallback
    private static Color warningBackground(Color fallback) {
        @Nullable Color configured = UIManager.getColor("nbtEditor.warningBackground");
        return configured == null ? fallback : configured;
    }

    /// Formats bounded report diagnostics for the expandable details body.
    ///
    /// @param report immutable read report
    /// @param profile immutable storage profile, or `null` when unavailable
    /// @param strings localized text provider
    /// @return plain-text diagnostics
    static String formatReadReport(NBTReadReport report, @Nullable StorageProfile profile,
                                   NBTEditorStrings strings) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(strings, "strings");
        StringBuilder details = new StringBuilder(256);
        details.append(strings.readDetailsEncodingLabel()).append(": ").append(report.encoding()).append('\n');
        details.append(strings.readDetailsStrictLabel()).append(": ").append(report.strictValid()).append('\n');
        for (NBTReadIssue issue : report.issues()) {
            details.append(strings.readDetailsIssue(issue));
            if (!issue.path().isEmpty()) {
                details.append(" ").append(issue.path());
            }
            details.append('\n');
            if (profile != null && profile.isRegion()) {
                int localIndex = parseRegionSlot(issue.path());
                if (localIndex >= 0 && localIndex < StorageProfile.REGION_SLOT_COUNT) {
                    StorageProfile.RegionSlot slot = profile.regionSlot(localIndex);
                    details.append("  ")
                            .append(strings.readDetailsRegionSlot(
                                    localIndex % 32,
                                    localIndex / 32,
                                    slot.marker(),
                                    slot.external(),
                                    slot.occupied()))
                            .append('\n');
                }
            }
            if (details.length() >= 65_536) {
                details.append("...\n");
                break;
            }
        }
        return details.toString();
    }

    /// Extracts a fixed region slot from a diagnostic path without accepting arbitrary scans.
    ///
    /// @param path diagnostic path
    /// @return local slot index, or -1 when the path is not a region slot
    private static int parseRegionSlot(String path) {
        if (!path.startsWith("slot[") || path.indexOf(']') < 6) {
            return -1;
        }
        int end = path.indexOf(']');
        try {
            return Integer.parseInt(path.substring(5, end));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
