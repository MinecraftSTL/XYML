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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import space.minecraftstl.xyml.util.versioning.VersionNumber;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Provides deterministic, provider-independent ordering for remote add-on versions.
///
/// Providers return versions in different orders and often mix versions for several Minecraft
/// releases. This utility presents one stable sequence: an exact requested game version first,
/// then the selected game-version or mod-version dimension, release channels, publication dates,
/// and finally stable identifiers. The first release in that sequence is the recommendation shown by Swing.
@NotNullByDefault
final class RemoteAddonVersionOrdering {
    /// Prevents construction of this stateless ordering utility.
    private RemoteAddonVersionOrdering() {
    }

    /// Sorts a provider response without mutating the provider-owned list.
    ///
    /// @param versions provider-returned installable versions
    /// @param requestedGameVersion optional exact game-version filter
    /// @return immutable ordered snapshot
    static @Unmodifiable List<RemoteAddon.Version> order(
            @Unmodifiable List<RemoteAddon.Version> versions,
            String requestedGameVersion) {
        return order(versions, requestedGameVersion, RemoteAddonVersionSortMode.RECOMMENDED);
    }

    /// Sorts a provider response using the selected user-facing version dimension.
    ///
    /// @param versions provider-returned installable versions
    /// @param requestedGameVersion optional exact game-version filter
    /// @param sortMode selected version ordering mode
    /// @return immutable ordered snapshot
    static @Unmodifiable List<RemoteAddon.Version> order(
            @Unmodifiable List<RemoteAddon.Version> versions,
            String requestedGameVersion,
            RemoteAddonVersionSortMode sortMode) {
        String requested = Objects.requireNonNull(requestedGameVersion, "requestedGameVersion").trim();
        List<RemoteAddon.Version> ordered = new ArrayList<>(Objects.requireNonNull(versions, "versions"));
        ordered.sort(comparator(requested, Objects.requireNonNull(sortMode, "sortMode")));
        return List.copyOf(ordered);
    }

    /// Chooses the newest stable release matching the requested game version when possible.
    ///
    /// The recommendation always uses the dedicated recommended ordering, regardless of the
    /// browsing order currently selected by the caller.
    ///
    /// @param versions provider-returned or otherwise unordered installable versions
    /// @param requestedGameVersion optional exact game-version filter
    /// @return recommended release, or the first available version, or null for an empty list
    static @Nullable RemoteAddon.Version recommended(
            @Unmodifiable List<RemoteAddon.Version> versions,
            String requestedGameVersion) {
        String requested = Objects.requireNonNull(requestedGameVersion, "requestedGameVersion").trim();
        @Unmodifiable List<RemoteAddon.Version> orderedVersions = order(
                Objects.requireNonNull(versions, "versions"),
                requested,
                RemoteAddonVersionSortMode.RECOMMENDED);
        @Nullable RemoteAddon.Version first = null;
        for (RemoteAddon.Version version : orderedVersions) {
            if (first == null) {
                first = version;
            }
            if (version.versionType() == RemoteAddon.VersionType.Release
                    && (requested.isEmpty() || hasGameVersion(version, requested))) {
                return version;
            }
        }
        return first;
    }

    /// Formats a version for the compact Swing selector, including both version dimensions.
    ///
    /// @param version version metadata
    /// @param recommended whether the row is the selected recommendation
    /// @param requestedGameVersion optional game-version context shown first when compatible
    /// @return localized concise version text
    static String displayText(
            RemoteAddon.Version version,
            boolean recommended,
            String requestedGameVersion) {
        RemoteAddon.Version selected = Objects.requireNonNull(version, "version");
        String requested = Objects.requireNonNull(requestedGameVersion, "requestedGameVersion").trim();
        String name = selected.name().isBlank() ? selected.version() : selected.name();
        String gameVersions = gameVersionText(selected, requested);
        String channel = RemoteVersionChannelPresentation.label(selected.versionType());
        String recommendation = recommended
                ? i18n("addon.download.recommend", recommendationGameVersion(selected, requested)) + " | "
                : "";
        return recommendation + gameVersions + " | " + name + " (" + selected.version() + ") - " + channel;
    }

    /// Returns unique compatible game versions with the requested version first, then descending.
    ///
    /// @param version version metadata
    /// @param requestedGameVersion optional exact game-version context
    /// @return comma-separated game-version group label
    static String gameVersionText(RemoteAddon.Version version, String requestedGameVersion) {
        String requested = Objects.requireNonNull(requestedGameVersion, "requestedGameVersion").trim();
        Set<String> versions = new LinkedHashSet<>(Objects.requireNonNull(version, "version").gameVersions());
        List<String> ordered = new ArrayList<>(versions);
        ordered.sort(RemoteAddonVersionOrdering::compareGameVersionDescending);
        if (!requested.isEmpty()) {
            for (int index = 0; index < ordered.size(); index++) {
                if (ordered.get(index).trim().equalsIgnoreCase(requested)) {
                    String exact = ordered.remove(index);
                    ordered.add(0, exact);
                    break;
                }
            }
        }
        return ordered.isEmpty() ? "-" : String.join(", ", ordered);
    }

