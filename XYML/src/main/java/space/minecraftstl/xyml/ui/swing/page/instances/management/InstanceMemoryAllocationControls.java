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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.util.platform.SystemInfo;
import space.minecraftstl.xyml.util.platform.hardware.PhysicalMemoryStatus;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Restores the manual heap slider and live physical-memory summary from the legacy game-settings editor.
///
/// Hardware polling runs on the shared I/O scheduler. The Swing timer only requests a refresh while the component is
/// visible, and every published value returns to the event dispatch thread before changing UI state.
@NotNullByDefault
final class InstanceMemoryAllocationControls {
    /// Number of bytes in one mebibyte.
    private static final long BYTES_PER_MIB = 1_024L * 1_024L;

    /// Number of bytes in one gibibyte.
    private static final long BYTES_PER_GIB = BYTES_PER_MIB * 1_024L;

    /// Largest value accepted by the corresponding instance-settings field.
    private static final int MAXIMUM_MEMORY_MIB = 1_048_576;

    /// Physical-memory refresh interval matching the legacy status control.
    private static final int REFRESH_INTERVAL_MILLIS = 3_000;

    /// Selector that resolves inherited, automatic, and manual allocation modes.
    private final InstanceMemoryModeSelector memoryModeSelector;

    /// Manual heap editor synchronized with the slider.
    private final JTextField maximumMemoryEditor;

    /// Manual heap slider bounded by detected physical memory and the current persisted value.
    private final JSlider maximumMemorySlider = new JSlider();

    /// Visual used-memory and requested-allocation summary.
    private final MemoryAllocationBar memoryBar = new MemoryAllocationBar();

    /// Localized physical-memory summary.
    private final JLabel physicalMemoryLabel = new JLabel();

    /// Localized requested-allocation summary.
    private final JLabel allocatedMemoryLabel = new JLabel();

    /// Transparent component inserted below the manual memory row.
    private final JPanel component = new JPanel(new MigLayout(
            "insets 2 0 0 0, fillx, wrap 1",
            "[grow,fill]",
            "[]6[]4[]"));

    /// Background physical-memory supplier.
    private final Supplier<PhysicalMemoryStatus> memoryStatusSupplier;

    /// Background executor used for hardware status polling.
    private final Executor executor;

    /// Repeating visible-only refresh trigger.
    private final Timer refreshTimer = new Timer(REFRESH_INTERVAL_MILLIS, event -> requestMemoryStatus());

    /// Prevents overlapping hardware status requests.
    private final AtomicBoolean refreshPending = new AtomicBoolean();

    /// Most recently published physical-memory status.
    private PhysicalMemoryStatus memoryStatus = PhysicalMemoryStatus.INVALID;

    /// Detected physical-memory upper bound in MiB.
    private int detectedMaximumMemoryMiB;

    /// Prevents reciprocal text and slider listeners from looping.
    private boolean synchronizingEditors;

    /// Effective inherited automatic-allocation value used while the inheritance choice is selected.
    private boolean inheritedAutomatic = true;

    /// Creates production memory controls using the process-wide hardware detector.
    ///
    /// @param memoryModeSelector memory mode selector
    /// @param maximumMemoryEditor manual heap text field
    InstanceMemoryAllocationControls(
            InstanceMemoryModeSelector memoryModeSelector,
            JTextField maximumMemoryEditor) {
        this(
                memoryModeSelector,
                maximumMemoryEditor,
                SystemInfo::getPhysicalMemoryStatus,
                Schedulers.io());
    }

    /// Creates memory controls with explicit polling dependencies for deterministic tests.
    ///
    /// @param memoryModeSelector memory mode selector
    /// @param maximumMemoryEditor manual heap text field
    /// @param memoryStatusSupplier physical-memory snapshot supplier
    /// @param executor executor used to invoke the supplier
    InstanceMemoryAllocationControls(
            InstanceMemoryModeSelector memoryModeSelector,
            JTextField maximumMemoryEditor,
            Supplier<PhysicalMemoryStatus> memoryStatusSupplier,
            Executor executor) {
        this.memoryModeSelector = Objects.requireNonNull(memoryModeSelector, "memoryModeSelector");
        this.maximumMemoryEditor = Objects.requireNonNull(maximumMemoryEditor, "maximumMemoryEditor");
        this.memoryStatusSupplier = Objects.requireNonNull(memoryStatusSupplier, "memoryStatusSupplier");
        this.executor = Objects.requireNonNull(executor, "executor");
        configureComponents();
        configureInteractions();
        synchronizeSliderFromText();
        updateMemorySummary();
    }

    /// Returns the transparent memory status component.
    ///
    /// @return component inserted beneath the memory editors
    JComponent component() {
        return component;
    }

    /// Applies the inherited allocation mode and refreshes the displayed effective allocation.
    ///
    /// @param automatic whether the inherited preset allocates memory automatically
    void applyInheritedAutomatic(boolean automatic) {
        inheritedAutomatic = automatic;
        updateMemorySummary();
    }

