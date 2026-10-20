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
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;

import javax.swing.JComboBox;
import java.util.List;
import java.util.Objects;

/// Owns one remote-category selector's programmatic application and loader-category mapping.
@NotNullByDefault
final class RemoteAddonCategoryControls {
    /// Provider category selector owned by the controls.
    private final JComboBox<RemoteCatalogCategoryOption> categoryBox;

    /// Whether an internal publication must not be interpreted as a user edit.
    private boolean applying;

    /// Provider category requested by prerequisite navigation, or null when none is pending.
    private @Nullable String pendingDependencyCategoryId;

    /// Creates controls around one category selector.
    ///
    /// @param categoryBox provider category selector
    RemoteAddonCategoryControls(JComboBox<RemoteCatalogCategoryOption> categoryBox) {
        this.categoryBox = Objects.requireNonNull(categoryBox, "categoryBox");
    }

    /// Returns whether the current category event is an internal publication.
    ///
    /// @return true while applying or selecting a programmatic category
    boolean isApplying() {
        return applying;
    }

    /// Returns whether prerequisite navigation is waiting for provider categories.
    ///
    /// @return true while a dependency category remains pending
    boolean hasPendingDependencyCategory() {
        return pendingDependencyCategoryId != null;
    }

    /// Replaces category options and selects the pending dependency category when available.
    ///
    /// @param options immutable flattened provider category options
    void apply(@Unmodifiable List<RemoteCatalogCategoryOption> options) {
        @Nullable String pendingCategoryId = pendingDependencyCategoryId;
        pendingDependencyCategoryId = null;
        applying = true;
        try {
            categoryBox.removeAllItems();
            for (RemoteCatalogCategoryOption option : Objects.requireNonNull(options, "options")) {
                categoryBox.addItem(option);
            }
            if ((pendingCategoryId == null || !selectCategoryById(pendingCategoryId))
                    && categoryBox.getItemCount() > 0) {
                categoryBox.setSelectedIndex(0);
            }
        } finally {
            applying = false;
        }
    }

    /// Restores the all-categories option and clears any pending dependency category.
    void reset() {
        apply(List.of(RemoteCatalogCategoryOption.all()));
    }

    /// Selects one provider category by identifier without interpreting it as a user edit.
    ///
    /// @param categoryId provider category identifier
    /// @return true when a matching category was selected
    boolean select(String categoryId) {
        applying = true;
        try {
            return selectCategoryById(categoryId);
        } finally {
            applying = false;
        }
    }

    /// Defers one dependency category until provider options become available.
    ///
    /// @param categoryId provider category identifier
    void defer(String categoryId) {
        pendingDependencyCategoryId = Objects.requireNonNull(categoryId, "categoryId");
    }

    /// Clears a pending dependency category without changing the current selection.
    void clearPending() {
        pendingDependencyCategoryId = null;
    }

    /// Maps one launcher mod loader to the provider category used by Modrinth searches.
    ///
    /// @param modLoader current instance mod loader, or null when unavailable
    /// @return Modrinth loader category identifier, or null when the loader has no distinct category
    static @Nullable String dependencyCategoryId(@Nullable ModLoaderType modLoader) {
        if (modLoader == null) {
            return null;
        }
        return switch (modLoader) {
            case FORGE -> "forge";
            case CLEANROOM -> "cleanroom";
            case NEO_FORGE -> "neoforge";
            case FABRIC -> "fabric";
            case QUILT -> "quilt";
            case LITE_LOADER -> "liteloader";
            case LEGACY_FABRIC -> "legacy-fabric";
            case UNKNOWN -> null;
        };
    }

    /// Selects one provider category by identifier while suppression is owned by the caller.
    ///
    /// @param categoryId provider category identifier
    /// @return true when a matching category was selected
    private boolean selectCategoryById(String categoryId) {
        String requestedCategoryId = Objects.requireNonNull(categoryId, "categoryId");
        for (int index = 0; index < categoryBox.getItemCount(); index++) {
            RemoteCatalogCategoryOption option = categoryBox.getItemAt(index);
            @Nullable RemoteAddonRepository.Category category = option.category();
            if (category != null && requestedCategoryId.equals(category.id())) {
                categoryBox.setSelectedIndex(index);
                return true;
            }
        }
        return false;
    }
}
