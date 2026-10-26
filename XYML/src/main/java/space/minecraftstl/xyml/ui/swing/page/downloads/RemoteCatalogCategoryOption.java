// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;
import space.minecraftstl.xyml.util.i18n.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Objects;

/// One flattened provider category option with its original backend value and visual nesting depth.
///
/// @param depth non-negative hierarchy depth
/// @param category provider category, or null for the all-categories option
@NotNullByDefault
record RemoteCatalogCategoryOption(
        int depth,
        @Nullable RemoteAddonRepository.Category category) {
    /// Validates hierarchy depth while retaining the explicit nullable all-categories sentinel.
    RemoteCatalogCategoryOption {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        if (category == null && depth != 0) {
            throw new IllegalArgumentException("all-categories option must be at depth zero");
        }
    }

    /// Creates the unfiltered category option.
    ///
    /// @return all-categories option
    static RemoteCatalogCategoryOption all() {
        return new RemoteCatalogCategoryOption(0, null);
    }

    /// Flattens provider category trees in source order after the all-categories option.
    ///
    /// @param categories provider category roots
    /// @return immutable flattened selector options
    static @Unmodifiable List<RemoteCatalogCategoryOption> flatten(
            @Unmodifiable List<RemoteAddonRepository.Category> categories) {
        List<RemoteCatalogCategoryOption> options = new ArrayList<>();
        options.add(all());
        for (RemoteAddonRepository.Category category : Objects.requireNonNull(categories, "categories")) {
            append(category, 0, options);
        }
        return List.copyOf(options);
    }

    /// Appends one provider subtree in depth-first source order.
    ///
    /// @param category current provider category
    /// @param depth current hierarchy depth
    /// @param destination mutable flattening destination
    private static void append(
            RemoteAddonRepository.Category category,
            int depth,
            List<RemoteCatalogCategoryOption> destination) {
        RemoteAddonRepository.Category current = Objects.requireNonNull(category, "category");
        destination.add(new RemoteCatalogCategoryOption(depth, current));
        for (RemoteAddonRepository.Category child : current.subcategories()) {
            append(child, depth + 1, destination);
        }
    }

    /// Produces an indented provider-localized selector label with a robust source-name fallback.
    ///
    /// @param modrinth whether the current provider is Modrinth rather than CurseForge
    /// @param allCategoriesLabel visible unfiltered option label
    /// @return localized and hierarchy-indented option text
    String displayText(boolean modrinth, String allCategoriesLabel) {
        @Nullable RemoteAddonRepository.Category selectedCategory = category;
        if (selectedCategory == null) {
            return Objects.requireNonNull(allCategoriesLabel, "allCategoriesLabel");
        }
        String prefix = modrinth ? "modrinth.category." : "curse.category.";
        String label;
        try {
            label = I18n.getResourceBundle().getString(prefix + selectedCategory.id());
        } catch (MissingResourceException missingTranslation) {
            if (selectedCategory.self() instanceof CurseForgeRemoteAddonRepository.Category curseCategory) {
                label = curseCategory.getName();
            } else {
                label = selectedCategory.id();
            }
        }
        return "    ".repeat(depth) + label;
    }
}
