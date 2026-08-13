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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorUrl;
import space.minecraftstl.xyml.game.ModpackHelper;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingButtonRippleSupport;
import space.minecraftstl.xyml.ui.swing.SwingContentTransition;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsPanel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesPanel;
import space.minecraftstl.xyml.ui.swing.page.downloads.DownloadCategoryPanel;
import space.minecraftstl.xyml.ui.swing.page.settings.SettingsCenterPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

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

    /// Authored expansion and fade duration for click-origin button feedback.
    private static final Duration BUTTON_RIPPLE_DURATION = Duration.ofMillis(450L);

    /// Physical navigation order used to derive top-level transition direction.
    private static final @Unmodifiable List<ShellPageId> NAVIGATION_ORDER = List.of(
            ShellPageId.ACCOUNTS,
            ShellPageId.INSTANCES,
            ShellPageId.DOWNLOADS,
            ShellPageId.SETTINGS);

    /// Toolkit-neutral selected-destination state.
    private final ShellNavigationState navigationState;

    /// Lazily created Swing destination pages.
    private final ShellPageCache<JComponent> pageCache;

    /// Stable instance-management page retained across every top-level transition.
    private final JComponent instancesPage;

    /// Unified page deck for instance management, accounts, downloads, and settings.
    private final ShellPageDeck pageDeck;

    /// Full-window-content title-bar workflow controls.
    private final ShellToolbarPanel toolbar;

    /// Icon-only navigation for transient pages beside persistent instance management.
    private final ShellNavigationRail navigationRail;

    /// Launch progress temporarily covering both base and top-level overlays.
    private final LaunchTaskOverlayPanel launchTaskOverlay;

    /// Layered workspace retaining the page deck and launch-task overlay.
    private final ShellWorkspace workspace;

    /// Root-level click-origin feedback shared by every current and lazily added button.
    private final SwingButtonRippleSupport buttonRippleSupport;

    /// Shell route accepting modpack archives only on instance-management and download pages.
    private final ShellFileDropHandler.RouteRegistration modpackDropRegistration;

    /// Global route accepting authlib-injector server text on every shell page.
    private final ShellFileDropHandler.RouteRegistration authlibDropRegistration;

    /// Current decoded background and native-transparency paint state.
    private WindowBackgroundVisual windowBackground = initialWindowBackground();

    /// Next physical navigation position whose page has not yet been considered for preloading.
    private int nextSidebarPreloadIndex;

    /// Whether the first completed shell paint has started incremental sidebar-page preloading.
    private boolean sidebarPreloadingStarted;

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
        navigationState = new ShellNavigationState();
        pageCache = new ShellPageCache<>(Objects.requireNonNull(pageFactories));
        pageDeck = new ShellPageDeck(animator, pageTransitionDuration);
        instancesPage = pageCache.getOrCreate(ShellPageId.INSTANCES);
        if (instancesPage instanceof InstancesPanel panel) {
            panel.setRevealDefaultPageCommand(this::revealDefaultPage);
        }
        navigationRail = new ShellNavigationRail(pagePresentations, this::togglePage);
        toolbar = new ShellToolbarPanel(
                Objects.requireNonNull(windowTitle, "windowTitle"),
                toolbarModels.home(),
                toolbarModels.instances(),
                toolbarModels.accounts(),
                toolbarModels.gameDirectories(),
                toolbarModels.recentSelections(),
                homeStrings,
                this::navigateTo,
                this::openGameDirectoryManagement,
                this::showDefaultPage);
        launchTaskOverlay = new LaunchTaskOverlayPanel(
                toolbarModels.home(),
                homeStrings,
                taskProgressStrings,
                animator,
                progressAnimationDuration);
        workspace = new ShellWorkspace(pageDeck, launchTaskOverlay);

        setLayout(new MigLayout(
                "insets 0, fill",
                "[52!][grow,fill]",
                "[" + HEADER_HEIGHT + "!][grow,fill]"));
        SwingContentTransition.provideContext(this, animator, pageTransitionDuration);
        setOpaque(true);
        setMinimumSize(new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT));
        setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));

        instancesPage.setOpaque(false);

        add(toolbar, "cell 0 0 2 1, grow");
        add(navigationRail, "cell 0 1, grow");
        add(workspace, "cell 1 1, grow, gap 18 20 18 18");
        showInstanceManagement();
        pageDeck.showPage(instancesPage, false);
        updateSelection(null);
        buttonRippleSupport = new SwingButtonRippleSupport(this, animator, BUTTON_RIPPLE_DURATION);
        modpackDropRegistration = ShellFileDropHandler.register(
                this,
                this::supportsDroppedModpack,
                this::openDroppedModpack);
        authlibDropRegistration = ShellFileDropHandler.registerText(
                this,
                text -> AuthlibInjectorUrl.parse(text).isPresent(),
                this::openDroppedAuthlibServer);
    }

    /// Replaces the renderer-ready background and schedules repainting.
    ///
    /// @param background newest decoded background
    void setWindowBackground(WindowBackgroundVisual background) {
        EdtDispatcher.requireEventDispatchThread();
        windowBackground = Objects.requireNonNull(background, "background");
        setOpaque(!background.windowTransparent());
        repaint();
    }

    /// Synchronizes the shell's clearing behavior with the native window's actual transparency state.
    ///
    /// @param transparent whether the native window currently supports and uses transparency
    void setWindowTransparency(boolean transparent) {
        EdtDispatcher.requireEventDispatchThread();
        windowBackground = windowBackground.withWindowTransparency(transparent);
        setOpaque(!transparent);
        repaint();
    }

    /// Paints a cover-cropped image or bounds-aware paint beneath all shell controls.
    ///
    /// @param graphics target Swing graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        WindowBackgroundVisual visual = windowBackground;
        if (visual.windowTransparent()) {
            Graphics2D clearing = (Graphics2D) graphics.create();
            try {
                clearing.setComposite(AlphaComposite.Clear);
                clearing.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                clearing.dispose();
            }
        } else {
            super.paintComponent(graphics);
        }

        if (visual.opacity() <= 0.0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Graphics2D backgroundGraphics = (Graphics2D) graphics.create();
        try {
            backgroundGraphics.setComposite(AlphaComposite.SrcOver.derive((float) visual.opacity()));
            @Nullable BufferedImage image = visual.image();
            if (image == null) {
                backgroundGraphics.setPaint(visual.fill().awtPaint(getWidth(), getHeight()));
                backgroundGraphics.fillRect(0, 0, getWidth(), getHeight());
            } else {
                paintCoverImage(backgroundGraphics, image, getWidth(), getHeight());
            }
        } finally {
            backgroundGraphics.dispose();
        }
    }

    /// Paints ordinary children before adding button feedback and scheduling post-first-paint page preloading.
    ///
    /// @param graphics target Swing graphics
    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        buttonRippleSupport.paintRipples(graphics);
        if (isShowing()) {
            startSidebarPagePreloading();
        }
    }

    /// Starts one-page-per-event-turn preloading after the persistent main page has painted.
    ///
    /// Package visibility keeps the incremental scheduling contract directly testable without a native window.
    void startSidebarPagePreloading() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || sidebarPreloadingStarted) {
            return;
        }
        sidebarPreloadingStarted = true;
        SwingUtilities.invokeLater(this::preloadNextSidebarPage);
    }

    /// Creates at most one uncached sidebar page before yielding to queued paint and input events.
    private void preloadNextSidebarPage() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }

        while (nextSidebarPreloadIndex < NAVIGATION_ORDER.size()) {
            ShellPageId page = NAVIGATION_ORDER.get(nextSidebarPreloadIndex++);
            if (pageCache.isCached(page)) {
                continue;
            }
            try {
                pageCache.getOrCreate(page);
            } catch (RuntimeException failure) {
                LOG.warning("Failed to preload sidebar page " + page, failure);
            }
            break;
        }

        if (!closed && nextSidebarPreloadIndex < NAVIGATION_ORDER.size()) {
            SwingUtilities.invokeLater(this::preloadNextSidebarPage);
        }
    }

    /// Scales one image to cover the shell while preserving its aspect ratio.
    ///
    /// @param graphics target graphics
    /// @param image decoded source image
    /// @param targetWidth shell width
    /// @param targetHeight shell height
    private static void paintCoverImage(
            Graphics2D graphics,
            BufferedImage image,
            int targetWidth,
            int targetHeight) {
        double scale = Math.max(
                (double) targetWidth / image.getWidth(),
                (double) targetHeight / image.getHeight());
        int width = Math.max(1, (int) Math.ceil(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.ceil(image.getHeight() * scale));
        int x = (targetWidth - width) / 2;
        int y = (targetHeight - height) / 2;
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(image, x, y, width, height, null);
    }

    /// Creates the stable pre-resolution surface used during application startup.
    ///
    /// @return opaque theme-surface background
    private static WindowBackgroundVisual initialWindowBackground() {
        @Nullable Color panelColor = UIManager.getColor("Panel.background");
        Color fill = panelColor != null ? panelColor : new Color(0xF4F4F6);
        return new WindowBackgroundVisual(null, WindowBackgroundPaint.solid(fill), 1.0, false);
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
        @Nullable ShellPageId previousPage = navigationState.selectedPage();
        if (!navigationState.select(page)) {
            return;
        }
        SwingContentTransition.Direction direction = transitionDirection(previousPage, page);

        if (page == ShellPageId.INSTANCES) {
            showInstanceListPage();
            pageDeck.showPage(instancesPage, true, direction);
            updateSelection(page);
            return;
        }
        JComponent destinationPage = pageCache.getOrCreate(page);
        pageDeck.showPage(destinationPage, true, direction);
        showInstanceManagement();
        updateSelection(page);
    }

    /// Opens or toggles one side destination from the left navigation rail.
    ///
    /// Repeating an active rail destination closes it and exposes persistent instance management. Other programmatic
    /// navigation, including popup management footers, continues to keep an already-open side page visible.
    ///
    /// @param page destination selected by the user
    private void togglePage(ShellPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        ShellPageId destination = Objects.requireNonNull(page, "page");
        if (navigationState.selectedPage() == destination) {
            showDefaultPage();
            return;
        }
        navigateTo(destination);
    }

    /// Reveals the persistent main page and clears every side-navigation selection.
    private void showDefaultPage() {
        EdtDispatcher.requireEventDispatchThread();
        showInstanceManagement();
        revealDefaultPage();
    }

    /// Exposes the cached persistent page without starting another management transition.
    private void revealDefaultPage() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable ShellPageId previousPage = navigationState.selectedPage();
        navigationState.clear();
        pageDeck.showPage(
                instancesPage,
                true,
                transitionDirection(previousPage, ShellPageId.INSTANCES));
        updateSelection(null);
    }

    /// Reveals the persistent page's list card without disposing its management view.
    private void showInstanceListPage() {
        if (instancesPage instanceof InstancesPanel panel) {
            panel.showInstanceListPage();
        }
    }

    /// Restores management for the selected instance when the persistent page supports it.
    private void showInstanceManagement() {
        if (instancesPage instanceof InstancesPanel panel) {
            panel.showSelectedInstanceManagement(false).toCompletableFuture().join();
        }
    }

    /// Opens the complete game-directory list in settings.
    private void openGameDirectoryManagement() {
        EdtDispatcher.requireEventDispatchThread();
        navigateTo(ShellPageId.SETTINGS);
        JComponent settingsPage = pageCache.getOrCreate(ShellPageId.SETTINGS);
        if (settingsPage instanceof SettingsCenterPanel settings) {
            settings.showGameDirectories();
        }
    }

    /// Returns the currently selected side destination.
    ///
    /// @return selected side page, or `null` while instance management is exposed
    public @Nullable ShellPageId selectedPage() {
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

    /// Returns whether the current top-level page accepts a modpack archive drop.
    ///
    /// @param path dropped local path
    /// @return whether the path is a modpack and the shell is on the default workspace or downloads
    private boolean supportsDroppedModpack(java.nio.file.Path path) {
        ShellPageId page = selectedPage();
        return ModpackHelper.isFileModpackByExtension(path)
                && (page == null || page == ShellPageId.DOWNLOADS);
    }

    /// Opens the local modpack importer after moving to the downloads page when necessary.
    ///
    /// @param archive dropped local modpack archive
    private void openDroppedModpack(java.nio.file.Path archive) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !supportsDroppedModpack(archive)) {
            return;
        }
        if (selectedPage() != ShellPageId.DOWNLOADS) {
            navigateTo(ShellPageId.DOWNLOADS);
        }
        JComponent downloadsPage = pageCache.getOrCreate(ShellPageId.DOWNLOADS);
        if (downloadsPage instanceof DownloadCategoryPanel downloads) {
            downloads.openDroppedModpack(archive);
        }
    }

    /// Opens the existing account server-management workflow for decoded dropped text.
    ///
    /// @param transferText canonical integration URI or direct Yggdrasil endpoint
    private void openDroppedAuthlibServer(String transferText) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        AuthlibInjectorUrl.parse(transferText).ifPresent(endpoint -> {
            JComponent accountsPage = pageCache.getOrCreate(ShellPageId.ACCOUNTS);
            if (accountsPage instanceof AccountsPanel accounts) {
                accounts.openDroppedAuthlibServer(this, endpoint);
            }
        });
    }

    /// Closes all created destination pages from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                authlibDropRegistration.close();
                modpackDropRegistration.close();
                setTransferHandler(null);
                @Nullable Throwable failure = null;
                failure = attemptClose(failure, toolbar);
                navigationRail.disableNavigation();
                failure = attemptClose(failure, launchTaskOverlay);
                failure = attemptClose(failure, buttonRippleSupport);
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
        return pageDeck.currentPage();
    }

    /// Returns the unified top-level page deck for focused transition verification.
    ///
    /// @return stable page deck
    ShellPageDeck pageDeck() {
        return pageDeck;
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
    /// @param page the newly selected side destination, or `null` for persistent instance management
    private void updateSelection(@Nullable ShellPageId page) {
        toolbar.setSelectedPage(page);
        navigationRail.setSelectedPage(page);
    }

    /// Derives vertical motion from the navigation rail's physical destination order.
    ///
    /// @param previous selected side destination, or null while instance management is exposed
    /// @param destination incoming destination
    /// @return direction matching the destination's position relative to the previous page
    private static SwingContentTransition.Direction transitionDirection(
            @Nullable ShellPageId previous,
            ShellPageId destination) {
        ShellPageId origin = previous != null ? previous : ShellPageId.INSTANCES;
        return NAVIGATION_ORDER.indexOf(destination) > NAVIGATION_ORDER.indexOf(origin)
                ? SwingContentTransition.Direction.VERTICAL_FORWARD
                : SwingContentTransition.Direction.VERTICAL_BACKWARD;
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

    /// Fixed-bounds layered workspace keeping page transitions below the launch-task overlay.
    @NotNullByDefault
    private static final class ShellWorkspace extends JPanel {
        /// Creates the page and launch-overlay layers in input-facing z-order.
        ///
        /// @param pageDeck all persistent and lazy top-level pages
        /// @param launchOverlay current launch-task surface
        private ShellWorkspace(
                ShellPageDeck pageDeck,
                LaunchTaskOverlayPanel launchOverlay) {
            super(null);
            setOpaque(false);
            add(Objects.requireNonNull(pageDeck, "pageDeck"));
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