    /// Requests one background status refresh unless another request is already active.
    void requestMemoryStatus() {
        if (!refreshPending.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(memoryStatusSupplier, executor)
                .whenComplete((@Nullable PhysicalMemoryStatus status, @Nullable Throwable failure) ->
                        SwingUiDispatcher.INSTANCE.dispatch(() -> completeMemoryStatus(status, failure)));
    }

    /// Builds the transparent slider, segmented status bar, and aligned labels.
    private void configureComponents() {
        component.setName("instanceGameSettingsMemoryStatus");
        component.setOpaque(false);

        maximumMemorySlider.setName("instanceGameSettingsMaximumMemorySlider");
        maximumMemorySlider.setMinimum(0);
        maximumMemorySlider.setPaintTicks(false);
        maximumMemorySlider.setPaintLabels(false);
        component.add(maximumMemorySlider, "growx");

        memoryBar.setName("instanceGameSettingsMemoryBar");
        component.add(memoryBar, "growx");

        JPanel labels = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][grow,fill]", "[]"));
        labels.setOpaque(false);
        physicalMemoryLabel.setName("instanceGameSettingsPhysicalMemory");
        allocatedMemoryLabel.setName("instanceGameSettingsAllocatedMemory");
        labels.add(physicalMemoryLabel, "alignx left");
        labels.add(allocatedMemoryLabel, "alignx right");
        component.add(labels, "growx");
    }

