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