    /// Returns the exact requested compatible version, otherwise the newest version, for recommendation text.
    private static String recommendationGameVersion(RemoteAddon.Version version, String requested) {
        if (!requested.isEmpty() && hasGameVersion(version, requested)) {
            return requested;
        }
        return highestGameVersion(version).isEmpty() ? "-" : highestGameVersion(version);
    }

    /// Builds the complete ordering comparator for one requested game-version context.
    ///
    /// @param requested exact requested game version, or empty
    /// @param sortMode selected version ordering mode
    /// @return descending stable comparator
    private static Comparator<RemoteAddon.Version> comparator(
            String requested,
            RemoteAddonVersionSortMode sortMode) {
        Comparator<RemoteAddon.Version> compatibility = Comparator.comparingInt(
                (RemoteAddon.Version version) -> exactGameVersionRank(version, requested));
        Comparator<RemoteAddon.Version> stableTieBreakers = Comparator
                .comparing(RemoteAddonVersionOrdering::publishedAt, Comparator.reverseOrder())
                .thenComparing(RemoteAddon.Version::version, String.CASE_INSENSITIVE_ORDER.reversed())
                .thenComparing(RemoteAddon.Version::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RemoteAddon.Version::versionId, String.CASE_INSENSITIVE_ORDER);
        return switch (Objects.requireNonNull(sortMode, "sortMode")) {
            case RECOMMENDED -> compatibility
                    .thenComparingInt(version -> channelRank(version.versionType()))
                    .thenComparing(RemoteAddonVersionOrdering::modVersion,
                            RemoteAddonVersionOrdering::compareModVersionDescending)
                    .thenComparing(version -> groupedGameVersion(version, requested),
                            RemoteAddonVersionOrdering::compareGameVersionDescending)
                    .thenComparing(stableTieBreakers);
            case GAME_VERSION -> compatibility
                    .thenComparing(version -> groupedGameVersion(version, requested),
                            RemoteAddonVersionOrdering::compareGameVersionDescending)
                    .thenComparingInt(version -> channelRank(version.versionType()))
                    .thenComparing(RemoteAddonVersionOrdering::modVersion,
                            RemoteAddonVersionOrdering::compareModVersionDescending)
                    .thenComparing(stableTieBreakers);
            case MOD_VERSION -> compatibility
                    .thenComparing(RemoteAddonVersionOrdering::modVersion,
                            RemoteAddonVersionOrdering::compareModVersionDescending)
                    .thenComparingInt(version -> channelRank(version.versionType()))
                    .thenComparing(version -> groupedGameVersion(version, requested),
                            RemoteAddonVersionOrdering::compareGameVersionDescending)
                    .thenComparing(stableTieBreakers);
        };
    }

    /// Gives exact game-version matches precedence without excluding other compatible releases.
    private static int exactGameVersionRank(RemoteAddon.Version version, String requested) {
        return requested.isEmpty() || hasGameVersion(version, requested) ? 0 : 1;
    }

    /// Checks a case-insensitive exact game-version membership.
    private static boolean hasGameVersion(RemoteAddon.Version version, String requested) {
        return version.gameVersions().stream().anyMatch(value -> value.trim().equalsIgnoreCase(requested));
    }

    /// Uses the requested version as the shared group key for every exact compatible artifact.
    private static String groupedGameVersion(RemoteAddon.Version version, String requested) {
        return !requested.isEmpty() && hasGameVersion(version, requested)
                ? requested
                : highestGameVersion(version);
    }

    /// Returns the newest compatible game-version group key.
    private static String highestGameVersion(RemoteAddon.Version version) {
        return version.gameVersions().stream()
                .max(RemoteAddonVersionOrdering::compareGameVersion)
                .orElse("");
    }

    /// Returns a safe semantic version object for comparator use.
    private static VersionNumber modVersion(RemoteAddon.Version version) {
        try {
            return VersionNumber.asVersion(version.version());
        } catch (RuntimeException invalidVersion) {
            return VersionNumber.ZERO;
        }
    }

    /// Returns a publication instant while preserving a deterministic epoch fallback.
    private static Instant publishedAt(RemoteAddon.Version version) {
        @Nullable Instant published = version.datePublished();
        return published == null ? Instant.EPOCH : published;
    }

    /// Orders release channels before previews.
    private static int channelRank(RemoteAddon.VersionType versionType) {
        return switch (Objects.requireNonNull(versionType, "versionType")) {
            case Release -> 0;
            case Beta -> 1;
            case Alpha -> 2;
        };
    }

    /// Compares two game-version strings using launcher semantics, with a lexical fallback.
    private static int compareGameVersion(String left, String right) {
        try {
            return GameVersionNumber.compare(left, right);
        } catch (RuntimeException invalidVersion) {
            return left.compareToIgnoreCase(right);
        }
    }

    /// Descending game-version comparison for comparator method references.
    private static int compareGameVersionDescending(String left, String right) {
        return compareGameVersion(right, left);
    }

    /// Descending semantic mod-version comparison for comparator method references.
    private static int compareModVersionDescending(VersionNumber left, VersionNumber right) {
        return right.compareTo(left);
    }
}
