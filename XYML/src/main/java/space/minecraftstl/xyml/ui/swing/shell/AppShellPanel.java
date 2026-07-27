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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/// Renders the stable XYML brand, navigation, and lazily created top-level page area.
@NotNullByDefault
public final class AppShellPanel extends JPanel implements AutoCloseable {
    /// Minimum shell width that preserves page and navigation readability.
    public static final int MINIMUM_WIDTH = 900;

    /// Minimum shell height that preserves all navigation targets.
    public static final int MINIMUM_HEIGHT = 560;

    /// Preferred initial shell width.
    public static final int PREFERRED_WIDTH = 1180;

    /// Preferred initial shell height.
    public static final int PREFERRED_HEIGHT = 720;

    /// Stable navigation-band width.
    public static final int NAVIGATION_WIDTH = 184;

    /// Stable brand-header height.
    public static final int HEADER_HEIGHT = 72;

    /// Toolkit-neutral selected-destination state.
    private final ShellNavigationState navigationState;

    /// Lazily created Swing destination pages.
    private final ShellPageCache<JComponent> pageCache;

    /// Buttons keyed by their destination for synchronized selection state.
    private final EnumMap<ShellPageId, ShellNavigationButton> navigationButtons =
            new EnumMap<>(ShellPageId.class);

    /// Header label that identifies the currently visible destination.
    private final JLabel pageTitle = new JLabel();

    /// Discoverable caller-configured file tool hidden until production supplies its workflow.
    private final JButton fileToolButton = new JButton();

    /// Stable content area that owns page transitions.
    private final ShellPageDeck pageDeck;

    /// Localized navigation and page-title presentations.
    private final ShellPagePresentations pagePresentations;

    /// Whether this shell has released all cached page resources.
    private boolean closed;

    /// Current file-tool command, or `null` while the generic header slot is hidden.
    private @Nullable Runnable fileToolCommand;

    /// Creates the application shell on the EDT.
    ///
    /// @param pageFactories one lazy Swing page factory for every destination
    /// @param initialPage the destination shown immediately without animation
    /// @param pagePresentations localized labels and mnemonics for every destination
    /// @param animator the shared Swing animator
    /// @param pageTransitionDuration the non-negative caller-selected transition duration
    public AppShellPanel(
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> pageFactories,
            ShellPageId initialPage,
            ShellPagePresentations pagePresentations,
            SwingAnimator animator,
            Duration pageTransitionDuration) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(initialPage);
        this.pagePresentations = Objects.requireNonNull(pagePresentations);
        navigationState = new ShellNavigationState(initialPage);
        pageCache = new ShellPageCache<>(Objects.requireNonNull(pageFactories));
        pageDeck = new ShellPageDeck(Objects.requireNonNull(animator), Objects.requireNonNull(pageTransitionDuration));

