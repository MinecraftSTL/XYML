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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingContentTransition;

import javax.swing.JPanel;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies first-visit construction, one-time activation, failure rollback, and deterministic cleanup.
@NotNullByDefault
final class InstanceManagementPageDeckTest {
    /// Only selected destinations are created, while revisiting preserves components and reruns idempotent activation.
    @Test
    void createsAndActivatesEachPageOnlyOnFirstVisit() {
        EdtDispatcher.executeAndWait(() -> {
            AtomicInteger overviewCreations = new AtomicInteger();
            AtomicInteger overviewActivations = new AtomicInteger();
            AtomicInteger modCreations = new AtomicInteger();
            AtomicInteger modActivations = new AtomicInteger();
            InstanceManagementPageDeck deck = new InstanceManagementPageDeck(Map.of(
                    InstanceManagementPageId.OVERVIEW,
                    countingPage(overviewCreations, overviewActivations, new AtomicInteger()),
                    InstanceManagementPageId.MODS,
                    countingPage(modCreations, modActivations, new AtomicInteger())));

            assertEquals(List.of(
                    InstanceManagementPageId.OVERVIEW,
                    InstanceManagementPageId.MODS), deck.availablePages());
            assertEquals(new Dimension(0, 0), deck.getMinimumSize());
            assertFalse(deck.isOpaque());
            assertInstanceOf(CardLayout.class, deck.getLayout());
            assertNull(deck.selectedPage());
            assertEquals(0, deck.loadedPageCount());

            deck.showPage(InstanceManagementPageId.OVERVIEW);
            deck.showPage(InstanceManagementPageId.MODS);
            deck.showPage(InstanceManagementPageId.OVERVIEW);

            assertEquals(1, overviewCreations.get());
            assertEquals(2, overviewActivations.get());
            assertEquals(1, modCreations.get());
            assertEquals(1, modActivations.get());
            assertEquals(2, deck.loadedPageCount());
            assertTrue(deck.isLoaded(InstanceManagementPageId.OVERVIEW));
            assertTrue(deck.isLoaded(InstanceManagementPageId.MODS));
            assertEquals(InstanceManagementPageId.OVERVIEW, deck.selectedPage());
        });
    }

