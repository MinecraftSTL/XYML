// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.addon.repository;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests artifact selection through the production Modrinth version adapter.
@NotNullByDefault
public final class ModrinthProjectVersionFileTest {

    /// Selects the primary artifact even when an auxiliary artifact appears first.
    @Test
    public void selectsPrimaryFileAfterAuxiliaryFile() {
        var auxiliary = file("addon-sources.jar", false);
        var primary = file("addon.jar", true);
        var projectVersion = version(List.of(auxiliary, primary));

        RemoteAddon.Version converted = projectVersion.toVersion().orElseThrow();

        assertEquals(primary.toFile(), converted.file());
        assertSame(projectVersion, converted.self());
    }

    /// Preserves the first artifact when the provider marks no artifact as primary.
    @Test
    public void fallsBackToFirstFileWithoutPrimary() {
        var first = file("addon-sources.jar", false);
        var second = file("addon.jar", false);

        RemoteAddon.Version converted = version(List.of(first, second)).toVersion().orElseThrow();

        assertEquals(first.toFile(), converted.file());
    }

    /// Uses the first primary marker rather than inferring suitability from a filename.
    @Test
    public void selectsFirstPrimaryFileWithoutFilenameHeuristics() {
        var auxiliary = file("auxiliary.jar", false);
        var firstPrimary = file("addon-sources.jar", true);
        var secondPrimary = file("addon.jar", true);

        RemoteAddon.Version converted = version(List.of(auxiliary, firstPrimary, secondPrimary))
                .toVersion().orElseThrow();

        assertEquals(firstPrimary.toFile(), converted.file());
    }

    /// Omits provider versions that contain no downloadable artifacts.
    @Test
    public void returnsEmptyWhenFilesAreEmpty() {
        assertEquals(Optional.empty(), version(List.of()).toVersion());
    }

    /// Retains each documented runtime environment through provider deserialization.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"client_only", "server_only", "dedicated_server_only",
            "client_and_server", "server_only_client_optional", "client_only_server_optional",
            "client_or_server_prefers_both", "client_or_server", "singleplayer_only", "unknown", "future_value"})
    void retainsProviderEnvironment(String environment) {
        var source = version(List.of(file("primary.jar", true)));
        var object = space.minecraftstl.xyml.util.gson.JsonUtils.GSON.toJsonTree(source).getAsJsonObject();
        object.addProperty("environment", environment);
        var parsed = space.minecraftstl.xyml.util.gson.JsonUtils.GSON.fromJson(object,
                ModrinthRemoteAddonRepository.ProjectVersion.class).toVersion().orElseThrow();
        String expected = "future_value".equals(environment) ? "UNKNOWN" : environment.toUpperCase(java.util.Locale.ROOT);
        assertEquals(expected, parsed.environment().name());
    }

    /// Creates an artifact with distinct URL and hash metadata.
    ///
    /// @param filename provider artifact name
    /// @param primary provider primary marker
    /// @return artifact supplied to the production adapter
    private static ModrinthRemoteAddonRepository.ProjectVersionFile file(String filename, boolean primary) {
        return new ModrinthRemoteAddonRepository.ProjectVersionFile(
                Map.of("sha512", "hash-" + filename), "https://example.invalid/" + filename, filename, primary, 1);
    }

    /// Creates a provider version without network access or a replacement adapter.
    ///
    /// @param files provider artifacts in their original order
    /// @return provider version mapped by the production adapter
    private static ModrinthRemoteAddonRepository.ProjectVersion version(
            @Unmodifiable List<ModrinthRemoteAddonRepository.ProjectVersionFile> files) {
        return new ModrinthRemoteAddonRepository.ProjectVersion(
                "Addon", "1.0", "", List.of(), List.of("1.21"), "release", List.of("fabric"),
                false, "version-id", "project-id", "author-id", Instant.parse("2026-01-01T00:00:00Z"),
                0, "", files, null);
    }
}