    /// Connects editor synchronization, dependent enablement, and visible-only polling.
    private void configureInteractions() {
        maximumMemoryEditor.getDocument().addDocumentListener(new DocumentListener() {
            /// Synchronizes after text insertion.
            @Override
            public void insertUpdate(DocumentEvent event) {
                maximumMemoryTextChanged();
            }

            /// Synchronizes after text removal.
            @Override
            public void removeUpdate(DocumentEvent event) {
                maximumMemoryTextChanged();
            }

            /// Synchronizes after styled-document attribute changes.
            @Override
            public void changedUpdate(DocumentEvent event) {
                maximumMemoryTextChanged();
            }
        });
        maximumMemorySlider.addChangeListener(event -> synchronizeTextFromSlider());
        maximumMemoryEditor.addPropertyChangeListener("enabled", event -> updateSliderAvailability());
        memoryModeSelector.addSelectionListener(this::updateMemorySummary);
        component.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }
            if (component.isShowing()) {
                requestMemoryStatus();
                refreshTimer.start();
            } else {
                refreshTimer.stop();
            }
        });
        updateSliderAvailability();
    }

    /// Synchronizes derived controls after a manual heap text edit.
    private void maximumMemoryTextChanged() {
        synchronizeSliderFromText();
        updateMemorySummary();
    }

    /// Mirrors one valid manual heap value into the slider without altering invalid drafts.
    private void synchronizeSliderFromText() {
        if (synchronizingEditors) {
            return;
        }
        @Nullable Integer value = parseMaximumMemory();
        if (value == null) {
            return;
        }
        synchronizingEditors = true;
        try {
            int boundedValue = Math.min(value, MAXIMUM_MEMORY_MIB);
            ensureSliderMaximum(boundedValue);
            maximumMemorySlider.setValue(boundedValue);
        } finally {
            synchronizingEditors = false;
        }
    }

    /// Mirrors slider interaction into the persisted manual heap field.
    private void synchronizeTextFromSlider() {
        if (synchronizingEditors || !maximumMemorySlider.isEnabled()) {
            return;
        }
        synchronizingEditors = true;
        try {
            maximumMemoryEditor.setText(Integer.toString(maximumMemorySlider.getValue()));
        } finally {
            synchronizingEditors = false;
        }
        updateMemorySummary();
    }

    /// Applies a completed physical-memory snapshot on the event dispatch thread.
    ///
    /// @param status detected status, or null when the supplier failed contractually
    /// @param failure polling failure, or null on success
    private void completeMemoryStatus(
            @Nullable PhysicalMemoryStatus status,
            @Nullable Throwable failure) {
        refreshPending.set(false);
        if (failure != null || status == null) {
            return;
        }
        memoryStatus = status;
        int totalMiB = clampMemoryMiB(status.total() / BYTES_PER_MIB);
        detectedMaximumMemoryMiB = totalMiB;
        ensureSliderMaximum(Math.max(totalMiB, parsedMaximumOrFallback()));
        updateMemorySummary();
    }

    /// Updates localized memory labels and the segmented bar from current editors.
    private void updateMemorySummary() {
        boolean automatic = memoryModeSelector.isInherited()
                ? inheritedAutomatic
                : memoryModeSelector.isAutomatic();
        long allocated = automatic
                ? automaticAllocation()
                : (long) parsedMaximumOrFallback() * BYTES_PER_MIB;
        memoryBar.setMemory(memoryStatus, allocated);

        physicalMemoryLabel.setText(i18n(
                "settings.memory.used_per_total",
                toGibibytes(memoryStatus.getUsed()),
                toGibibytes(memoryStatus.total())));
        allocatedMemoryLabel.setText(memoryStatus.hasAvailable() && allocated > memoryStatus.available()
                ? i18n(
                        "settings.memory.allocate.exceeded",
                        toGibibytes(allocated),
                        toGibibytes(memoryStatus.available()))
                : i18n("settings.memory.allocate", toGibibytes(allocated)));
    }

    /// Keeps the slider range large enough for physical memory and an existing custom value.
    ///
    /// @param requiredMaximum minimum required upper bound in MiB
    private void ensureSliderMaximum(int requiredMaximum) {
        int desiredMaximum = Math.max(requiredMaximum, detectedMaximumMemoryMiB);
        int boundedMaximum = Math.max(1, Math.min(desiredMaximum, MAXIMUM_MEMORY_MIB));
        if (maximumMemorySlider.getMaximum() != boundedMaximum) {
            maximumMemorySlider.setMaximum(boundedMaximum);
        }
    }

    /// Mirrors the authoritative text-editor availability onto the secondary slider.
    private void updateSliderAvailability() {
        maximumMemorySlider.setEnabled(maximumMemoryEditor.isEnabled());
    }

    /// Parses a valid non-negative manual heap value.
    ///
    /// @return parsed MiB value, or null for an invalid draft
    private @Nullable Integer parseMaximumMemory() {
        try {
            int value = Integer.parseInt(maximumMemoryEditor.getText().trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /// Returns the current manual value or the launcher's durable fallback.
    ///
    /// @return non-negative heap allocation in MiB
    private int parsedMaximumOrFallback() {
        @Nullable Integer parsed = parseMaximumMemory();
        return parsed != null && parsed > 0 ? parsed : GameSettings.SUGGESTED_MEMORY;
    }

    /// Returns automatic allocation for a valid status or the durable fallback while detection is unavailable.
    ///
    /// @return automatic allocation in bytes
    private long automaticAllocation() {
        return memoryStatus.hasAvailable()
                ? XYMLGameRepository.getAutoAllocatedMemory(memoryStatus.available())
                : (long) GameSettings.SUGGESTED_MEMORY * BYTES_PER_MIB;
    }

    /// Converts a positive byte count to the bounded slider representation.
    ///
    /// @param bytesInMiB byte count already converted to MiB
    /// @return bounded MiB count
    private static int clampMemoryMiB(long bytesInMiB) {
        return (int) Math.max(0L, Math.min(bytesInMiB, MAXIMUM_MEMORY_MIB));
    }

    /// Converts bytes to the localized GiB display unit.
    ///
    /// @param bytes byte count
    /// @return gibibyte value
    private static double toGibibytes(long bytes) {
        return Math.max(0L, bytes) / (double) BYTES_PER_GIB;
    }

    /// Paints physical usage and requested game allocation as two bounded segments.
    @NotNullByDefault
    private static final class MemoryAllocationBar extends JComponent {
        /// Current physical-memory snapshot.
        private PhysicalMemoryStatus status = PhysicalMemoryStatus.INVALID;

        /// Requested game allocation in bytes.
        private long allocated;

        /// Creates a compact transparent memory summary bar.
        private MemoryAllocationBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(200, 6));
            setMinimumSize(new Dimension(40, 6));
        }

        /// Replaces the represented memory values and repaints the bar.
        ///
        /// @param status physical-memory snapshot
        /// @param allocated requested game allocation in bytes
        private void setMemory(PhysicalMemoryStatus status, long allocated) {
            this.status = Objects.requireNonNull(status, "status");
            this.allocated = Math.max(0L, allocated);
            repaint();
        }

        /// Paints a rounded track, requested-allocation segment, and current-use segment.
        ///
        /// @param graphics Swing graphics context
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                int arc = Math.max(1, height);
                RoundRectangle2D track = new RoundRectangle2D.Double(0, 0, width, height, arc, arc);
                copy.setColor(uiColor("ProgressBar.background", new Color(0x808080)));
                copy.fill(track);
                copy.clip(track);

                if (status.total() <= 0L) {
                    return;
                }
                int usedWidth = scaledWidth(status.getUsed(), status.total(), width);
                int allocationEnd = scaledWidth(status.getUsed() + allocated, status.total(), width);
                copy.setColor(uiColor("ProgressBar.foreground", new Color(0x4A90E2)));
                copy.fillRect(usedWidth, 0, Math.max(0, allocationEnd - usedWidth), height);
                copy.setColor(uiColor("Label.disabledForeground", new Color(0x606060)));
                copy.fillRect(0, 0, usedWidth, height);
            } finally {
                copy.dispose();
            }
        }

        /// Converts one byte count into a bounded bar width.
        ///
        /// @param value represented byte count
        /// @param total total byte count
        /// @param width available logical width
        /// @return bounded logical width
        private static int scaledWidth(long value, long total, int width) {
            double ratio = Math.max(0.0D, Math.min(1.0D, value / (double) total));
            return (int) Math.round(width * ratio);
        }

        /// Returns a look-and-feel color with a stable fallback.
        ///
        /// @param key UI defaults key
        /// @param fallback fallback color
        /// @return resolved color
        private static Color uiColor(String key, Color fallback) {
            @Nullable Color color = UIManager.getColor(Objects.requireNonNull(key, "key"));
            return color != null ? color : Objects.requireNonNull(fallback, "fallback");
        }
    }
}
