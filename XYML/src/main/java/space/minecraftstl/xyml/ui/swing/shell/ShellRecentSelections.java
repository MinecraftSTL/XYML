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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.collection.ObservableList;
import space.minecraftstl.xyml.setting.LauncherSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Maintains persistent most-recently-used ordering exclusively for compact shell selectors.
///
/// Management pages retain their own domain-specific source order. Histories use stable IDs and move-to-front
/// semantics rather than timestamps, avoiding clock ties while preserving an exact newest-to-oldest sequence.
@NotNullByDefault
public final class ShellRecentSelections {
    /// Persistent game-directory history.
    private final ObservableList<String> directories;

    /// Persistent account history.
    private final ObservableList<String> accounts;

    /// Persistent directory-qualified instance history.
    private final ObservableList<String> instances;

    /// Creates a selector-history adapter over launcher settings.
    ///
    /// @param settings persistent launcher settings
    public ShellRecentSelections(LauncherSettings settings) {
        LauncherSettings source = Objects.requireNonNull(settings, "settings");
        directories = source.getRecentGameDirectories();
        accounts = source.getRecentAccounts();
        instances = source.getRecentInstances();
    }

    /// Creates detached histories suitable for previews and focused tests.
    ///
    /// @return independent in-memory selection histories
    public static ShellRecentSelections transientSelections() {
        return new ShellRecentSelections(new LauncherSettings());
    }

    /// Returns current directory IDs in most-recently-used order and prunes removed IDs.
    ///
    /// @param sourceIds current management-page source order
    /// @return immutable selector order
    public @Unmodifiable List<String> orderDirectories(@Unmodifiable List<String> sourceIds) {
        return orderAndReconcile(directories, sourceIds);
    }

    /// Records one confirmed game-directory selection.
    ///
    /// @param stableId selected directory identifier
    public void recordDirectory(String stableId) {
        record(directories, stableId);
    }

    /// Returns current account IDs in most-recently-used order and prunes removed IDs.
    ///
    /// @param sourceIds current account-page source order
    /// @return immutable selector order
    public @Unmodifiable List<String> orderAccounts(@Unmodifiable List<String> sourceIds) {
        return orderAndReconcile(accounts, sourceIds);
    }

    /// Records one confirmed account selection.
    ///
    /// @param stableId selected account identifier
    public void recordAccount(String stableId) {
        record(accounts, stableId);
    }

    /// Returns instance IDs in the selected directory's independent most-recently-used order.
    ///
    /// @param directoryId selected directory identifier
    /// @param sourceIds current instance-page source order
    /// @return immutable selector order for this directory
    public @Unmodifiable List<String> orderInstances(
            String directoryId,
            @Unmodifiable List<String> sourceIds) {
        String context = requireNonBlank(directoryId, "directoryId");
        @Unmodifiable List<String> currentIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
        Set<String> validIds = Set.copyOf(currentIds);
        List<String> recentIds = new ArrayList<>();
        List<String> retainedEntries = new ArrayList<>();
        for (String entry : List.copyOf(instances)) {
            InstanceHistoryKey key = decodeInstanceKey(entry);
            if (key == null) {
                continue;
            }
            if (!key.directoryId().equals(context)) {
                retainedEntries.add(entry);
            } else if (validIds.contains(key.instanceId()) && !recentIds.contains(key.instanceId())) {
                retainedEntries.add(entry);
                recentIds.add(key.instanceId());
            }
        }
        replaceIfChanged(instances, retainedEntries);
        return appendSourceOrder(recentIds, currentIds);
    }

    /// Records one confirmed instance selection inside its directory context.
    ///
    /// @param directoryId selected directory identifier
    /// @param instanceId selected instance identifier
    public void recordInstance(String directoryId, String instanceId) {
        String context = requireNonBlank(directoryId, "directoryId");
        String id = requireNonBlank(instanceId, "instanceId");
        String encoded = encodeInstanceKey(context, id);
        List<String> replacement = new ArrayList<>(instances.size() + 1);
        replacement.add(encoded);
        for (String entry : List.copyOf(instances)) {
            if (!entry.equals(encoded)) {
                replacement.add(entry);
            }
        }
        replaceIfChanged(instances, replacement);
    }

    /// Orders one unqualified history and removes duplicates or identifiers no longer present.
    private static @Unmodifiable List<String> orderAndReconcile(
            ObservableList<String> history,
            @Unmodifiable List<String> sourceIds) {
        @Unmodifiable List<String> currentIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
        Set<String> validIds = Set.copyOf(currentIds);
        Set<String> seen = new HashSet<>();
        List<String> recentIds = new ArrayList<>();
        for (String stableId : List.copyOf(history)) {
            if (validIds.contains(stableId) && seen.add(stableId)) {
                recentIds.add(stableId);
            }
        }
        replaceIfChanged(history, recentIds);
        return appendSourceOrder(recentIds, currentIds);
    }

    /// Moves one unqualified stable identifier to the front.
    private static void record(ObservableList<String> history, String stableId) {
        String id = requireNonBlank(stableId, "stableId");
        List<String> replacement = new ArrayList<>(history.size() + 1);
        replacement.add(id);
        for (String existing : List.copyOf(history)) {
            if (!existing.equals(id)) {
                replacement.add(existing);
            }
        }
        replaceIfChanged(history, replacement);
    }

    /// Appends never-used source IDs after the retained recent prefix.
    private static @Unmodifiable List<String> appendSourceOrder(
            List<String> recentIds,
            @Unmodifiable List<String> sourceIds) {
        List<String> ordered = new ArrayList<>(sourceIds.size());
        ordered.addAll(recentIds);
        Set<String> recent = Set.copyOf(recentIds);
        for (String stableId : sourceIds) {
            if (!recent.contains(stableId)) {
                ordered.add(stableId);
            }
        }
        return List.copyOf(ordered);
    }

    /// Replaces one observable history only when its exact order changed.
    private static void replaceIfChanged(ObservableList<String> history, List<String> replacement) {
        if (!List.copyOf(history).equals(replacement)) {
            history.setAll(replacement);
        }
    }

    /// Encodes a directory-qualified instance without relying on reserved delimiter characters.
    private static String encodeInstanceKey(String directoryId, String instanceId) {
        return directoryId.length() + ":" + directoryId + instanceId;
    }

    /// Decodes one length-prefixed instance-history entry.
    private static InstanceHistoryKey decodeInstanceKey(String encoded) {
        int separator = encoded.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        try {
            int directoryLength = Integer.parseInt(encoded.substring(0, separator));
            int directoryStart = separator + 1;
            int instanceStart = Math.addExact(directoryStart, directoryLength);
            if (directoryLength <= 0 || instanceStart >= encoded.length()) {
                return null;
            }
            return new InstanceHistoryKey(
                    encoded.substring(directoryStart, instanceStart),
                    encoded.substring(instanceStart));
        } catch (ArithmeticException | NumberFormatException failure) {
            return null;
        }
    }

    /// Requires one non-blank stable identifier or context key.
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /// Decoded directory-qualified instance-history key.
    ///
    /// @param directoryId stable directory identifier
    /// @param instanceId stable instance identifier inside that directory
    @NotNullByDefault
    private record InstanceHistoryKey(String directoryId, String instanceId) {
        /// Validates decoded key components.
        private InstanceHistoryKey {
            requireNonBlank(directoryId, "directoryId");
            requireNonBlank(instanceId, "instanceId");
        }
    }
}
