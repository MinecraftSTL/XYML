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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.settings.SettingsCenterPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/// Renders a title-bar workflow above persistent instance management and lazy overlay pages.
@NotNullByDefault
public final class AppShellPanel extends JPanel implements AutoCloseable {
    /// Minimum shell width that preserves page and navigation readability.
    public static final int MINIMUM_WIDTH = 1040;

    /// Minimum shell height that preserves all navigation targets.
    public static final int MINIMUM_HEIGHT = 560;

    /// Preferred initial shell width.
    public static final int PREFERRED_WIDTH = 1180;

    /// Preferred initial shell height.
    public static final int PREFERRED_HEIGHT = 720;

    /// Stable full-window-content title-bar height.
    public static final int HEADER_HEIGHT = 52;

    /// Toolkit-neutral selected-destination state.
    private final ShellNavigationState navigationState;

    /// Lazily created Swing destination pages.
    private final ShellPageCache<JComponent> pageCache;

    /// Stable instance-management page retained underneath every top-level overlay.
    private final JComponent instancesPage;

    /// Lazy overlay deck for accounts, downloads, and settings.
    private final ShellPageDeck overlayDeck;

    /// Full-window-content title-bar workflow controls.
    private final ShellToolbarPanel toolbar;

    /// Icon-only navigation for transient pages beside persistent instance management.
    private final ShellNavigationRail navigationRail;

    /// Launch progress temporarily covering both base and top-level overlays.
    private final LaunchTaskOverlayPanel launchTaskOverlay;

    /// Layered workspace retaining the base, page overlay, and launch-task overlay.
    private final ShellWorkspace workspace;

    /// Whether this shell has released all cached page resources.
    private boolean closed;

    /// Creates the application shell on the EDT.
    ///
    /// @param windowTitle visible launcher title beside the bundled icon
    /// @param pageFactories one lazy Swing page factory for every destination
    /// @param pagePresentations localized labels and mnemonics for every destination
    /// @param toolbarModels non-owning launcher workflow models used by the title bar
    /// @param homeStrings localized title-bar launch controls
    /// @param taskProgressStrings localized launch progress controls
    /// @param animator the shared Swing animator
    /// @param pageTransitionDuration the non-negative caller-selected transition duration
    /// @param progressAnimationDuration non-negative launch progress animation duration
    public AppShellPanel(
            String windowTitle,
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> pageFactories,
            ShellPagePresentations pagePresentations,
            ShellToolbarModels toolbarModels,
            HomeStrings homeStrings,
            TaskProgressStrings taskProgressStrings,
            SwingAnimator animator,
            Duration pageTransitionDuration,
            Duration progressAnimationDuration) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(pagePresentations, "pagePresentations");
        Objects.requireNonNull(toolbarModels, "toolbarModels");
        Objects.requireNonNull(homeStrings, "homeStrings");
        Objects.requireNonNull(taskProgressStrings, "taskProgressStrings");
        Objects.requireNonNull(animator, "animator");
        Objects.requireNonNull(pageTransitionDuration, "pageTransitionDuration");
        Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration");
        navigationState = new ShellNavigationState(ShellPageId.INSTANCES);
        pageCache = new ShellPageCache<>(Objects.requireNonNull(pageFactories));
        overlayDeck = new ShellPageDeck(animator, pageTransitionDuration);
        instancesPage = pageCache.getOrCreate(ShellPageId.INSTANCES);
        navigationRail = new ShellNavigationRail(pagePresentations, this::toggleOverlay);
        toolbar = new ShellToolbarPanel(
                Objects.requireNonNull(windowTitle, "windowTitle"),
                toolbarModels.home(),
                toolbarModels.instances(),
                toolbarModels.accounts(),
                toolbarModels.gameDirectories(),
                toolbarModels.recentSelections(),
                homeStrings,
                this::navigateTo,
                () -> openGameDirectoryManagement(true),
                () -> openGameDirectoryManagement(false));
        launchTaskOverlay = new LaunchTaskOverlayPanel(
                toolbarModels.home(),
                homeStrings,
                taskProgressStrings,
                animator,
                progressAnimationDuration);
        workspace = new ShellWorkspace(instancesPage, overlayDeck, launchTaskOverlay);

