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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// Renders the complete instance-management destination set as a transparent, scrollable, single-selection rail.
///
/// Buttons use FlatLaf toolbar selection surfaces instead of radio indicators. Arrow, Home, and End keys move both
/// focus and selection, while repeated activation of the current destination is intentionally idempotent.
@NotNullByDefault
public final class InstanceManagementNavigationPanel extends JPanel {
    /// Target width used by the instance-workspace split layout.
    public static final int PREFERRED_WIDTH = 190;

    /// Horizontal size of each bundled navigation icon.
    private static final int ICON_SIZE = 20;

    /// Exclusive selection model for all destination buttons.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// Stable destination buttons used for external synchronization and keyboard traversal.
    private final EnumMap<InstanceManagementPageId, JToggleButton> buttons =
            new EnumMap<>(InstanceManagementPageId.class);

    /// Callback invoked only when user input selects a different destination.
    private final Consumer<InstanceManagementPageId> selectionCommand;

    /// Canonically ordered immutable subset that the owning workspace can actually display.
    private final @Unmodifiable List<InstanceManagementPageId> availablePages;

    /// Transparent layout surface hosted by the vertical scroll pane.
    private final JPanel navigationContent = new JPanel(new MigLayout(
            "insets 8, fillx, wrap 1, gapy 4",
            "[grow,fill]",
            "[]"));

    /// Transparent vertical scroll container allowing every destination to remain reachable in short windows.
    private final JScrollPane scrollPane = new JScrollPane(navigationContent);

    /// Currently selected destination, always represented by exactly one selected button after construction.
    private InstanceManagementPageId selectedPage;

