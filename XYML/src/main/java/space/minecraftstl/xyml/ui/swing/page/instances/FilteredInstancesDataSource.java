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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Projects an installed-instance source through its cheap name-and-ID search index without eagerly loading rows.
///
/// An empty query delegates contiguous viewport ranges directly to the original model. A non-empty query
/// enumerates only cheap identities and resolves details for the matching visible rows, preserving the viewport
/// list's measured demand and adjacent-range cache behavior.
@NotNullByDefault
final class FilteredInstancesDataSource implements ViewportChoiceDataSource<InstanceListItem> {
    /// Prefix selecting the legacy case-insensitive regular-expression search mode.
    private static final String REGEX_PREFIX = "regex:";

    /// Underlying installed-instance model and row loader.
    private final InstancesModel source;

    /// Current immutable search projection.
    private volatile Projection projection;

    /// Current exact query used when the source content is refreshed.
    private String query = "";

    /// Creates an initially unfiltered projection of the current search index.
    ///
    /// @param source installed-instance source
    FilteredInstancesDataSource(InstancesModel source) {
        this.source = Objects.requireNonNull(source, "source");
        projection = new Projection(stableIds(source.searchEntries()), true, 0L);
    }

    /// Replaces the search query and invalidates outstanding filtered viewport work when visible IDs change.
    ///
    /// Plain text performs a case-insensitive substring match. The `regex:` prefix retains the former instance
    /// page's case-insensitive regular-expression mode; an invalid expression produces no visible matches.
    ///
    /// @param replacement exact user-entered query
    /// @return whether the visible stable-ID sequence changed
    synchronized boolean setQuery(String replacement) {
        String validated = Objects.requireNonNull(replacement, "replacement");
        if (query.equals(validated)) {
            return false;
        }
        query = validated;
        return replaceProjection(false);
    }

    /// Re-enumerates stable IDs after the backing model publishes a new content revision.
    ///
    /// The projection revision always advances so late row completions from the prior model content are rejected,
    /// even when the refreshed repository happens to retain the same identifiers.
    synchronized void refreshSource() {
        replaceProjection(true);
    }

    /// Finds one source-stable instance identifier in current filtered display order.
    ///
    /// @param stableId stable repository instance identifier
    /// @return filtered display index, or empty when the current query hides the instance
    OptionalInt displayIndexOf(String stableId) {
        int index = projection.visibleIds().indexOf(Objects.requireNonNull(stableId, "stableId"));
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /// Returns the exact current filtered row count.
    @Override
    public OptionalInt exactItemCount() {
        return OptionalInt.of(projection.visibleIds().size());
    }

    /// Returns the projection revision used to reject completions from an earlier query or source snapshot.
    @Override
    public OptionalLong sourceRevision() {
        return OptionalLong.of(projection.revision());
    }

    /// Loads a contiguous unfiltered range directly or only the requested matching stable IDs.
    ///
    /// @param desiredRange filtered viewport range
    /// @param cancellation cooperative cancellation signal
    /// @return eventual exact filtered page
    @Override
    public CompletionStage<ChoicePage<InstanceListItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        Projection requestProjection = projection;
        if (requestProjection.unfiltered()) {
            return source.load(desiredRange, cancellation);
        }

        @Unmodifiable List<String> requestIds = requestProjection.visibleIds();
        IndexRange actualRange = desiredRange.clampToItemCount(requestIds.size());
        List<CompletableFuture<InstanceListItem>> requests = new ArrayList<>(actualRange.length());
        for (int index = actualRange.startInclusive(); index < actualRange.endExclusive(); index++) {
            cancellation.throwIfCancelled();
            requests.add(source.loadItem(requestIds.get(index), cancellation).toCompletableFuture());
        }
        CompletableFuture<?>[] completions = requests.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(completions).thenApply(ignored -> {
            cancellation.throwIfCancelled();
            List<InstanceListItem> items = new ArrayList<>(requests.size());
            for (CompletableFuture<InstanceListItem> request : requests) {
                items.add(Objects.requireNonNull(request.join(), "instance source returned null row"));
            }
            return new ChoicePage<>(
                    actualRange,
                    List.copyOf(items),
                    OptionalInt.of(requestIds.size()),
                    actualRange.endExclusive() == requestIds.size());
        });
    }

    /// Recomputes the immutable current projection from the stored query.
    ///
    /// @param forceRevision whether unchanged visible IDs still represent replaced backing content
    /// @return whether the visible stable-ID sequence changed
    private boolean replaceProjection(boolean forceRevision) {
        @Unmodifiable List<InstanceSearchEntry> sourceEntries = List.copyOf(source.searchEntries());
        Predicate<InstanceSearchEntry> predicate = predicateFor(query);
        List<String> matches = new ArrayList<>(sourceEntries.size());
        for (InstanceSearchEntry entry : sourceEntries) {
            if (predicate.test(entry)) {
                matches.add(entry.stableId());
            }
        }
        @Unmodifiable List<String> visibleIds = List.copyOf(matches);
        Projection previous = projection;
        boolean changed = !previous.visibleIds().equals(visibleIds)
                || previous.unfiltered() != query.isEmpty();
        if (changed || forceRevision) {
            projection = new Projection(
                    visibleIds,
                    query.isEmpty(),
                    Math.addExact(previous.revision(), 1L));
        }
        return changed;
    }

    /// Creates the exact legacy-compatible identity predicate for one query.
    ///
    /// @param searchText exact user-entered query
    /// @return display-name and stable-ID predicate requiring no row detail load
    private static Predicate<InstanceSearchEntry> predicateFor(String searchText) {
        if (searchText.isEmpty()) {
            return ignored -> true;
        }
        if (searchText.startsWith(REGEX_PREFIX)) {
            try {
                Pattern pattern = Pattern.compile(
                        searchText.substring(REGEX_PREFIX.length()),
                        Pattern.CASE_INSENSITIVE);
                return entry -> pattern.matcher(entry.displayName()).find()
                        || pattern.matcher(entry.stableId()).find();
            } catch (PatternSyntaxException failure) {
                return ignored -> false;
            }
        }
        String normalized = searchText.toLowerCase(Locale.ROOT);
        return entry -> entry.displayName().toLowerCase(Locale.ROOT).contains(normalized)
                || entry.stableId().toLowerCase(Locale.ROOT).contains(normalized);
    }

    /// Extracts stable identifiers from one immutable source-ordered search index.
    ///
    /// @param entries cheap instance identities
    /// @return immutable source-ordered stable identifiers
    private static @Unmodifiable List<String> stableIds(@Unmodifiable List<InstanceSearchEntry> entries) {
        List<String> identifiers = new ArrayList<>(entries.size());
        for (InstanceSearchEntry entry : entries) {
            identifiers.add(entry.stableId());
        }
        return List.copyOf(identifiers);
    }

    /// Immutable IDs, direct-loading mode, and invalidation revision for one search projection.
    ///
    /// @param visibleIds stable IDs in filtered source order
    /// @param unfiltered whether range loads can be delegated directly
    /// @param revision monotonic projection revision
    @NotNullByDefault
    private record Projection(
            @Unmodifiable List<String> visibleIds,
            boolean unfiltered,
            long revision) {
        /// Validates one immutable projection.
        private Projection {
            visibleIds = List.copyOf(Objects.requireNonNull(visibleIds, "visibleIds"));
            if (revision < 0L) {
                throw new IllegalArgumentException("Projection revision cannot be negative");
            }
        }
    }
}
