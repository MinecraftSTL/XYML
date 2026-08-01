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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;

/// Filters a shallow resource-pack path index while preserving exact viewport metadata loading.
///
/// Search never parses pack metadata. It matches the archive or directory name and its extensionless
/// form against the existing legacy query syntax. A visible viewport range is mapped into consecutive
/// source-index segments, so the delegate still resolves only paths needed by that viewport.
@NotNullByDefault
final class FilteredResourcePackCatalogDataSource
        implements ViewportChoiceDataSource<ResourcePackCatalogItem> {
    /// Underlying lazy catalog that resolves exact source-index ranges.
    private final ResourcePackCatalogModel delegate;

    /// Guards immutable index, query, and revision replacement.
    private final Object stateLock = new Object();

    /// Latest immutable complete and filtered path state.
    private FilterState state;

    /// Creates a filtered source from the delegate's current shallow index.
    ///
    /// @param delegate underlying lazy resource-pack catalog
    FilteredResourcePackCatalogDataSource(ResourcePackCatalogModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        @Unmodifiable List<Path> initialPaths = immutablePaths(delegate.indexedPaths());
        state = FilterState.create(initialPaths, "", 0L);
    }

    /// Replaces the complete shallow index after an underlying content revision.
    ///
    /// The local revision changes even when path identity is unchanged because enabled state or
    /// parsed metadata may have changed underneath the same path.
    ///
    /// @param paths immutable current source-order paths
    void replaceIndex(@Unmodifiable List<Path> paths) {
        @Unmodifiable List<Path> captured = immutablePaths(paths);
        synchronized (stateLock) {
            state = FilterState.create(captured, state.query(), nextRevision(state.revision()));
        }
    }

    /// Applies one legacy-compatible file-name query without loading resource-pack metadata.
    ///
    /// @param query plain case-insensitive text or a `regex:` expression
    /// @return whether the filtered source changed
    boolean setSearchQuery(String query) {
        String checkedQuery = Objects.requireNonNull(query, "query");
        synchronized (stateLock) {
            if (state.query().equals(checkedQuery)) {
                return false;
            }
            state = FilterState.create(state.allPaths(), checkedQuery, nextRevision(state.revision()));
            return true;
        }
    }

    /// Resolves selected visible indexes into stable paths without materializing lazy rows.
    ///
    /// @param selectedIndices Swing selected indexes in the filtered list
    /// @return immutable stable paths in selected-index order
    @Unmodifiable List<Path> selectedPaths(int[] selectedIndices) {
        Objects.requireNonNull(selectedIndices, "selectedIndices");
        synchronized (stateLock) {
            List<Path> selected = new ArrayList<>(selectedIndices.length);
            Set<Integer> uniqueIndices = new HashSet<>();
            for (int selectedIndex : selectedIndices) {
                if (selectedIndex < 0 || selectedIndex >= state.visiblePaths().size()) {
                    throw new IllegalArgumentException("Selected resource-pack index is no longer visible");
                }
                if (uniqueIndices.add(selectedIndex)) {
                    selected.add(state.visiblePaths().get(selectedIndex));
                }
            }
            return List.copyOf(selected);
        }
    }

    /// Finds one stable path in the current filtered order.
    ///
    /// @param path normalized path to find
    /// @return filtered index, or negative one when hidden or absent
    int indexOf(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        synchronized (stateLock) {
            return state.visiblePaths().indexOf(normalized);
        }
    }

    /// Returns the exact current filtered count.
    ///
    /// @return exact filtered count
    @Override
    public OptionalInt exactItemCount() {
        synchronized (stateLock) {
            return OptionalInt.of(state.visiblePaths().size());
        }
    }

    /// Returns the local index or query revision used to reject stale viewport completions.
    ///
    /// @return current filtered-source revision
    @Override
    public OptionalLong sourceRevision() {
        synchronized (stateLock) {
            return OptionalLong.of(state.revision());
        }
    }

    /// Maps one filtered viewport range to exact consecutive delegate ranges.
    ///
    /// @param desiredRange filtered viewport range
    /// @param cancellation caller-owned cancellation
    /// @return eventual exact filtered page
    @Override
    public CompletionStage<ChoicePage<ResourcePackCatalogItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancelled();
        FilterState captured;
        synchronized (stateLock) {
            captured = state;
        }
        int itemCount = captured.visiblePaths().size();
        IndexRange actualRange = desiredRange.clampToItemCount(itemCount);
        if (actualRange.isEmpty()) {
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    actualRange,
                    List.of(),
                    OptionalInt.of(itemCount),
                    actualRange.endExclusive() == itemCount));
        }

        @Unmodifiable List<Path> requestedPaths = List.copyOf(captured.visiblePaths().subList(
                actualRange.startInclusive(),
                actualRange.endExclusive()));
        @Unmodifiable List<LoadSegment> segments = loadSegments(captured.sourceIndices(), requestedPaths);
        CompletionStage<@Unmodifiable List<ResourcePackCatalogItem>> completion =
                CompletableFuture.completedFuture(List.of());
        for (LoadSegment segment : segments) {
            completion = completion.thenCompose(loaded -> delegate.load(segment.sourceRange(), cancellation)
                    .thenApply(page -> appendSegment(loaded, segment, page, cancellation)));
        }
        return completion.thenApply(items -> {
            cancellation.throwIfCancelled();
            return new ChoicePage<>(
                    actualRange,
                    items,
                    OptionalInt.of(itemCount),
                    actualRange.endExclusive() == itemCount);
        });
    }

    /// Appends one exact mapped segment from a delegate page that may be wider than requested.
    ///
    /// @param loaded previously resolved filtered rows
    /// @param segment exact expected source segment
    /// @param page delegate result
    /// @param cancellation caller-owned cancellation
    /// @return immutable rows including the appended segment
    private static @Unmodifiable List<ResourcePackCatalogItem> appendSegment(
            @Unmodifiable List<ResourcePackCatalogItem> loaded,
            LoadSegment segment,
            ChoicePage<ResourcePackCatalogItem> page,
            LoadCancellation cancellation) {
        cancellation.throwIfCancelled();
        Map<Path, ResourcePackCatalogItem> rowsByPath = new HashMap<>();
        for (ResourcePackCatalogItem item : page.items()) {
            if (rowsByPath.put(item.path(), item) != null) {
                throw new IllegalArgumentException("Delegate returned a duplicate resource-pack path");
            }
        }
        List<ResourcePackCatalogItem> combined = new ArrayList<>(loaded.size() + segment.paths().size());
        combined.addAll(loaded);
        for (Path expectedPath : segment.paths()) {
            @Nullable ResourcePackCatalogItem item = rowsByPath.get(expectedPath);
            if (item == null) {
                throw new IllegalArgumentException("Delegate page omitted requested resource pack: " + expectedPath);
            }
            combined.add(item);
        }
        return List.copyOf(combined);
    }

    /// Groups requested filtered paths into exact consecutive delegate-index ranges.
    ///
    /// @param sourceIndices stable path-to-source-index map
    /// @param requestedPaths filtered requested paths
    /// @return immutable consecutive source segments
    private static @Unmodifiable List<LoadSegment> loadSegments(
            @Unmodifiable Map<Path, Integer> sourceIndices,
            @Unmodifiable List<Path> requestedPaths) {
        List<LoadSegment> segments = new ArrayList<>();
        int segmentStart = -1;
        int previousIndex = -2;
        List<Path> segmentPaths = new ArrayList<>();
        for (Path path : requestedPaths) {
            @Nullable Integer sourceIndex = sourceIndices.get(path);
            if (sourceIndex == null) {
                throw new IllegalArgumentException("Filtered path is absent from its source index: " + path);
            }
            if (!segmentPaths.isEmpty() && sourceIndex != previousIndex + 1) {
                segments.add(new LoadSegment(
                        new IndexRange(segmentStart, previousIndex + 1),
                        segmentPaths));
                segmentPaths = new ArrayList<>();
            }
            if (segmentPaths.isEmpty()) {
                segmentStart = sourceIndex;
            }
            segmentPaths.add(path);
            previousIndex = sourceIndex;
        }
        if (!segmentPaths.isEmpty()) {
            segments.add(new LoadSegment(
                    new IndexRange(segmentStart, previousIndex + 1),
                    segmentPaths));
        }
        return List.copyOf(segments);
    }

    /// Normalizes, freezes, and rejects duplicate shallow index paths.
    ///
    /// @param paths source paths
    /// @return immutable normalized paths
    private static @Unmodifiable List<Path> immutablePaths(@Unmodifiable List<Path> paths) {
        @Unmodifiable List<Path> normalized = Objects.requireNonNull(paths, "paths").stream()
                .map(path -> Objects.requireNonNull(path, "paths contains null"))
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException("Resource-pack index contains duplicate paths");
        }
        return normalized;
    }

    /// Filters shallow paths with the legacy file-name query syntax.
    ///
    /// @param paths complete shallow paths
    /// @param query plain case-insensitive text or `regex:` expression
    /// @return immutable matching paths
    private static @Unmodifiable List<Path> filteredPaths(
            @Unmodifiable List<Path> paths,
            String query) {
        if (query.isBlank()) {
            return List.copyOf(paths);
        }
        final Predicate<@Nullable String> matcher;
        try {
            matcher = StringUtils.compileQuery(query);
        } catch (PatternSyntaxException failure) {
            return List.of();
        }
        return paths.stream()
                .filter(path -> {
                    String fileName = Objects.requireNonNull(
                            path.getFileName(),
                            "indexed resource-pack path requires a file name").toString();
                    return matcher.test(fileName)
                            || matcher.test(FileUtils.getNameWithoutExtension(fileName));
                })
                .toList();
    }

    /// Increments one local revision without silent overflow.
    ///
    /// @param revision current revision
    /// @return next revision
    private static long nextRevision(long revision) {
        return Math.addExact(revision, 1L);
    }

    /// Immutable complete and filtered shallow index state.
    ///
    /// @param allPaths complete source-order paths
    /// @param visiblePaths current filtered paths
    /// @param sourceIndices stable complete-index lookup
    /// @param query current search query
    /// @param revision local stale-result revision
    @NotNullByDefault
    private record FilterState(
            @Unmodifiable List<Path> allPaths,
            @Unmodifiable List<Path> visiblePaths,
            @Unmodifiable Map<Path, Integer> sourceIndices,
            String query,
            long revision) {
        /// Stores defensive immutable state and validates membership.
        private FilterState {
            allPaths = List.copyOf(allPaths);
            visiblePaths = List.copyOf(visiblePaths);
            sourceIndices = Map.copyOf(sourceIndices);
            Objects.requireNonNull(query, "query");
            if (revision < 0L || !allPaths.containsAll(visiblePaths)) {
                throw new IllegalArgumentException("Filtered resource-pack state is inconsistent");
            }
        }

        /// Creates one internally consistent filtered state.
        ///
        /// @param allPaths complete source paths
        /// @param query current query
        /// @param revision local revision
        /// @return immutable filtered state
        private static FilterState create(
                @Unmodifiable List<Path> allPaths,
                String query,
                long revision) {
            Map<Path, Integer> indices = new HashMap<>();
            for (int index = 0; index < allPaths.size(); index++) {
                indices.put(allPaths.get(index), index);
            }
            return new FilterState(
                    allPaths,
                    filteredPaths(allPaths, query),
                    indices,
                    query,
                    revision);
        }
    }

    /// One exact consecutive delegate range and the requested paths it must contain.
    ///
    /// @param sourceRange exact delegate range
    /// @param paths expected paths in source order
    @NotNullByDefault
    private record LoadSegment(
            IndexRange sourceRange,
            @Unmodifiable List<Path> paths) {
        /// Freezes and validates one non-empty source segment.
        private LoadSegment {
            Objects.requireNonNull(sourceRange, "sourceRange");
            paths = List.copyOf(paths);
            if (paths.isEmpty() || paths.size() != sourceRange.length()) {
                throw new IllegalArgumentException("Load segment paths must exactly cover its source range");
            }
        }
    }
}
