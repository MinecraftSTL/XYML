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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/// A thin Swing single-choice list that loads only viewport-driven source ranges.
///
/// The component measures its current viewport and the reusable renderer row, then delegates all
/// range planning and asynchronous loading to independently testable classes.
///
/// @param <T> the non-null choice value type
@NotNullByDefault
public final class ViewportChoiceList<T extends Object> extends JScrollPane implements AutoCloseable {
    /// The sparse asynchronous list model.
    private final ViewportChoiceListModel<T> choiceModel;

    /// The JList configured for exactly one selected row.
    private final JList<ChoiceListEntry<T>> list;

    /// The one renderer instance reused for all painted rows.
    private final ListCellRenderer<ChoiceListEntry<T>> renderer;

    /// The stateless viewport load-window strategy.
    private final ViewportLoadStrategy loadStrategy;

    /// The listener that refreshes demand after scrolling or viewport resizing.
    private final ChangeListener viewportListener = event -> refreshLoadPlan();

    /// The listener that keeps changed selection and keyboard-focus indexes pinned.
    private final ListSelectionListener selectionListener = event -> {
        if (!event.getValueIsAdjusting()) {
            refreshLoadPlan();
        }
    };

    /// The scroll offset used for the previous speed observation.
    private int previousScrollOffsetPixels;

    /// The monotonic time used for the previous speed observation.
    private long previousObservationNanos;

    /// Whether a viewport refresh is already in progress.
    private boolean refreshing;

    /// Whether this component has released its load resources.
    private boolean closed;

    /// Creates a viewport-driven single-choice list.
    ///
    /// @param dataSource the indexed choice data source
    /// @param textProvider the provider of localized loaded-row labels
    public ViewportChoiceList(
            ViewportChoiceDataSource<T> dataSource,
            ChoiceTextProvider<T> textProvider) {
        this(dataSource, new ChoiceEntryRenderer<>(textProvider));
    }

