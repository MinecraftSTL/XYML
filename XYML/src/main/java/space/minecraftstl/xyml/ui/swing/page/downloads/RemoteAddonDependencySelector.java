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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Compact fixed-height selector for one selected remote Mod version's downloadable prerequisites.
///
/// The component resolves dependency display names on the caller-owned worker executor, retains names
/// only for the current project version, and delegates the provider search command without owning the
/// shared catalog results list.
@NotNullByDefault
final class RemoteAddonDependencySelector extends JPanel implements AutoCloseable {
    /// Blocking provider gateway used only for dependency-name resolution.
    private final RemoteAddonCatalogBackend backend;

    /// Caller-owned worker executor shared with the owning catalog panel.
    private final Executor workerExecutor;

    /// Command opening one dependency identifier through the shared Mod search route.
    private final BiConsumer<RemoteAddon.Dependency, String> searchAction;

    /// Fixed-height selector containing downloadable prerequisite options.
    private final JComboBox<DependencyOption> selector = new JComboBox<>();

    /// Opens the selected prerequisite query in the shared Mod result list.
    private final JButton searchButton = new JButton();

    /// Search button listener retained for deterministic cleanup.
    private final ActionListener searchListener = event -> searchSelectedDependency();

    /// Human-readable names retained for the current selected project version.
    private final Map<DependencyKey, DependencyName> nameCache = new LinkedHashMap<>();

    /// Dependency identifiers currently resolving through the selected provider.
    private final Set<DependencyKey> nameRequests = new LinkedHashSet<>();

    /// Current selected project, or null when no dependency context is active.
    private @Nullable RemoteAddonCatalogItem contextItem;

    /// Current selected version, or null when no dependency context is active.
    private @Nullable RemoteAddon.Version contextVersion;

    /// Monotonic identity for dependency-name resolution callbacks.
    private long requestRevision;

    /// Whether the owning panel currently accepts user commands.
    private boolean inputsEnabled = true;

    /// Whether this component has permanently released its listeners and context.
    private boolean closed;

    /// Creates one compact prerequisite selector.
    ///
    /// @param backend provider gateway used for dependency-name resolution
    /// @param workerExecutor caller-owned background executor
    /// @param searchAction command opening the selected dependency search
    RemoteAddonDependencySelector(
            RemoteAddonCatalogBackend backend,
            Executor workerExecutor,
            BiConsumer<RemoteAddon.Dependency, String> searchAction) {
        EdtDispatcher.requireEventDispatchThread();
        this.backend = Objects.requireNonNull(backend, "backend");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.searchAction = Objects.requireNonNull(searchAction, "searchAction");

        setName("remoteAddonDependencyControls");
        setOpaque(false);
        setMinimumSize(new Dimension(0, 0));
        setLayout(new MigLayout("insets 0, fillx", "[grow,fill][]", "[32!]"));
        setVisible(false);

        selector.setName("remoteAddonDependencies");
        selector.setRenderer(new DependencyRenderer(
                i18n("swing.download.dependency_loading"),
                i18n("swing.download.dependency_unavailable")));
        selector.setMinimumSize(new Dimension(0, 0));
        searchButton.setName("remoteAddonDependencySearch");
        searchButton.setText(i18n("search"));
        searchButton.setMinimumSize(new Dimension(0, 0));
        searchButton.addActionListener(searchListener);

        add(selector, "growx, wmin 0, h 32!");
        add(searchButton, "w 96!, h 32!");
    }

    /// Shows the downloadable prerequisites for one selected project version.
    ///
    /// @param item selected remote Mod project
    /// @param version selected installable version
    /// @return true when the project-version context changed
    boolean showDependencies(RemoteAddonCatalogItem item, RemoteAddon.Version version) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(version, "version");
        if (closed) {
            return false;
        }

        boolean contextChanged = item != contextItem || version != contextVersion;
        if (contextChanged) {
            requestRevision++;
            contextItem = item;
            contextVersion = version;
            clearState();
        }

