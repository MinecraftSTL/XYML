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
package space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.datapack.DataPack;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

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

/// In-memory immutable data-pack snapshot exposed through the shared sparse viewport-list protocol.
///
/// `DataPack` discovery happens off the EDT in the owning panel. This source then lets the visual
/// list materialize only the rows that its measured viewport needs, rather than creating one Swing
/// renderer value for every installed pack at selection time.
@NotNullByDefault
final class DataPackViewportDataSource implements ViewportChoiceDataSource<DataPack.Pack> {
    /// Prefix selecting the legacy case-insensitive regular-expression search mode.
    private static final String REGEX_PREFIX = "regex:";

    /// Lock pairing a complete immutable data-pack snapshot with its source revision.
    private final Object stateLock = new Object();

    /// Complete immutable snapshot produced by the selected world's DataPack API.
    private @Unmodifiable List<DataPack.Pack> allPacks = List.of();

    /// Latest immutable snapshot matching [#searchQuery].
    private @Unmodifiable List<DataPack.Pack> packs = List.of();

    /// Current search query, including the optional legacy-compatible `regex:` prefix.
    private String searchQuery = "";

    /// Monotonic content identity used to discard old viewport completions after reselection.
    private long revision;

    /// Replaces the exact data-pack snapshot after background discovery or mutation completes.
    ///
    /// @param updatedPacks immutable or mutable data-pack values to snapshot defensively
    void replacePacks(List<? extends DataPack.Pack> updatedPacks) {
        @Unmodifiable List<DataPack.Pack> snapshot = List.copyOf(Objects.requireNonNull(updatedPacks, "updatedPacks"));
        synchronized (stateLock) {
            allPacks = snapshot;
            packs = filteredPacks(snapshot, searchQuery);
            revision++;
        }
    }

    /// Applies a case-insensitive or `regex:` search to the complete selected-world snapshot.
    ///
    /// @param query user-entered search query
    void setSearchQuery(String query) {
        String checkedQuery = Objects.requireNonNull(query, "query");
        synchronized (stateLock) {
            searchQuery = checkedQuery;
            packs = filteredPacks(allPacks, checkedQuery);
            revision++;
        }
    }

    /// Resolves selected filtered-list indexes without requiring every lazy row to be materialized.
    ///
    /// @param selectedIndices filtered-list indexes selected by Swing
    /// @return immutable selected loaded values in index order
    @Unmodifiable List<DataPack.Pack> selectedPacks(int[] selectedIndices) {
        Objects.requireNonNull(selectedIndices, "selectedIndices");
        synchronized (stateLock) {
            return java.util.Arrays.stream(selectedIndices)
                    .filter(index -> index >= 0 && index < packs.size())
                    .mapToObj(packs::get)
                    .toList();
        }
    }

    /// Returns the exact count from the latest selected-world snapshot.
    ///
    /// @return exact non-negative data-pack count
    @Override
    public OptionalInt exactItemCount() {
        synchronized (stateLock) {
            return OptionalInt.of(packs.size());
        }
    }

    /// Returns the snapshot revision paired with all list values.
    ///
    /// @return source revision used by the sparse list to reject stale rows
    @Override
    public OptionalLong sourceRevision() {
        synchronized (stateLock) {
            return OptionalLong.of(revision);
        }
    }

    /// Returns a contiguous portion of the current immutable snapshot without background work.
    ///
    /// @param desiredRange viewport-derived requested range
    /// @param cancellation cooperative cancellation signal
    /// @return immediately completed page matching the current selected-world snapshot
    @Override
    public CompletionStage<ChoicePage<DataPack.Pack>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        IndexRange requested = Objects.requireNonNull(desiredRange, "desiredRange");
        LoadCancellation signal = Objects.requireNonNull(cancellation, "cancellation");
        signal.throwIfCancelled();
        @Unmodifiable List<DataPack.Pack> snapshot;
        synchronized (stateLock) {
            snapshot = packs;
        }
        IndexRange effectiveRange = requested.clampToItemCount(snapshot.size());
        @Unmodifiable List<DataPack.Pack> values = List.copyOf(snapshot.subList(
                effectiveRange.startInclusive(),
                effectiveRange.endExclusive()));
        ChoicePage<DataPack.Pack> page = new ChoicePage<>(
                effectiveRange,
                values,
                OptionalInt.of(snapshot.size()),
                effectiveRange.endExclusive() == snapshot.size());
        return CompletableFuture.completedFuture(page);
    }

    /// Builds one immutable filtered snapshot using the legacy data-pack search semantics.
    ///
    /// @param source complete immutable data-pack snapshot
    /// @param query plain substring or `regex:` query
    /// @return immutable matching snapshot
    private static @Unmodifiable List<DataPack.Pack> filteredPacks(
            List<DataPack.Pack> source,
            String query) {
        String checkedQuery = Objects.requireNonNull(query, "query");
        if (checkedQuery.isBlank()) {
            return List.copyOf(source);
        }
        Predicate<String> matcher = searchMatcher(checkedQuery);
        return source.stream()
                .filter(pack -> matcher.test(pack.getId()) || matcher.test(pack.getDescription().toString()))
                .toList();
    }

    /// Creates a text matcher for a plain case-insensitive substring or `regex:` expression.
    ///
    /// @param query non-blank search query
    /// @return matcher that rejects every value when a regular expression is invalid
    private static Predicate<String> searchMatcher(String query) {
        if (query.startsWith(REGEX_PREFIX)) {
            try {
                Pattern pattern = Pattern.compile(
                        query.substring(REGEX_PREFIX.length()),
                        Pattern.CASE_INSENSITIVE);
                return value -> value != null && pattern.matcher(value).find();
            } catch (PatternSyntaxException failure) {
                return value -> false;
            }
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return value -> value != null && value.toLowerCase(Locale.ROOT).contains(normalized);
    }
}
