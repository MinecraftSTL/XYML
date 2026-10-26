// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;

/// Tracks the cumulative mean of accepted data-source request latencies.
@NotNullByDefault
public final class LoadLatencyTracker {
    /// The number of accepted latency observations.
    private long sampleCount;

    /// The cumulative mean latency in nanoseconds.
    private double averageNanos;

    /// Creates an empty latency tracker with no assumed initial latency.
    public LoadLatencyTracker() {
    }

    /// Records one non-negative request duration.
    ///
    /// @param latency the measured request duration
    public synchronized void record(Duration latency) {
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
        long nanos = latency.toNanos();
        sampleCount++;
        averageNanos += (nanos - averageNanos) / sampleCount;
    }

    /// Returns the measured cumulative mean, or zero before the first observation.
    ///
    /// @return the measured load latency
    public synchronized Duration observedLatency() {
        return Duration.ofNanos((long) Math.ceil(averageNanos));
    }

    /// Returns the number of accepted latency observations.
    ///
    /// @return the latency sample count
    public synchronized long sampleCount() {
        return sampleCount;
    }
}