        setLayout(new MigLayout(
                "insets 0, fill",
                "[" + NAVIGATION_WIDTH + "!][grow,fill]",
                "[" + HEADER_HEIGHT + "!][grow,fill]"));
        setMinimumSize(new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT));
        setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));

        add(createHeader(), "cell 0 0 2 1, grow");
        add(createNavigation(), "cell 0 1, grow");
        add(pageDeck, "cell 1 1, grow, gap 24 24 22 24");

        updateSelection(initialPage);
        pageDeck.showPage(pageCache.getOrCreate(initialPage), false);
    }

    /// Selects a destination, creating its page only on first access.
    ///
    /// @param page the destination to show
    public void navigateTo(ShellPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Application shell is closed");
        }
        Objects.requireNonNull(page);
        if (!navigationState.select(page)) {
            return;
        }

        updateSelection(page);
        pageDeck.showPage(pageCache.getOrCreate(page), true);
    }

    /// Returns the currently selected destination.
    ///
    /// @return the selected page identifier
    public ShellPageId selectedPage() {
        return navigationState.selectedPage();
    }

    /// Returns whether a destination page has already been created.
    ///
    /// @param page the destination to inspect
    /// @return `true` when its lazy factory has run
    public boolean isPageCached(ShellPageId page) {
        return pageCache.isCached(Objects.requireNonNull(page));
    }

    /// Returns the number of destination pages created during this session.
    ///
    /// @return the cached page count
    public int cachedPageCount() {
        return pageCache.cachedPageCount();
    }

    /// Configures and reveals the discoverable file-tool command in the stable header.
    ///
    /// @param label localized visible command label and accessible name
    /// @param command caller-owned command invoked on the EDT
    public void configureFileTool(String label, Runnable command) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Application shell is closed");
        }
        String validatedLabel = Objects.requireNonNull(label, "label").strip();
        if (validatedLabel.isEmpty()) {
            throw new IllegalArgumentException("File-tool label cannot be blank");
        }
        fileToolCommand = Objects.requireNonNull(command, "command");
        fileToolButton.setText(validatedLabel);
        fileToolButton.setToolTipText(validatedLabel);
        fileToolButton.getAccessibleContext().setAccessibleName(validatedLabel);
        fileToolButton.setVisible(true);
        revalidate();
        repaint();
    }

    /// Closes all created destination pages from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                fileToolCommand = null;
                fileToolButton.setEnabled(false);
                setTransferHandler(null);
                pageCache.close();
            }
        });
    }

    /// Returns the destination page currently hosted by the deck for focused tests and integrations.
    ///
    /// @return the active page component
    @Nullable JComponent activePage() {
        return pageDeck.currentPage();
    }

    /// Returns a navigation button for focused layout and accessibility verification.
    ///
    /// @param page the represented destination
    /// @return the corresponding button
    ShellNavigationButton navigationButton(ShellPageId page) {
        return Objects.requireNonNull(navigationButtons.get(Objects.requireNonNull(page)));
    }

    /// Returns the optional header file-tool button for focused accessibility verification.
    ///
    /// @return stable header command button
    JButton fileToolButton() {
        return fileToolButton;
    }

    /// Creates the full-width brand and current-destination header.
    ///
    /// @return the configured header band
    private JPanel createHeader() {
        JPanel header = new JPanel(new MigLayout(
                "insets 0 24 0 24, fill",
                "[]16[]push[]",
                "[grow,fill]"));

        @Nullable Icon brandIcon = LauncherIconImages.headerIcon();
        JLabel brand = new JLabel("XYML", brandIcon, SwingConstants.LEFT);
        @Nullable Font configuredBrandFont = brand.getFont();
        Font brandFont = Objects.requireNonNullElse(
                configuredBrandFont,
                new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        brand.setFont(brandFont.deriveFont(Font.BOLD, 19.0f));
        brand.setIconTextGap(10);

        @Nullable Font configuredTitleFont = pageTitle.getFont();
        Font titleFont = Objects.requireNonNullElse(
                configuredTitleFont,
                new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        pageTitle.setFont(titleFont.deriveFont(Font.PLAIN, 15.0f));

        fileToolButton.setName("shellFileTool");
        fileToolButton.setIcon(new FlatSVGIcon("assets/swing/icons/file-import.svg", 18, 18));
        fileToolButton.setIconTextGap(8);
        fileToolButton.setFocusable(true);
        fileToolButton.setVisible(false);
        fileToolButton.addActionListener(event -> {
            @Nullable Runnable command = fileToolCommand;
            if (command != null) {
                command.run();
            }
        });

        header.add(brand);
        header.add(pageTitle);
        header.add(fileToolButton, "h 40!, hidemode 3");
        header.setBorder(ShellSeparatorBorder.bottom());
        return header;
    }

    /// Creates the fixed-width keyboard-accessible navigation band.
    ///
    /// @return the configured navigation panel
    private JPanel createNavigation() {
        JPanel navigation = new JPanel(new MigLayout(
                "insets 14 12, fillx, wrap 1",
                "[grow,fill]",
                "[]8[]8[]8[]8[]push"));
        ButtonGroup group = new ButtonGroup();

        for (ShellPageId page : ShellPageId.values()) {
            ShellNavigationButton button = new ShellNavigationButton(page, pagePresentations.get(page));
            button.addActionListener(event -> navigateTo(page));
            navigationButtons.put(page, button);
            group.add(button);
            navigation.add(button, "growx, h 44!");
        }

        navigation.setBorder(ShellSeparatorBorder.right());
        return navigation;
    }

    /// Synchronizes title and button selection after a navigation state change.
    ///
    /// @param page the newly selected destination
    private void updateSelection(ShellPageId page) {
        pageTitle.setText(pagePresentations.get(page).label());
        navigationButton(page).setSelected(true);
    }

}