    /// User-selected management destinations inherit the shared cached-frame transition context.
    @Test
    void animatesManagementDestinationChanges() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);

        EdtDispatcher.executeAndWait(() -> {
            InstanceManagementPageDeck deck = new InstanceManagementPageDeck(Map.of(
                    InstanceManagementPageId.OVERVIEW,
                    () -> InstanceManagementPage.passive(new JPanel(), () -> { }),
                    InstanceManagementPageId.MODS,
                    () -> InstanceManagementPage.passive(new JPanel(), () -> { })));
            SwingContentTransition.provideContext(deck, animator, Duration.ofSeconds(2L));
            deck.setSize(640, 480);
            deck.showPage(InstanceManagementPageId.OVERVIEW);
            deck.doLayout();

            deck.showPage(InstanceManagementPageId.MODS);

            assertEquals(InstanceManagementPageId.MODS, deck.selectedPage());
            assertEquals(
                    SwingContentTransition.Direction.VERTICAL_FORWARD,
                    deck.contentTransitionDirection());
            assertTrue(deck.isTransitionRunning());
            animator.setMotionPolicy(MotionPolicy.OFF);
            assertFalse(deck.isTransitionRunning());
            deck.close();
        });
    }

    /// Invalidating a cached directory-dependent page closes it and recreates it on the next visit.
    @Test
    void rebuildsInvalidatedNonVisiblePages() {
        EdtDispatcher.executeAndWait(() -> {
            AtomicInteger modCreations = new AtomicInteger();
            AtomicInteger modCleanups = new AtomicInteger();
            InstanceManagementPageDeck deck = new InstanceManagementPageDeck(Map.of(
                    InstanceManagementPageId.OVERVIEW,
                    () -> InstanceManagementPage.passive(new JPanel(), () -> { }),
                    InstanceManagementPageId.MODS,
                    countingPage(modCreations, new AtomicInteger(), modCleanups)));
            deck.showPage(InstanceManagementPageId.MODS);
            deck.showPage(InstanceManagementPageId.OVERVIEW);

            deck.invalidatePages(List.of(InstanceManagementPageId.MODS));

            assertFalse(deck.isLoaded(InstanceManagementPageId.MODS));
            assertEquals(1, modCleanups.get());
            deck.showPage(InstanceManagementPageId.MODS);
            assertEquals(2, modCreations.get());
            deck.close();
        });
    }

    /// Closing releases only loaded pages, in reverse creation order, and remains idempotent.
    @Test
    void closesOnlyLoadedPagesInReverseCreationOrder() {
        List<InstanceManagementPageId> cleanupOrder = new ArrayList<>();
        AtomicReference<@Nullable InstanceManagementPageDeck> deckReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            EnumMap<InstanceManagementPageId, InstanceManagementPageDeck.PageFactory> factories =
                    new EnumMap<>(InstanceManagementPageId.class);
            factories.put(InstanceManagementPageId.OVERVIEW, () -> passivePage(
                    InstanceManagementPageId.OVERVIEW,
                    cleanupOrder));
            factories.put(InstanceManagementPageId.MODS, () -> passivePage(
                    InstanceManagementPageId.MODS,
                    cleanupOrder));
            factories.put(InstanceManagementPageId.WORLDS, () -> passivePage(
                    InstanceManagementPageId.WORLDS,
                    cleanupOrder));
            InstanceManagementPageDeck deck = new InstanceManagementPageDeck(factories);
            deckReference.set(deck);
            deck.showPage(InstanceManagementPageId.OVERVIEW);
            deck.showPage(InstanceManagementPageId.MODS);
        });

        InstanceManagementPageDeck deck = Objects.requireNonNull(deckReference.get());
        deck.close();
        deck.close();

        assertEquals(List.of(
                InstanceManagementPageId.MODS,
                InstanceManagementPageId.OVERVIEW), cleanupOrder);
        EdtDispatcher.executeAndWait(() -> {
            assertEquals(0, deck.getComponentCount());
            assertEquals(0, deck.loadedPageCount());
            assertNull(deck.selectedPage());
            assertThrows(
                    IllegalStateException.class,
                    () -> deck.showPage(InstanceManagementPageId.WORLDS));
        });
    }

    /// An activation failure closes and removes the incomplete page while retaining the previous selection.
    @Test
    void rollsBackFailedFirstActivation() {
        EdtDispatcher.executeAndWait(() -> {
            AtomicInteger failedCleanup = new AtomicInteger();
            InstanceManagementPageDeck deck = new InstanceManagementPageDeck(Map.of(
                    InstanceManagementPageId.OVERVIEW,
                    () -> InstanceManagementPage.passive(new JPanel(), () -> { }),
                    InstanceManagementPageId.MODS,
                    () -> new InstanceManagementPage(
                            new JPanel(),
                            () -> {
                                throw new IllegalStateException("activation failed");
                            },
                            failedCleanup::incrementAndGet)));
            deck.showPage(InstanceManagementPageId.OVERVIEW);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> deck.showPage(InstanceManagementPageId.MODS));

            assertEquals("activation failed", failure.getMessage());
            assertEquals(1, failedCleanup.get());
            assertEquals(1, deck.loadedPageCount());
            assertFalse(deck.isLoaded(InstanceManagementPageId.MODS));
            assertEquals(InstanceManagementPageId.OVERVIEW, deck.selectedPage());
            assertEquals(1, deck.getComponentCount());
            deck.close();
        });
    }

    /// Creates a page factory whose counters expose construction and first activation.
    ///
    /// @param creations construction counter
    /// @param activations activation counter
    /// @param cleanups cleanup counter
    /// @return fresh counted page factory
    private static InstanceManagementPageDeck.PageFactory countingPage(
            AtomicInteger creations,
            AtomicInteger activations,
            AtomicInteger cleanups) {
        return () -> {
            creations.incrementAndGet();
            return new InstanceManagementPage(
                    new JPanel(),
                    activations::incrementAndGet,
                    cleanups::incrementAndGet);
        };
    }

    /// Creates a passive page that records its destination during cleanup.
    ///
    /// @param page represented destination
    /// @param cleanupOrder mutable cleanup observation list
    /// @return freshly owned passive page
    private static InstanceManagementPage passivePage(
            InstanceManagementPageId page,
            List<InstanceManagementPageId> cleanupOrder) {
        return InstanceManagementPage.passive(
                new JPanel(),
                () -> cleanupOrder.add(page));
    }
}
