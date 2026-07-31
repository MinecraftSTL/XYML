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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.util.CircularArrayList;
import space.minecraftstl.xyml.util.Log4jLevel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded retention, filter bookkeeping, and immutable export snapshots independently of Swing.
@NotNullByDefault
class BoundedGameLogBufferTest {
    /// Evicts leading rows while preserving append order and cumulative severity counts.
    @Test
    void batchAppendIsBoundedAndReportsVisibleHeadRemoval() {
        CircularArrayList<Log> sharedLogs = new CircularArrayList<>();
        BoundedGameLogBuffer buffer = new BoundedGameLogBuffer(sharedLogs, 3);
        List<Log> visibleAppends = new ArrayList<>();
        Log first = log("first", Log4jLevel.INFO);
        Log second = log("second", Log4jLevel.ERROR);
        Log third = log("third", Log4jLevel.WARN);
        Log fourth = log("fourth", Log4jLevel.DEBUG);

        int removed = buffer.appendAll(List.of(first, second, third, fourth), visibleAppends::add);

        assertEquals(1, removed);
        assertEquals(List.of(first, second, third, fourth), visibleAppends);
        assertEquals(List.of(second, third, fourth), buffer.snapshot());
        assertEquals(List.of(second, third, fourth), List.copyOf(sharedLogs));
        assertEquals(1, buffer.levelCount(Log4jLevel.INFO));
        assertEquals(1, buffer.levelCount(Log4jLevel.ERROR));
    }

    /// Counts a visible eviction correctly when the newly retained row is hidden by a severity filter.
    @Test
    void hiddenAppendCanEvictVisibleHeadWithoutCreatingVisibleRow() {
        BoundedGameLogBuffer buffer = new BoundedGameLogBuffer(new CircularArrayList<>(), 1);
        List<Log> visibleAppends = new ArrayList<>();
        buffer.setLevelShown(Log4jLevel.ERROR, false);

        int removed = buffer.appendAll(
                List.of(log("visible", Log4jLevel.INFO), log("hidden", Log4jLevel.ERROR)),
                visibleAppends::add);

        assertEquals(1, removed);
        assertEquals(List.of("visible"), visibleAppends.stream().map(Log::getLog).toList());
        assertTrue(buffer.visibleSnapshot().isEmpty());
        assertEquals(List.of("hidden"), buffer.textSnapshot());
        assertFalse(buffer.isLevelShown(Log4jLevel.ERROR));
    }

    /// Trims existing shared history at construction and when the user lowers the line limit.
    @Test
    void existingHistoryAndChangedLimitUseSameBoundedStorage() {
        CircularArrayList<Log> sharedLogs = new CircularArrayList<>();
        sharedLogs.addLast(log("one", Log4jLevel.INFO));
        sharedLogs.addLast(log("two", Log4jLevel.INFO));
        sharedLogs.addLast(log("three", Log4jLevel.INFO));
        BoundedGameLogBuffer buffer = new BoundedGameLogBuffer(sharedLogs, 2);

        int removed = buffer.setMaxLines(1);

        assertEquals(1, removed);
        assertEquals(1, buffer.maxLines());
        assertEquals(List.of("three"), buffer.textSnapshot());
        assertEquals(3, buffer.levelCount(Log4jLevel.INFO));
    }

    /// Clears retained rows without resetting cumulative counts and exposes immutable snapshots.
    @Test
    void clearPreservesSessionCountsAndSnapshotsAreImmutable() {
        BoundedGameLogBuffer buffer = new BoundedGameLogBuffer(new CircularArrayList<>(), 2);
        buffer.append(log("entry", Log4jLevel.FATAL), ignored -> {
        });
        @Unmodifiable List<Log> snapshot = buffer.snapshot();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(log("other", Log4jLevel.INFO)));

        buffer.clear();

        assertEquals(0, buffer.size());
        assertEquals(1, buffer.levelCount(Log4jLevel.FATAL));
    }

    /// Rejects a non-positive memory bound.
    @Test
    void nonPositiveLimitIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundedGameLogBuffer(new CircularArrayList<>(), 0));
    }

    /// Creates one explicitly leveled test entry.
    ///
    /// @param text entry text
    /// @param level entry severity
    /// @return test log entry
    private static Log log(String text, Log4jLevel level) {
        return new Log(text, level);
    }
}