        setLayout(new MigLayout(
                "insets 0, fill",
                "[52!][grow,fill]",
                "[" + HEADER_HEIGHT + "!][grow,fill]"));
        setMinimumSize(new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT));
        setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));

        add(toolbar, "cell 0 0 2 1, grow");
        add(navigationRail, "cell 0 1, grow");
        add(workspace, "cell 1 1, grow, gap 18 20 18 18");
        updateSelection(ShellPageId.INSTANCES);
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
        if (page == ShellPageId.INSTANCES) {
            overlayDeck.setVisible(false);
            workspace.revalidate();
            workspace.repaint();
            return;
        }
        overlayDeck.setVisible(true);
        overlayDeck.showPage(pageCache.getOrCreate(page), true);
    }

    /// Toggles one transient destination from the left navigation rail.
    ///
    /// Repeating an active rail destination closes its overlay and exposes persistent instance management. Other
    /// programmatic navigation, including popup management footers, continues to keep an already-open page visible.
    ///
    /// @param page transient destination selected by the user
    private void toggleOverlay(ShellPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        ShellPageId destination = Objects.requireNonNull(page, "page");
        if (destination == ShellPageId.INSTANCES) {
            throw new IllegalArgumentException("Persistent instance management is not an overlay");
        }
        navigateTo(navigationState.selectedPage() == destination
                ? ShellPageId.INSTANCES
                : destination);
    }

    /// Opens the settings directory tab and optionally starts its add workflow.
    ///
    /// @param beginAdd whether to enter the add editor after revealing the page
    private void openGameDirectoryManagement(boolean beginAdd) {
        EdtDispatcher.requireEventDispatchThread();
        navigateTo(ShellPageId.SETTINGS);
        JComponent settingsPage = pageCache.getOrCreate(ShellPageId.SETTINGS);
        if (settingsPage instanceof SettingsCenterPanel settings) {
            if (beginAdd) {
                settings.beginAddingGameDirectory();
            } else {
                settings.showGameDirectories();
            }
        }
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

    /// Closes all created destination pages from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                setTransferHandler(null);
                @Nullable Throwable failure = null;
                failure = attemptClose(failure, toolbar);
                navigationRail.disableNavigation();
                failure = attemptClose(failure, launchTaskOverlay);
                failure = attemptClose(failure, pageCache);
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure != null) {
                    throw new IllegalStateException("Application shell cleanup failed", failure);
                }
            }
        });
    }

    /// Returns the destination page currently hosted by the deck for focused tests and integrations.
    ///
    /// @return the active page component
    @Nullable JComponent activePage() {
        return navigationState.selectedPage() == ShellPageId.INSTANCES
                ? instancesPage
                : overlayDeck.currentPage();
    }

    /// Returns a navigation button for focused layout and accessibility verification.
    ///
    /// @param page the represented destination
    /// @return the corresponding button
    ShellNavigationButton navigationButton(ShellPageId page) {
        return navigationRail.button(Objects.requireNonNull(page));
    }

    /// Returns the title-bar workflow controls for focused layout verification.
    ///
    /// @return stable toolbar panel
    ShellToolbarPanel toolbar() {
        return toolbar;
    }

    /// Returns the launch task overlay for focused lifecycle verification.
    ///
    /// @return stable launch task overlay
    LaunchTaskOverlayPanel launchTaskOverlay() {
        return launchTaskOverlay;
    }

    /// Synchronizes title-bar navigation state after a base or overlay change.
    ///
    /// @param page the newly selected destination
    private void updateSelection(ShellPageId page) {
        toolbar.setSelectedPage(page);
        navigationRail.setSelectedPage(page);
    }

    /// Attempts one shell-owned cleanup while retaining the first failure.
    ///
    /// @param previous earlier failure, or null
    /// @param resource next resource to close
    /// @return first failure with later failures suppressed, or null
    private static @Nullable Throwable attemptClose(
            @Nullable Throwable previous,
            AutoCloseable resource) {
        try {
            resource.close();
            return previous;
        } catch (Throwable failure) {
            if (previous == null) {
                return failure;
            }
            if (previous != failure) {
                previous.addSuppressed(failure);
            }
            return previous;
        }
    }

    /// Fixed-bounds layered workspace keeping instance management alive below transient overlays.
    @NotNullByDefault
    private static final class ShellWorkspace extends JPanel {
        /// Creates the base and both overlay layers in input-facing z-order.
        ///
        /// @param base persistent instance page
        /// @param pageOverlay accounts, downloads, and settings deck
        /// @param launchOverlay current launch-task surface
        private ShellWorkspace(
                JComponent base,
                ShellPageDeck pageOverlay,
                LaunchTaskOverlayPanel launchOverlay) {
            super(null);
            setOpaque(true);
            pageOverlay.setVisible(false);
            add(Objects.requireNonNull(base, "base"));
            add(Objects.requireNonNull(pageOverlay, "pageOverlay"), 0);
            add(Objects.requireNonNull(launchOverlay, "launchOverlay"), 0);
        }

        /// Keeps all layers on identical stable content bounds.
        @Override
        public void doLayout() {
            for (Component child : getComponents()) {
                child.setBounds(0, 0, getWidth(), getHeight());
            }
        }

        /// Reports overlap because hidden or visible overlays share base bounds.
        ///
        /// @return always false for layered child painting
        @Override
        public boolean isOptimizedDrawingEnabled() {
            return false;
        }
    }
}
