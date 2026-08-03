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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingContentTransition;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/// Hosts supported instance-management pages while constructing each page only on its first visit.
///
/// A page's activation action runs after it enters the visible card. Page implementations retain their own idempotent
/// loading guards, allowing a later visit to retry a failed asynchronous load without duplicating successful work.
/// Construction or first activation failures release the incomplete page and restore the previously selected card.
/// Closing releases only pages that were actually created, in reverse creation order.
@NotNullByDefault
final class InstanceManagementPageDeck extends JPanel implements AutoCloseable {
    /// Creates one freshly owned page on the Swing event-dispatch thread.
    @FunctionalInterface
    @NotNullByDefault
    interface PageFactory {
        /// Creates one page lifecycle for its destination.
        ///
        /// @return freshly owned page
        InstanceManagementPage create();
    }

    /// Card layout controlling the currently visible management page.
    private final CardLayout cardLayout;

    /// Snapshot-composited transition between user-selected management destinations.
    private final SwingContentTransition contentTransition;

    /// Immutable destination-to-factory mapping copied during construction.
    private final @Unmodifiable Map<InstanceManagementPageId, PageFactory> factories;

    /// Canonical destination subset available in this deck.
    private final @Unmodifiable List<InstanceManagementPageId> availablePages;

    /// Pages that have completed construction and first-display activation.
    private final EnumMap<InstanceManagementPageId, InstanceManagementPage> loadedPages =
            new EnumMap<>(InstanceManagementPageId.class);

    /// Exact successful creation order used for deterministic reverse cleanup.
    private final List<InstanceManagementPageId> creationOrder = new ArrayList<>();

    /// Prevents additional navigation and duplicate cleanup after closure begins.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Currently visible page, or `null` before the first page is shown.
    private @Nullable InstanceManagementPageId selectedPage;

    /// Creates an empty transparent deck backed by the supplied supported-page factories.
    ///
    /// @param factories non-empty mapping of supported destinations to fresh page factories
    InstanceManagementPageDeck(Map<InstanceManagementPageId, PageFactory> factories) {
        super(new CardLayout());
        EdtDispatcher.requireEventDispatchThread();
        cardLayout = (CardLayout) getLayout();
        contentTransition = new SwingContentTransition(this);
        EnumMap<InstanceManagementPageId, PageFactory> copiedFactories = copyFactories(factories);
        this.factories = Collections.unmodifiableMap(copiedFactories);
        availablePages = InstanceManagementPageId.orderedValues().stream()
                .filter(copiedFactories::containsKey)
                .toList();
        setName("instanceManagementPageDeck");
        setOpaque(false);
        setMinimumSize(new Dimension(0, 0));
    }

    /// Returns supported destinations in canonical navigation order.
    ///
    /// @return immutable non-empty supported destination list
    @Unmodifiable
    List<InstanceManagementPageId> availablePages() {
        return availablePages;
    }

    /// Shows a destination, creating it first when necessary and then invoking its idempotent selection action.
    ///
    /// @param page requested supported destination
    void showPage(InstanceManagementPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        ensureOpen();
        InstanceManagementPageId destination = requireAvailable(page);
        if (destination == selectedPage) {
            return;
        }
        @Nullable JComponent outgoingComponent = selectedComponent();
        @Nullable InstanceManagementPage loadedPage = loadedPages.get(destination);
        if (loadedPage == null) {
            contentTransition.transitionFrom(outgoingComponent, () -> createAndActivate(destination));
        } else {
            InstanceManagementPage destinationPage = loadedPage;
            contentTransition.transitionFrom(outgoingComponent, () -> {
                cardLayout.show(this, destination.name());
                try {
                    destinationPage.activate();
                } catch (RuntimeException | Error failure) {
                    restoreSelectedCard();
                    throw failure;
                }
            });
        }
        selectedPage = destination;
        revalidate();
        repaint();
    }