    /// Creates a complete instance navigation rail without invoking the selection callback for its initial state.
    ///
    /// @param availablePages non-empty destination subset supported by the owning workspace
    /// @param initialPage initially selected available destination
    /// @param selectionCommand callback for a user-initiated destination change
    public InstanceManagementNavigationPanel(
            @Unmodifiable List<InstanceManagementPageId> availablePages,
            InstanceManagementPageId initialPage,
            Consumer<InstanceManagementPageId> selectionCommand) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.availablePages = canonicalAvailablePages(availablePages);
        selectedPage = requireAvailable(initialPage);
        this.selectionCommand = Objects.requireNonNull(selectionCommand, "selectionCommand");
        configureComponents();
        selectWithoutNotification(selectedPage);
    }

    /// Returns the currently selected destination.
    ///
    /// @return stable selected page identifier
    public InstanceManagementPageId selectedPage() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedPage;
    }

    /// Returns the supported destination subset in canonical navigation order.
    ///
    /// @return immutable non-empty available page list
    public @Unmodifiable List<InstanceManagementPageId> availablePages() {
        return availablePages;
    }

    /// Synchronizes selection from an owning workspace without invoking the user-selection callback.
    ///
    /// @param page destination that should appear selected
    public void setSelectedPage(InstanceManagementPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        selectWithoutNotification(requireAvailable(page));
    }

    /// Returns the stable full-row button representing one destination.
    ///
    /// @param page requested destination
    /// @return corresponding configured toggle button
    public JToggleButton button(InstanceManagementPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JToggleButton button = buttons.get(Objects.requireNonNull(page, "page"));
        if (button == null) {
            throw new IllegalArgumentException("Page has no navigation button: " + page);
        }
        return button;
    }

    /// Configures transparent layout surfaces, titled groups, buttons, and scrolling behavior.
    private void configureComponents() {
        setName("instanceManagementNavigation");
        setOpaque(false);
        setMinimumSize(new Dimension(0, 0));

        navigationContent.setName("instanceManagementNavigationContent");
        navigationContent.setOpaque(false);
        navigationContent.setMinimumSize(new Dimension(0, 0));

        for (InstanceManagementPageGroup group : InstanceManagementPageGroup.orderedValues()) {
            addGroup(group);
        }

        scrollPane.setName("instanceManagementNavigationScroll");
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setMinimumSize(new Dimension(0, 0));
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        Dimension scrollPreferred = scrollPane.getPreferredSize();
        scrollPane.setPreferredSize(new Dimension(PREFERRED_WIDTH, scrollPreferred.height));
        add(scrollPane, BorderLayout.CENTER);
    }

    /// Adds one optional section heading followed by every destination assigned to the group.
    ///
    /// @param group section rendered at the current layout position
    private void addGroup(InstanceManagementPageGroup group) {
        InstanceManagementPageGroup section = Objects.requireNonNull(group, "group");
        @Unmodifiable List<InstanceManagementPageId> visiblePages = section.pages().stream()
                .filter(availablePages::contains)
                .toList();
        if (visiblePages.isEmpty()) {
            return;
        }
        @Nullable JLabel heading = null;
        if (section.headingVisible()) {
            heading = new JLabel(section.localizedLabel());
            heading.setName("instanceManagementNavigationGroup_" + section.name());
            heading.setFont(heading.getFont().deriveFont(Font.BOLD));
            navigationContent.add(heading, "growx, gaptop 10");
        }

        for (InstanceManagementPageId page : visiblePages) {
            JToggleButton button = createNavigationButton(page);
            if (heading != null && heading.getLabelFor() == null) {
                heading.setLabelFor(button);
            }
            buttonGroup.add(button);
            buttons.put(page, button);
            navigationContent.add(button, "growx, h 40!");
        }
    }

    /// Creates one accessible icon-and-text destination with full-row selected-state painting.
    ///
    /// @param page represented destination
    /// @return configured navigation toggle button
    private JToggleButton createNavigationButton(InstanceManagementPageId page) {
        InstanceManagementPageId destination = Objects.requireNonNull(page, "page");
        String label = destination.localizedLabel();
        JToggleButton button = new JToggleButton(label, createNavigationIcon(destination));
        button.setName("instanceManagementNavigationButton_" + destination.name());
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(10);
        button.setMargin(new Insets(8, 10, 8, 10));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusable(true);
        button.setFocusPainted(true);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.getAccessibleContext().setAccessibleName(label);
        button.getAccessibleContext().setAccessibleDescription(destination.group().localizedLabel());
        button.addActionListener(event -> selectFromUser(destination));
        registerKeyboardNavigation(button);
        return button;
    }

    /// Registers vertical and boundary navigation shortcuts for one focused destination button.
    ///
    /// @param button destination button receiving focused key events
    private void registerKeyboardNavigation(JToggleButton button) {
        JToggleButton target = Objects.requireNonNull(button, "button");
        target.registerKeyboardAction(
                event -> moveSelection(-1),
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
                JComponent.WHEN_FOCUSED);
        target.registerKeyboardAction(
                event -> moveSelection(1),
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
                JComponent.WHEN_FOCUSED);
        target.registerKeyboardAction(
                event -> selectBoundary(true),
                KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0),
                JComponent.WHEN_FOCUSED);
        target.registerKeyboardAction(
                event -> selectBoundary(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_END, 0),
                JComponent.WHEN_FOCUSED);
    }

    /// Moves to the preceding or following destination, wrapping at either end.
    ///
    /// @param offset negative for the preceding page or positive for the following page
    private void moveSelection(int offset) {
        EdtDispatcher.requireEventDispatchThread();
        @Unmodifiable List<InstanceManagementPageId> pages = availablePages;
        int currentIndex = pages.indexOf(selectedPage);
        int targetIndex = Math.floorMod(currentIndex + offset, pages.size());
        selectFromKeyboard(pages.get(targetIndex));
    }

    /// Moves directly to the first or last destination.
    ///
    /// @param first true for the first destination, false for the last
    private void selectBoundary(boolean first) {
        EdtDispatcher.requireEventDispatchThread();
        @Unmodifiable List<InstanceManagementPageId> pages = availablePages;
        selectFromKeyboard(pages.get(first ? 0 : pages.size() - 1));
    }

    /// Applies a keyboard selection and transfers focus to the newly selected row.
    ///
    /// @param page destination reached through keyboard navigation
    private void selectFromKeyboard(InstanceManagementPageId page) {
        selectFromUser(page);
        button(page).requestFocusInWindow();
    }

    /// Applies a user-requested destination change and emits at most one callback.
    ///
    /// @param page user-selected destination
    private void selectFromUser(InstanceManagementPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        InstanceManagementPageId destination = Objects.requireNonNull(page, "page");
        if (destination == selectedPage) {
            button(destination).setSelected(true);
            return;
        }
        selectWithoutNotification(destination);
        selectionCommand.accept(destination);
    }

    /// Changes the selected button while leaving the owning workspace callback untouched.
    ///
    /// @param page destination to select
    private void selectWithoutNotification(InstanceManagementPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        InstanceManagementPageId destination = requireAvailable(page);
        selectedPage = destination;
        button(destination).setSelected(true);
    }

    /// Copies, validates, and canonicalizes the caller's supported destination subset.
    ///
    /// @param pages caller-provided supported destinations
    /// @return immutable non-empty subset in declaration order
    private static @Unmodifiable List<InstanceManagementPageId> canonicalAvailablePages(
            @Unmodifiable List<InstanceManagementPageId> pages) {
        @Unmodifiable List<InstanceManagementPageId> copy =
                List.copyOf(Objects.requireNonNull(pages, "availablePages"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("availablePages must not be empty");
        }
        EnumSet<InstanceManagementPageId> uniquePages = EnumSet.noneOf(InstanceManagementPageId.class);
        for (InstanceManagementPageId page : copy) {
            if (!uniquePages.add(page)) {
                throw new IllegalArgumentException("availablePages contains duplicate page: " + page);
            }
        }
        return InstanceManagementPageId.orderedValues().stream()
                .filter(uniquePages::contains)
                .toList();
    }

    /// Validates that a destination is available in this navigation instance.
    ///
    /// @param page requested destination
    /// @return validated available destination
    private InstanceManagementPageId requireAvailable(InstanceManagementPageId page) {
        InstanceManagementPageId destination = Objects.requireNonNull(page, "page");
        if (!availablePages.contains(destination)) {
            throw new IllegalArgumentException("Page is not available: " + destination);
        }
        return destination;
    }

    /// Creates one theme-aware icon from the destination's bundled resource.
    ///
    /// @param page represented destination
    /// @return configured 20-pixel SVG icon
    private static FlatSVGIcon createNavigationIcon(InstanceManagementPageId page) {
        InstanceManagementPageId destination = Objects.requireNonNull(page, "page");
        FlatSVGIcon icon = new FlatSVGIcon(destination.iconResource(), ICON_SIZE, ICON_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(
                InstanceManagementNavigationPanel::resolveIconColor));
        return icon;
    }

    /// Uses the current button foreground while retaining the SVG color for standalone rendering.
    ///
    /// @param component icon-owning component, or null during standalone rendering
    /// @param originalColor SVG-authored fallback color
    /// @return component foreground when available, otherwise the SVG fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color fallback = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        return foreground == null ? fallback : foreground;
    }
}
