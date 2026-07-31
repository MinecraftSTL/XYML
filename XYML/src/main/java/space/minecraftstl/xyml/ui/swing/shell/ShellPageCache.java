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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/// Creates each top-level page at most once and retains it for later navigation.
///
/// @param <T> the toolkit-specific page representation
@NotNullByDefault
public final class ShellPageCache<T> implements AutoCloseable {
    /// Defensive copy of the complete caller-provided factory set.
    private final EnumMap<ShellPageId, ShellPageFactory<? extends T>> factories =
            new EnumMap<>(ShellPageId.class);

    /// Pages already created during this application session.
    private final EnumMap<ShellPageId, T> pages = new EnumMap<>(ShellPageId.class);

    /// Whether this cache has released all created page resources.
    private boolean closed;

    /// Creates an empty lazy cache after validating all five page factories.
    ///
    /// @param pageFactories one factory for every top-level destination
    public ShellPageCache(Map<ShellPageId, ? extends ShellPageFactory<? extends T>> pageFactories) {
        Objects.requireNonNull(pageFactories);
        pageFactories.forEach((page, factory) -> factories.put(
                Objects.requireNonNull(page, "page factory key"),
                Objects.requireNonNull(factory, "page factory")));

        EnumSet<ShellPageId> missingPages = EnumSet.allOf(ShellPageId.class);
        missingPages.removeAll(factories.keySet());
        if (!missingPages.isEmpty()) {
            throw new IllegalArgumentException("Missing page factories: " + missingPages);
        }
    }

    /// Returns an existing page or invokes its factory exactly once.
    ///
    /// @param page the destination whose page is needed
    /// @return the cached or newly created page
    public T getOrCreate(ShellPageId page) {
        Objects.requireNonNull(page);
        if (closed) {
            throw new IllegalStateException("Shell page cache is closed");
        }
        return pages.computeIfAbsent(page, selectedPage -> Objects.requireNonNull(
                factories.get(selectedPage).createPage(),
                () -> "Page factory returned null for " + selectedPage));
    }

    /// Returns whether a destination page has already been created.
    ///
    /// @param page the destination to inspect
    /// @return `true` when its page is cached
    public boolean isCached(ShellPageId page) {
        return pages.containsKey(Objects.requireNonNull(page));
    }

    /// Returns the number of pages created so far.
    ///
    /// @return the cached page count
    public int cachedPageCount() {
        return pages.size();
    }

    /// Closes every created auto-closeable page exactly once and releases the cache.
    ///
    /// Non-closeable page values are simply discarded. All pages are visited even when one close operation
    /// fails; the first failure is rethrown after cleanup has been attempted.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        @Nullable Exception firstFailure = null;
        for (T page : pages.values()) {
            if (page instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                }
            }
        }
        pages.clear();
        if (firstFailure != null) {
            throw new IllegalStateException("Failed to close one or more shell pages", firstFailure);
        }
    }
}