        long revision = requestRevision;
        @Unmodifiable List<DependencyOption> options = dependencyOptions(item, version);
        publishOptions(options);

        List<DependencyNameRequest> unresolved = new ArrayList<>();
        for (DependencyOption option : options) {
            if (!nameCache.containsKey(option.key()) && nameRequests.add(option.key())) {
                unresolved.add(new DependencyNameRequest(option.key(), option.dependency()));
            }
        }
        if (!unresolved.isEmpty()) {
            scheduleNameResolution(item, version, revision, List.copyOf(unresolved));
        }
        return contextChanged;
    }

    /// Clears the current dependency context and hides the selector without closing the component.
    void clear() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || contextItem == null && contextVersion == null) {
            return;
        }
        requestRevision++;
        contextItem = null;
        contextVersion = null;
        clearState();
        setEnabledState(false);
    }

    /// Reports whether a visible prerequisite can currently be searched.
    ///
    /// @return true when the selector contains visible options
    boolean hasDependencies() {
        EdtDispatcher.requireEventDispatchThread();
        return !closed && selector.getItemCount() > 0 && isVisible();
    }

    /// Reconciles selector availability with the owning panel's command state.
    ///
    /// @param inputsEnabled whether panel inputs currently accept user commands
    void setInputsEnabled(boolean inputsEnabled) {
        EdtDispatcher.requireEventDispatchThread();
        this.inputsEnabled = inputsEnabled;
        if (closed || !inputsEnabled || selector.getItemCount() == 0) {
            setEnabledState(false);
            return;
        }
        selector.setEnabled(true);
        searchButton.setEnabled(selector.getSelectedItem() != null);
    }

    /// Removes the selector listener and clears all per-version state.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        requestRevision++;
        contextItem = null;
        contextVersion = null;
        clearState();
        searchButton.removeActionListener(searchListener);
        setEnabledState(false);
    }

    /// Publishes selector enablement without hiding an otherwise valid option list.
    ///
    /// @param enabled whether the selected prerequisite command may run
    private void setEnabledState(boolean enabled) {
        selector.setEnabled(enabled && selector.getItemCount() > 0);
        searchButton.setEnabled(enabled && selector.getSelectedItem() != null);
    }

    /// Clears all current options and name-resolution state.
    private void clearState() {
        nameCache.clear();
        nameRequests.clear();
        selector.removeAllItems();
        setVisible(false);
        revalidate();
        repaint();
    }

    /// Builds immutable presentation rows for every downloadable prerequisite in stable provider order.
    ///
    /// @param item selected project
    /// @param version selected installable version
    /// @return immutable prerequisite options
    private @Unmodifiable List<DependencyOption> dependencyOptions(
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version) {
        Set<RemoteAddon.DependencyType> downloadable = EnumSet.of(
                RemoteAddon.DependencyType.REQUIRED,
                RemoteAddon.DependencyType.OPTIONAL,
                RemoteAddon.DependencyType.TOOL);
        Set<DependencyKey> identifiers = new LinkedHashSet<>();
        List<DependencyOption> options = new ArrayList<>();
        for (RemoteAddon.Dependency dependency : version.dependencies()) {
            RemoteAddon.DependencyType type = dependency.getType();
            @Nullable String rawId = dependency.getId();
            if (!downloadable.contains(type) || rawId == null || rawId.isBlank()) {
                continue;
            }
            String id = rawId.trim();
            DependencyKey key = new DependencyKey(dependency.getSource(), id, item.source());
            if (!identifiers.add(key)) {
                continue;
            }
            @Nullable DependencyName cached = nameCache.get(key);
            options.add(new DependencyOption(
                    key,
                    dependency,
                    id,
                    cached == null ? null : cached.value(),
                    cached != null && cached.unavailable()));
        }
        return List.copyOf(options);
    }

    /// Publishes compact options while preserving the selected prerequisite when possible.
    ///
    /// @param options immutable prerequisite options
    private void publishOptions(@Unmodifiable List<DependencyOption> options) {
        @Nullable DependencyOption previous = (DependencyOption) selector.getSelectedItem();
        selector.removeAllItems();
        for (DependencyOption option : options) {
            selector.addItem(option);
        }
        if (previous != null) {
            for (int index = 0; index < selector.getItemCount(); index++) {
                if (selector.getItemAt(index).key().equals(previous.key())) {
                    selector.setSelectedIndex(index);
                    break;
                }
            }
        }
        if (selector.getSelectedIndex() < 0 && selector.getItemCount() > 0) {
            selector.setSelectedIndex(0);
        }
        setVisible(!options.isEmpty());
        revalidate();
        repaint();
    }

    /// Resolves names for new prerequisites on the caller-owned worker executor.
    ///
    /// @param item selected project
    /// @param version selected installable version
    /// @param revision dependency context revision
    /// @param requests unresolved dependency requests
    private void scheduleNameResolution(
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version,
            long revision,
            @Unmodifiable List<DependencyNameRequest> requests) {
        try {
            workerExecutor.execute(() -> resolveNames(item, version, revision, requests));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule remote add-on dependency name resolution", schedulingFailure);
            for (DependencyNameRequest request : requests) {
                nameRequests.remove(request.key());
                nameCache.put(request.key(), DependencyName.unavailableValue());
            }
            publishOptions(dependencyOptions(item, version));
        }
    }

    /// Resolves prerequisite names away from the EDT and publishes the complete stable result.
    ///
    /// @param item selected project
    /// @param version selected installable version
    /// @param revision dependency context revision
    /// @param requests unresolved dependency requests
    private void resolveNames(
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version,
            long revision,
            @Unmodifiable List<DependencyNameRequest> requests) {
        Map<DependencyKey, DependencyName> resolved = new LinkedHashMap<>();
        for (DependencyNameRequest request : requests) {
            try {
                @Nullable String displayName = backend.resolveDependencyDisplayName(item, request.dependency());
                resolved.put(
                        request.key(),
                        displayName == null ? DependencyName.unavailableValue() : DependencyName.resolved(displayName));
            } catch (IOException | RuntimeException failure) {
                LOG.warning("Failed to resolve remote add-on dependency name", failure);
                resolved.put(request.key(), DependencyName.unavailableValue());
            }
        }
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> applyNames(item, version, revision, requests, resolved));
    }

    /// Publishes dependency names only while the originating project version remains selected.
    ///
    /// @param item originating project
    /// @param version originating version
    /// @param revision dependency context revision
    /// @param requests resolved request identities
    /// @param resolved immutable name results
    private void applyNames(
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version,
            long revision,
            @Unmodifiable List<DependencyNameRequest> requests,
            Map<DependencyKey, DependencyName> resolved) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || requestRevision != revision || contextItem != item || contextVersion != version) {
            return;
        }
        for (DependencyNameRequest request : requests) {
            nameRequests.remove(request.key());
            nameCache.put(
                    request.key(),
                    resolved.getOrDefault(request.key(), DependencyName.unavailableValue()));
        }
        publishOptions(dependencyOptions(item, version));
        setInputsEnabled(inputsEnabled);
    }

    /// Opens the selected prerequisite through the owning panel's shared result list.
    private void searchSelectedDependency() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable Object selected = selector.getSelectedItem();
        if (selected instanceof DependencyOption option) {
            searchAction.accept(option.dependency(), option.identifier());
        }
    }

    /// One identifiable prerequisite option displayed in the compact selector.
    ///
    /// @param key stable source and project identity
    /// @param dependency provider dependency metadata
    /// @param identifier non-blank provider project identifier
    /// @param displayName resolved human-readable name, or null while loading
    /// @param unavailable whether provider resolution completed without a name
    @NotNullByDefault
    private record DependencyOption(
            DependencyKey key,
            RemoteAddon.Dependency dependency,
            String identifier,
            @Nullable String displayName,
            boolean unavailable) {
        /// Validates one option.
        private DependencyOption {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(dependency, "dependency");
            identifier = Objects.requireNonNull(identifier, "identifier");
            if (identifier.isBlank()) {
                throw new IllegalArgumentException("identifier must not be blank");
            }
        }

        /// Returns the compact selector text for the current resolution state.
        ///
        /// @param loadingText text shown while resolution is pending
        /// @param unavailableText text shown after a failed resolution
        /// @return resolved or fallback display text
        private String displayText(String loadingText, String unavailableText) {
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return unavailable ? unavailableText : loadingText;
        }
    }

    /// One resolved or unavailable prerequisite name.
    ///
    /// @param value resolved display name, or null when unavailable
    /// @param unavailable whether the provider could not produce a name
    @NotNullByDefault
    private record DependencyName(@Nullable String value, boolean unavailable) {
        /// Validates one name state.
        private DependencyName {
            if (unavailable) {
                if (value != null) {
                    throw new IllegalArgumentException("unavailable name must not carry a value");
                }
            } else if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("resolved name must not be blank");
            }
        }

        /// Creates one resolved dependency name.
        ///
        /// @param value non-blank resolved name
        /// @return resolved name state
        private static DependencyName resolved(String value) {
            return new DependencyName(value, false);
        }

        /// Creates one unavailable dependency name.
        ///
        /// @return unavailable name state
        private static DependencyName unavailableValue() {
            return new DependencyName(null, true);
        }
    }

    /// Stable identity for one provider dependency name.
    ///
    /// @param dependencySource declared dependency source, or null when inherited
    /// @param identifier provider project identifier
    /// @param fallbackSource catalog source used when the dependency omits a source
    @NotNullByDefault
    private record DependencyKey(
            @Nullable RemoteAddon.Source dependencySource,
            String identifier,
            RemoteAddonCatalogSource fallbackSource) {
        /// Validates one dependency key.
        private DependencyKey {
            identifier = Objects.requireNonNull(identifier, "identifier");
            fallbackSource = Objects.requireNonNull(fallbackSource, "fallbackSource");
            if (identifier.isBlank()) {
                throw new IllegalArgumentException("identifier must not be blank");
            }
        }
    }

    /// One dependency name resolution request.
    ///
    /// @param key stable dependency identity
    /// @param dependency provider dependency metadata
    @NotNullByDefault
    private record DependencyNameRequest(DependencyKey key, RemoteAddon.Dependency dependency) {
        /// Validates one resolution request.
        private DependencyNameRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(dependency, "dependency");
        }
    }

    /// Renders prerequisite names with loading, unavailable, and provider-tooltip fallbacks.
    @NotNullByDefault
    private static final class DependencyRenderer extends DefaultListCellRenderer {
        /// Text shown while provider resolution is pending.
        private final String loadingText;

        /// Text shown when provider resolution cannot produce a name.
        private final String unavailableText;

        /// Creates one reusable prerequisite renderer.
        ///
        /// @param loadingText loading-state text
        /// @param unavailableText unavailable-state text
        private DependencyRenderer(String loadingText, String unavailableText) {
            this.loadingText = Objects.requireNonNull(loadingText, "loadingText");
            this.unavailableText = Objects.requireNonNull(unavailableText, "unavailableText");
        }

        /// Renders one prerequisite option without exposing a raw identifier as its primary label.
        ///
        /// @param list owning selector
        /// @param value dependency option, or null before selection
        /// @param index row index
        /// @param isSelected whether the row is selected
        /// @param cellHasFocus whether the row owns focus
        /// @return configured renderer component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Component component = super.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    isSelected,
                    cellHasFocus);
            setIcon(null);
            setText("");
            setToolTipText(null);
            if (value instanceof DependencyOption option) {
                setText(option.displayText(loadingText, unavailableText));
                setToolTipText(i18n("addon.dependency."
                        + option.dependency().getType().name().toLowerCase(Locale.ROOT))
                        + " (" + option.identifier() + ")");
            }
            return component;
        }
    }
}