    /// Creates a viewport-driven single-choice list with a custom reusable renderer.
    ///
    /// The renderer must return a component with a stable positive preferred height for loading,
    /// loaded, and failed entries. The list measures that renderer rather than assuming a fixed
    /// row count, so viewport demand continues to follow the current look and feel and scale.
    ///
    /// @param dataSource the indexed choice data source
    /// @param renderer the renderer used for every sparse row state
    public ViewportChoiceList(
            ViewportChoiceDataSource<T> dataSource,
            ListCellRenderer<ChoiceListEntry<T>> renderer) {
        choiceModel = new ViewportChoiceListModel<>(dataSource);
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        loadStrategy = new ViewportLoadStrategy();
        list = new JList<>(choiceModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(this.renderer);
        setOpaque(false);
        getViewport().setOpaque(false);
        list.setOpaque(false);
        int rowHeight = measureRowHeight();
        setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        getVerticalScrollBar().setUnitIncrement(rowHeight);
        setMinimumSize(new Dimension(0, 0));
        list.addListSelectionListener(selectionListener);
        setViewportView(list);
        getViewport().addChangeListener(viewportListener);
    }

    /// Returns the configured Swing list for selection and accessibility integration.
    ///
    /// @return the single-selection JList
    public JList<ChoiceListEntry<T>> getList() {
        return list;
    }

    /// Returns the sparse asynchronous choice model.
    ///
    /// @return the viewport-backed model
    public ViewportChoiceListModel<T> getChoiceModel() {
        return choiceModel;
    }

    /// Returns the loaded value selected by the user.
    ///
    /// @return the loaded selected value, or `null` for no selection or a placeholder row
    public @Nullable T getSelectedValue() {
        int selectedIndex = list.getSelectedIndex();
        return selectedIndex < 0 ? null : choiceModel.loadedValueAt(selectedIndex);
    }

    /// Re-measures row geometry after a font, scale, renderer, or look-and-feel change.
    public void invalidateRowMeasurement() {
        requireEventDispatchThread();
        list.setFixedCellHeight(-1);
        measureRowHeight();
        revalidate();
        refreshLoadPlan();
    }

    /// Retries failed source ranges for the current viewport.
    public void retryFailedLoads() {
        requireEventDispatchThread();
        choiceModel.retry();
    }

    /// Cancels stale work, clears sparse cached values, and reloads the current measured viewport.
    ///
    /// Use this after the source contents or exact item count change. The replacement request is still derived
    /// from measured viewport geometry and observed latency rather than a fixed page size.
    public void reloadData() {
        requireEventDispatchThread();
        choiceModel.invalidateData();
        previousObservationNanos = 0L;
        refreshLoadPlan();
    }

    /// Re-measures the viewport and applies its resulting demand.
    public void refreshLoadPlan() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshLoadPlan);
            return;
        }
        if (closed || refreshing) {
            return;
        }
        Dimension extent = getViewport().getExtentSize();
        if (extent.height <= 0) {
            return;
        }

        refreshing = true;
        try {
            int rowHeight = measureRowHeight();
            Point viewPosition = getViewport().getViewPosition();
            int scrollOffset = Math.max(0, viewPosition.y);
            int firstVisibleIndex = findFirstVisibleIndex(scrollOffset, rowHeight);
            int leadingClip = findLeadingClip(firstVisibleIndex, scrollOffset, rowHeight);

            long now = System.nanoTime();
            int previousOffset = previousObservationNanos == 0L
                    ? scrollOffset
                    : previousScrollOffsetPixels;
            Duration elapsed = previousObservationNanos == 0L
                    ? Duration.ZERO
                    : Duration.ofNanos(Math.max(0L, now - previousObservationNanos));
            previousScrollOffsetPixels = scrollOffset;
            previousObservationNanos = now;

            @Unmodifiable Set<Integer> pinnedIndices = currentPinnedIndices();
            ViewportObservation observation = new ViewportObservation(
                    firstVisibleIndex,
                    leadingClip,
                    extent.height,
                    rowHeight,
                    scrollOffset,
                    previousOffset,
                    elapsed,
                    choiceModel.observedLoadLatency(),
                    choiceModel.exactItemCount(),
                    pinnedIndices);
            choiceModel.applyPlan(loadStrategy.plan(observation));
        } finally {
            refreshing = false;
        }
    }

    /// Schedules the initial viewport measurement after Swing completes layout.
    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(this::refreshLoadPlan);
    }

    /// Cancels active requests and detaches component listeners.
    @Override
    public void close() {
        requireEventDispatchThread();
        if (!closed) {
            closed = true;
            getViewport().removeChangeListener(viewportListener);
            list.removeListSelectionListener(selectionListener);
            choiceModel.close();
        }
    }

    /// Measures the actual row height from current cell geometry or the reusable renderer.
    ///
    /// @return the positive measured row height
    private int measureRowHeight() {
        int fixedHeight = list.getFixedCellHeight();
        if (fixedHeight > 0) {
            return fixedHeight;
        }

        Component sample = renderer.getListCellRendererComponent(
                list,
                ChoiceListEntry.loading(0),
                0,
                false,
                false);
        int measuredHeight = sample.getPreferredSize().height;
        if (measuredHeight <= 0) {
            throw new IllegalStateException("Choice renderer must have a positive preferred height");
        }
        list.setFixedCellHeight(measuredHeight);
        return measuredHeight;
    }

    /// Finds the first logical row intersecting the current viewport.
    ///
    /// @param scrollOffset the current vertical pixel offset
    /// @param rowHeight the measured fixed row height
    /// @return the first intersecting logical index
    private int findFirstVisibleIndex(int scrollOffset, int rowHeight) {
        if (choiceModel.getSize() == 0) {
            return 0;
        }
        int locatedIndex = list.locationToIndex(new Point(0, scrollOffset));
        if (locatedIndex >= 0) {
            return locatedIndex;
        }
        return scrollOffset / rowHeight;
    }

    /// Measures how much of the first visible row is clipped above the viewport.
    ///
    /// @param firstVisibleIndex the first intersecting logical index
    /// @param scrollOffset the current vertical pixel offset
    /// @param rowHeight the measured fixed row height
    /// @return a pixel count within the first visible row
    private int findLeadingClip(int firstVisibleIndex, int scrollOffset, int rowHeight) {
        @Nullable Rectangle bounds = choiceModel.getSize() == 0
                ? null
                : list.getCellBounds(firstVisibleIndex, firstVisibleIndex);
        long rowStart = bounds == null ? (long) firstVisibleIndex * rowHeight : bounds.y;
        long clippedPixels = (long) scrollOffset - rowStart;
        return (int) Math.max(0L, Math.min(rowHeight - 1L, clippedPixels));
    }

    /// Collects current selected and keyboard-focus indexes for cache pinning.
    ///
    /// @return immutable valid pin indexes
    private @Unmodifiable Set<Integer> currentPinnedIndices() {
        Set<Integer> pinned = new HashSet<>();
        int selectedIndex = list.getSelectedIndex();
        if (selectedIndex >= 0) {
            pinned.add(selectedIndex);
        }
        int focusedIndex = list.getLeadSelectionIndex();
        if (focusedIndex >= 0) {
            pinned.add(focusedIndex);
        }
        return Set.copyOf(pinned);
    }

    /// Enforces the Swing single-thread rule for synchronous component changes.
    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Viewport choice list must be changed on the Swing event dispatch thread");
        }
    }
}