    /// Returns the currently visible destination.
    ///
    /// @return selected destination, or `null` before first display
    @Nullable InstanceManagementPageId selectedPage() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedPage;
    }

    /// Reports whether a destination has completed construction and activation.
    ///
    /// @param page queried destination
    /// @return true when the destination owns a loaded page
    boolean isLoaded(InstanceManagementPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        return loadedPages.containsKey(Objects.requireNonNull(page, "page"));
    }

    /// Returns the number of successfully created pages.
    ///
    /// @return loaded page count
    int loadedPageCount() {
        EdtDispatcher.requireEventDispatchThread();
        return loadedPages.size();
    }

    /// Returns whether one management destination is currently fading into another.
    ///
    /// @return true while the cached outgoing frame remains active
    boolean isTransitionRunning() {
        EdtDispatcher.requireEventDispatchThread();
        return contentTransition.isRunning();
    }

    /// Releases cached non-visible pages whose backing instance directories changed.
    ///
    /// The visible page cannot be invalidated because removing the selected card would leave navigation and content
    /// state inconsistent. Unloaded destinations are accepted and require no work.
    ///
    /// @param pages available non-visible destinations to invalidate
    void invalidatePages(Collection<InstanceManagementPageId> pages) {
        EdtDispatcher.requireEventDispatchThread();
        ensureOpen();
        EnumMap<InstanceManagementPageId, Boolean> targets = new EnumMap<>(InstanceManagementPageId.class);
        for (InstanceManagementPageId page : Objects.requireNonNull(pages, "pages")) {
            targets.put(requireAvailable(page), Boolean.TRUE);
        }
        @Nullable InstanceManagementPageId current = selectedPage;
        if (current != null && targets.containsKey(current)) {
            throw new IllegalArgumentException("Cannot invalidate the visible page: " + current);
        }

        @Nullable Throwable firstFailure = null;
        for (int index = creationOrder.size() - 1; index >= 0; index--) {
            InstanceManagementPageId pageId = creationOrder.get(index);
            if (!targets.containsKey(pageId)) {
                continue;
            }
            creationOrder.remove(index);
            @Nullable InstanceManagementPage page = loadedPages.remove(pageId);
            if (page == null) {
                continue;
            }
            remove(page.component());
            try {
                page.close();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        revalidate();
        repaint();
        rethrow(firstFailure);
    }

    /// Releases every loaded page once and clears the component tree.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeLoadedPages);
    }

    /// Paints the live selected management destination and its cached outgoing predecessor.
    ///
    /// @param graphics management deck graphics
    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        contentTransition.paintOverlay(graphics);
    }

    /// Releases any cached transition frame before the management deck leaves the display hierarchy.
    @Override
    public void removeNotify() {
        contentTransition.settle();
        super.removeNotify();
    }

    /// Creates one destination, makes it visible, and runs its first selection action.
    ///
    /// @param destination supported unloaded destination
    /// @return successfully activated owned page
    private InstanceManagementPage createAndActivate(InstanceManagementPageId destination) {
        PageFactory factory = Objects.requireNonNull(factories.get(destination), "page factory");
        InstanceManagementPage createdPage = Objects.requireNonNull(factory.create(), "created page");
        boolean installed = false;
        try {
            createdPage.component().setOpaque(false);
            add(createdPage.component(), destination.name());
            installed = true;
            cardLayout.show(this, destination.name());
            revalidate();
            createdPage.activate();
            loadedPages.put(destination, createdPage);
            creationOrder.add(destination);
            return createdPage;
        } catch (RuntimeException | Error failure) {
            if (installed) {
                remove(createdPage.component());
                restoreSelectedCard();
            }
            try {
                createdPage.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /// Resolves the component currently represented by [#selectedPage].
    ///
    /// @return visible loaded component, or null before initial navigation
    private @Nullable JComponent selectedComponent() {
        @Nullable InstanceManagementPageId currentPage = selectedPage;
        if (currentPage == null) {
            return null;
        }
        @Nullable InstanceManagementPage current = loadedPages.get(currentPage);
        return current == null ? null : current.component();
    }

    /// Restores the previously selected card after a failed first visit.
    private void restoreSelectedCard() {
        @Nullable InstanceManagementPageId previousPage = selectedPage;
        if (previousPage != null) {
            cardLayout.show(this, previousPage.name());
        }
        revalidate();
        repaint();
    }

    /// Releases loaded pages in reverse creation order and propagates the first cleanup failure.
    private void closeLoadedPages() {
        contentTransition.settle();
        @Nullable Throwable firstFailure = null;
        for (int index = creationOrder.size() - 1; index >= 0; index--) {
            InstanceManagementPageId pageId = creationOrder.get(index);
            @Nullable InstanceManagementPage page = loadedPages.remove(pageId);
            if (page == null) {
                continue;
            }
            try {
                page.close();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        creationOrder.clear();
        selectedPage = null;
        removeAll();
        revalidate();
        repaint();
        rethrow(firstFailure);
    }

    /// Copies and validates a non-empty page factory mapping.
    ///
    /// @param source caller-owned factory mapping
    /// @return validated mutable enum-map copy
    private static EnumMap<InstanceManagementPageId, PageFactory> copyFactories(
            Map<InstanceManagementPageId, PageFactory> source) {
        Map<InstanceManagementPageId, PageFactory> requiredSource =
                Objects.requireNonNull(source, "factories");
        EnumMap<InstanceManagementPageId, PageFactory> copy =
                new EnumMap<>(InstanceManagementPageId.class);
        requiredSource.forEach((page, factory) -> copy.put(
                Objects.requireNonNull(page, "factory page"),
                Objects.requireNonNull(factory, "page factory")));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("factories must not be empty");
        }
        return copy;
    }

    /// Validates that a destination is supported by this deck.
    ///
    /// @param page requested destination
    /// @return validated supported destination
    private InstanceManagementPageId requireAvailable(InstanceManagementPageId page) {
        InstanceManagementPageId destination = Objects.requireNonNull(page, "page");
        if (!factories.containsKey(destination)) {
            throw new IllegalArgumentException("Page is not available: " + destination);
        }
        return destination;
    }

    /// Rejects navigation after cleanup has begun.
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Instance management page deck is closed");
        }
    }

    /// Rethrows the first unchecked cleanup failure without changing its identity.
    ///
    /// @param failure first cleanup failure, or `null` after complete success
    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        if (failure != null) {
            throw new AssertionError("Unexpected checked page cleanup failure", failure);
        }
    }
}
