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
package space.minecraftstl.xyml.ui.swing.page.home;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

/// Presents launcher readiness, selected account and instance, and the primary launch command.
///
/// Selection rows are full-width commands rather than nested cards. The panel owns its model subscription and
/// must be closed when its cached shell page is permanently discarded.
@NotNullByDefault
public final class HomePanel extends JPanel implements AutoCloseable {
    /// Home model supplying state and commands.
    private final HomeModel model;

    /// Localized home-page text.
    private final HomeStrings strings;

    /// Selected-account command row.
    private final SelectionButton accountButton;

    /// Selected-instance command row.
    private final SelectionButton instanceButton;

    /// New-instance command.
    private final JButton addInstanceButton = new JButton();

    /// Primary launch command.
    private final JButton launchButton = new JButton();

    /// Current readiness or operation status.
    private final JLabel statusLabel = new JLabel();

    /// Owned home-state listener registration.
    private final Subscription modelSubscription;

    /// Snapshot currently represented by the controls, or null before initialization.
    private @Nullable HomeSnapshot displayedSnapshot;

    /// Whether this panel has released its model listener.
    private boolean closed;

    /// Creates a launcher home panel on the EDT.
    ///
    /// @param model toolkit-neutral home model
    /// @param strings localized home-page text
    public HomePanel(HomeModel model, HomeStrings strings) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]28[]0[]28[grow,fill]20[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        accountButton = new SelectionButton("homeAccount", strings.accountLabel());
        instanceButton = new SelectionButton("homeInstance", strings.instanceLabel());

        configureComponents();
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(model.snapshot());
    }

    /// Returns the immutable snapshot currently represented by the home controls.
    ///
    /// @return displayed launcher-home state
    public HomeSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial home snapshot was not applied");
    }

    /// Releases the model subscription from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                modelSubscription.unsubscribe();
            }
        });
    }

    /// Builds the stable unframed home-page layout.
    private void configureComponents() {
        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("homePageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        add(heading);

        accountButton.addActionListener(event -> model.selectAccount());
        instanceButton.addActionListener(event -> model.selectInstance());
        add(accountButton, "h 76!");
        add(new JSeparator(), "growx");
        add(instanceButton, "h 76!");

        JPanel actionBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[grow,fill][]16[260!]",
                "[64!]"));
        actionBand.setOpaque(false);

        statusLabel.setName("homeStatus");
        addInstanceButton.setName("homeAddInstance");
        addInstanceButton.setText(strings.addInstanceAction());
        addInstanceButton.addActionListener(event -> model.addInstance());
        launchButton.setName("homeLaunch");
        launchButton.putClientProperty("JButton.buttonType", "roundRect");
        launchButton.setFont(launchButton.getFont().deriveFont(Font.BOLD, 17.0F));
        launchButton.addActionListener(event -> model.launch());

        actionBand.add(statusLabel, "growx");
        actionBand.add(addInstanceButton, "h 40!");
        actionBand.add(launchButton, "grow");
        add(actionBand, "growx");
    }

    /// Coalesces a worker-published transition to the model's latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated the displayed page
    private void modelChanged(ValueChange<HomeSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies one immutable home state to every control.
    ///
    /// @param snapshot latest home state
    private void applySnapshot(HomeSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        displayedSnapshot = snapshot;

        accountButton.setValues(
                snapshot.accountName().isBlank() ? strings.missingAccountLabel() : snapshot.accountName(),
                snapshot.accountDetail());
        instanceButton.setValues(
                snapshot.instanceName().isBlank() ? strings.missingInstanceLabel() : snapshot.instanceName(),
                snapshot.instanceDetail());
        accountButton.setEnabled(snapshot.selectionCommandsEnabled());
        instanceButton.setEnabled(snapshot.selectionCommandsEnabled());
        addInstanceButton.setEnabled(snapshot.selectionCommandsEnabled());
        statusLabel.setText(snapshot.statusText());
        statusLabel.setToolTipText(snapshot.statusText());
        launchButton.setText(snapshot.launching() ? strings.launchingAction() : strings.launchAction());
        launchButton.setEnabled(snapshot.launchEnabled());
    }

    /// Full-width account or instance selection command with stable title and detail regions.
    @NotNullByDefault
    private static final class SelectionButton extends JButton {
        /// Left padding used by painted field and value text.
        private static final int TEXT_INSET = 14;

        /// Gap between the field label and selected value baselines.
        private static final int VALUE_BASELINE_GAP = 25;

        /// Localized field label painted above the current selection.
        private final String fieldLabel;

        /// Current selected display value.
        private String value = "";

        /// Current short provider or version detail.
        private String detail = "";

        /// Creates one selection command row.
        ///
        /// @param componentName stable automation name
        /// @param fieldLabel localized field label
        private SelectionButton(String componentName, String fieldLabel) {
            this.fieldLabel = Objects.requireNonNull(fieldLabel, "fieldLabel");
            setName(componentName);
            setHorizontalAlignment(LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            putClientProperty("JButton.buttonType", "toolBarButton");
            setText("");
        }

        /// Updates the selected value and short detail without changing row geometry.
        ///
        /// @param value selected display value
        /// @param detail short provider or version detail
        private void setValues(String value, String detail) {
            this.value = Objects.requireNonNull(value, "value");
            this.detail = Objects.requireNonNull(detail, "detail");
            setToolTipText(detail.isBlank() ? value : value + " - " + detail);
            getAccessibleContext().setAccessibleDescription(value + (detail.isBlank() ? "" : ", " + detail));
            repaint();
        }

        /// Paints the field, selected value, and trailing detail while retaining the current look-and-feel button.
        ///
        /// @param graphics target button graphics
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D textGraphics = (Graphics2D) graphics.create();
            try {
                textGraphics.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                textGraphics.setColor(getForeground());

                Font fieldFont = getFont().deriveFont(Math.max(10.0F, getFont().getSize2D() - 1.0F));
                Font valueFont = getFont().deriveFont(Font.BOLD, getFont().getSize2D() + 2.0F);
                FontMetrics fieldMetrics = textGraphics.getFontMetrics(fieldFont);
                FontMetrics valueMetrics = textGraphics.getFontMetrics(valueFont);
                int fieldBaseline = Math.max(fieldMetrics.getAscent() + 9, getHeight() / 2 - 7);
                int valueBaseline = Math.min(
                        getHeight() - valueMetrics.getDescent() - 8,
                        fieldBaseline + VALUE_BASELINE_GAP);

                textGraphics.setFont(fieldFont);
                textGraphics.drawString(
                        fitText(fieldMetrics, fieldLabel, getWidth() - TEXT_INSET * 2),
                        TEXT_INSET,
                        fieldBaseline);

                textGraphics.setFont(valueFont);
                int detailWidth = detail.isBlank()
                        ? 0
                        : Math.min(valueMetrics.stringWidth(detail), Math.max(0, getWidth() / 3));
                int valueWidth = Math.max(0, getWidth() - TEXT_INSET * 3 - detailWidth);
                textGraphics.drawString(
                        fitText(valueMetrics, value, valueWidth),
                        TEXT_INSET,
                        valueBaseline);
                if (!detail.isBlank()) {
                    String fittedDetail = fitText(valueMetrics, detail, detailWidth);
                    int detailX = getWidth() - TEXT_INSET - valueMetrics.stringWidth(fittedDetail);
                    textGraphics.drawString(fittedDetail, detailX, valueBaseline);
                }
            } finally {
                textGraphics.dispose();
            }
        }

        /// Fits text to a pixel width using an ASCII ellipsis when truncation is required.
        ///
        /// @param metrics active font metrics
        /// @param text source text
        /// @param maximumWidth available width in pixels
        /// @return original or width-constrained text
        private static String fitText(FontMetrics metrics, String text, int maximumWidth) {
            if (maximumWidth <= 0) {
                return "";
            }
            if (metrics.stringWidth(text) <= maximumWidth) {
                return text;
            }
            String ellipsis = "...";
            int ellipsisWidth = metrics.stringWidth(ellipsis);
            if (ellipsisWidth >= maximumWidth) {
                return "";
            }

            int low = 0;
            int high = text.length();
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (metrics.stringWidth(text.substring(0, middle)) + ellipsisWidth <= maximumWidth) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return text.substring(0, low) + ellipsis;
        }
    }
}
