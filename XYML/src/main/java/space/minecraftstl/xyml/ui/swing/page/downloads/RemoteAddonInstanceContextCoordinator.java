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
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceAddonContext;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Applies one target instance's analyzed version and loader to a remote catalog's criteria band.
///
/// Resolution runs on the instance model's own background thread and only the latest completed request is applied
/// on the EDT. Applying criteria never starts a provider query, so the user still supplies the project name.
@NotNullByDefault
final class RemoteAddonInstanceContextCoordinator {
    /// Optional installed-instance source, or null when the catalog models no instance context.
    private final @Nullable InstancesModel source;

    /// Provider selector set to Modrinth whenever an instance context is applied.
    private final JComboBox<RemoteAddonCatalogSource> sourceBox;

    /// Editable Minecraft-version field updated from the analyzed instance.
    private final JComboBox<String> gameVersionField;

    /// Loader-category mapping owner for the same criteria band.
    private final RemoteAddonCategoryControls categoryControls;

    /// Reports whether Modrinth provider categories are already published.
    private final BooleanSupplier categoriesReady;

    /// Restores the provider-default result ordering.
    private final Runnable resetSortOptions;

    /// Project query field cleared before an instance context is applied.
    private final JTextField searchField;

    /// Discards any query retained for a later layout pass.
    private final Runnable clearPendingSearch;

    /// Optional local target selector attached after construction.
    private @Nullable RemoteAddonTargetInstanceSelector targetInstanceSelector;

    /// Monotonic revision identifying the latest applied resolution.
    private long revision;

    /// Instance whose resolution is outstanding, or null when none is pending.
    private @Nullable GameInstanceID resolvingInstanceId;

    /// Creates one instance-context coordinator for the owning catalog.
    ///
    /// @param source optional installed-instance source
    /// @param sourceBox provider selector
    /// @param gameVersionField editable Minecraft-version field
    /// @param categoryControls loader-category controls
    /// @param categoriesReady reports whether Modrinth categories are published
    /// @param resetSortOptions restores the provider-default ordering
    /// @param searchField project query field cleared by programmatic navigation
    /// @param clearPendingSearch discards a retained project query
    RemoteAddonInstanceContextCoordinator(
            @Nullable InstancesModel source,
            JComboBox<RemoteAddonCatalogSource> sourceBox,
            JComboBox<String> gameVersionField,
            RemoteAddonCategoryControls categoryControls,
            BooleanSupplier categoriesReady,
            Runnable resetSortOptions,
            JTextField searchField,
            Runnable clearPendingSearch) {
        this.source = source;
        this.sourceBox = Objects.requireNonNull(sourceBox, "sourceBox");
        this.gameVersionField = Objects.requireNonNull(gameVersionField, "gameVersionField");
        this.categoryControls = Objects.requireNonNull(categoryControls, "categoryControls");
        this.categoriesReady = Objects.requireNonNull(categoriesReady, "categoriesReady");
        this.resetSortOptions = Objects.requireNonNull(resetSortOptions, "resetSortOptions");
        this.searchField = Objects.requireNonNull(searchField, "searchField");
        this.clearPendingSearch = Objects.requireNonNull(clearPendingSearch, "clearPendingSearch");
    }

    /// Attaches the owning catalog's optional local target selector.
    ///
    /// @param selector local target selector, or null when the catalog owns none
    void attachTargetSelector(@Nullable RemoteAddonTargetInstanceSelector selector) {
        targetInstanceSelector = selector;
    }

    /// Applies one navigation context: clears the project query, selects the target, and resolves criteria.
    ///
    /// @param instanceId instance whose analyzed context should prefill the owning catalog
    void applyNavigation(GameInstanceID instanceId) {
        clearPendingSearch.run();
        searchField.setText("");
        @Nullable RemoteAddonTargetInstanceSelector selector = targetInstanceSelector;
        if (selector != null) {
            selector.selectInstance(instanceId);
        }
        request(instanceId);
    }

    /// Applies analyzed version and loader criteria without starting a provider query.
    ///
    /// @param gameVersion analyzed Minecraft version, or null when unavailable
    /// @param modLoader current instance mod loader, or null when unavailable or unsupported
    void apply(@Nullable String gameVersion, @Nullable ModLoaderType modLoader) {
        sourceBox.setSelectedItem(RemoteAddonCatalogSource.MODRINTH);
        boolean ready = categoriesReady.getAsBoolean();
        @Nullable String dependencyCategoryId = RemoteAddonCategoryControls.dependencyCategoryId(modLoader);
        if (dependencyCategoryId == null || !ready) {
            categoryControls.reset();
        }
        resetSortOptions.run();
        SwingTextFields.textEditor(gameVersionField).setText(Objects.requireNonNullElse(gameVersion, "").trim());

        if (dependencyCategoryId != null && !categoryControls.select(dependencyCategoryId)) {
            if (ready) {
                categoryControls.reset();
            } else {
                categoryControls.defer(dependencyCategoryId);
            }
        }
    }

    /// Starts one asynchronous resolution unless the same instance request is already pending.
    ///
    /// @param instanceId instance whose analyzed context should be applied
    void request(GameInstanceID instanceId) {
        @Nullable InstancesModel availableSource = source;
        if (availableSource == null || instanceId.equals(resolvingInstanceId)) {
            return;
        }
        resolvingInstanceId = instanceId;
        long requestRevision = ++revision;
        try {
            availableSource.resolveAddonContext(instanceId).whenComplete((context, failure) ->
                    EdtDispatcher.execute(() -> applyResult(requestRevision, context, failure)));
        } catch (RuntimeException failure) {
            resolvingInstanceId = null;
            LOG.warning("Failed to request add-on context for " + instanceId, failure);
        }
    }

    /// Clears pending resolution state after the owning catalog closes.
    void clear() {
        resolvingInstanceId = null;
        revision++;
    }

    /// Applies one completed resolution on the EDT while it remains the latest request.
    ///
    /// @param expectedRevision revision captured when the resolution was requested
    /// @param context resolved context, or null when the stage produced no value
    /// @param failure resolution failure, or null on success
    private void applyResult(
            long expectedRevision,
            @Nullable InstanceAddonContext context,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (expectedRevision != revision) {
            return;
        }
        resolvingInstanceId = null;
        if (failure != null) {
            LOG.warning("Failed to resolve instance add-on context", failure);
            return;
        }
        if (context != null) {
            apply(context.gameVersion(), context.modLoader());
        }
    }
}
