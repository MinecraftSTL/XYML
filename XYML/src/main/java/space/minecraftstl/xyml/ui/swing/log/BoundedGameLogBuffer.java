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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.util.CircularArrayList;
import space.minecraftstl.xyml.util.Log4jLevel;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/// Owns the bounded log history, severity filters, and per-session severity counts for one game process.
///
/// The caller confines mutations to one UI thread. The supplied history remains the authoritative list so crash
/// diagnostics can continue to consume the same bounded entries after the visible window is closed.
@NotNullByDefault
final class BoundedGameLogBuffer {
    /// Shared mutable history retained for crash diagnostics.
    private final CircularArrayList<Log> logs;

    /// Severity levels currently visible in the log list.
    private final EnumSet<Log4jLevel> shownLevels = EnumSet.allOf(Log4jLevel.class);

    /// Number of entries observed for each severity during this window session.
    private final Map<Log4jLevel, Integer> levelCounts = new EnumMap<>(Log4jLevel.class);

    /// Maximum number of entries retained in memory.
    private int maxLines;

    /// Creates a buffer around the launch listener's shared history.
    ///
    /// Existing entries are counted and any entries beyond the supplied limit are removed from the head.
    ///
    /// @param logs shared mutable log history
    /// @param maxLines positive maximum retained entry count
    BoundedGameLogBuffer(CircularArrayList<Log> logs, int maxLines) {
        this.logs = Objects.requireNonNull(logs, "logs");
        this.maxLines = requirePositiveLimit(maxLines);
        for (Log4jLevel level : Log4jLevel.values()) {
            levelCounts.put(level, 0);
        }
        for (Log log : logs) {
            incrementCount(log.getLevel());
        }
        trimToLimit();
    }

    /// Appends a single entry and reports visible additions to the caller before head trimming is applied.
    ///
    /// @param log entry to retain
    /// @param visibleAppender callback receiving the entry when its severity is currently shown
    /// @return number of visible entries removed from the head to enforce the limit
    int append(Log log, Consumer<Log> visibleAppender) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(visibleAppender, "visibleAppender");

        logs.addLast(log);
        Log4jLevel level = log.getLevel();
        incrementCount(level);
        if (shownLevels.contains(level)) {
            visibleAppender.accept(log);
        }
        return trimToLimit();
    }

    /// Appends a batch in source order and reports visible additions before head trimming is applied.
    ///
    /// The callback ordering lets a Swing list append the batch and then remove the returned number of leading rows,
    /// including the correct result when a batch itself is larger than the configured capacity.
    ///
    /// @param additions entries to retain in source order
    /// @param visibleAppender callback receiving entries whose severities are currently shown
    /// @return number of visible entries removed from the head to enforce the limit
    int appendAll(List<Log> additions, Consumer<Log> visibleAppender) {
        Objects.requireNonNull(additions, "additions");
        Objects.requireNonNull(visibleAppender, "visibleAppender");

        for (Log log : additions) {
            Log entry = Objects.requireNonNull(log, "additions contains null");
            logs.addLast(entry);
            Log4jLevel level = entry.getLevel();
            incrementCount(level);
            if (shownLevels.contains(level)) {
                visibleAppender.accept(entry);
            }
        }
        return trimToLimit();
    }

    /// Changes the retention limit and immediately removes excess leading entries.
    ///
    /// @param maxLines positive maximum retained entry count
    /// @return number of currently visible entries removed from the head
    int setMaxLines(int maxLines) {
        this.maxLines = requirePositiveLimit(maxLines);
        return trimToLimit();
    }

    /// Returns the active retention limit.
    ///
    /// @return positive maximum retained entry count
    int maxLines() {
        return maxLines;
    }

    /// Enables or disables one severity in visible snapshots and subsequent append callbacks.
    ///
    /// @param level severity to update
    /// @param shown whether entries at that severity should be visible
    void setLevelShown(Log4jLevel level, boolean shown) {
        Objects.requireNonNull(level, "level");
        if (shown) {
            shownLevels.add(level);
        } else {
            shownLevels.remove(level);
        }
    }

    /// Reports whether one severity is currently visible.
    ///
    /// @param level severity to query
    /// @return true when entries at that severity are shown
    boolean isLevelShown(Log4jLevel level) {
        return shownLevels.contains(Objects.requireNonNull(level, "level"));
    }

    /// Returns a stable immutable snapshot containing only currently visible entries.
    ///
    /// @return filtered entries in source order
    @Unmodifiable List<Log> visibleSnapshot() {
        return logs.stream()
                .filter(log -> shownLevels.contains(log.getLevel()))
                .toList();
    }

    /// Returns a stable immutable snapshot of every retained entry.
    ///
    /// @return retained entries in source order
    @Unmodifiable List<Log> snapshot() {
        return List.copyOf(logs);
    }

    /// Returns a stable immutable snapshot of the text written by log export.
    ///
    /// @return retained entry text in source order
    @Unmodifiable List<String> textSnapshot() {
        return logs.stream().map(Log::getLog).toList();
    }

    /// Returns the number of retained entries.
    ///
    /// @return current bounded history size
    int size() {
        return logs.size();
    }

    /// Returns the number of entries observed at one severity during this session.
    ///
    /// Counts intentionally survive head trimming and manual clearing for stable session totals.
    ///
    /// @param level severity to query
    /// @return observed entry count
    int levelCount(Log4jLevel level) {
        return levelCounts.get(Objects.requireNonNull(level, "level"));
    }

    /// Removes every retained entry while preserving session severity counters.
    void clear() {
        logs.clear();
    }

    /// Removes excess leading entries and counts how many were currently visible.
    ///
    /// @return number of visible entries removed from the head
    private int trimToLimit() {
        int visibleRemovals = 0;
        while (logs.size() > maxLines) {
            Log removed = logs.removeFirst();
            if (shownLevels.contains(removed.getLevel())) {
                visibleRemovals++;
            }
        }
        return visibleRemovals;
    }

    /// Increments the session counter for one severity.
    ///
    /// @param level severity observed on an appended entry
    private void incrementCount(Log4jLevel level) {
        levelCounts.compute(
                Objects.requireNonNull(level, "level"),
                (Log4jLevel ignored, @Nullable Integer count) -> count == null ? 1 : count + 1);
    }

    /// Validates a requested retention limit.
    ///
    /// @param maxLines requested maximum retained entry count
    /// @return the validated positive value
    private static int requirePositiveLimit(int maxLines) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        return maxLines;
    }
}
