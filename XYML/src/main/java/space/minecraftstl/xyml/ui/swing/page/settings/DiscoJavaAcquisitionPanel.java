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
package space.minecraftstl.xyml.ui.swing.page.settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.Color;
import java.awt.Component;
import java.net.URI;
import java.util.EnumMap;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents the explicit, lazily loaded third-party Java distribution workflow.
///
/// Distribution and package controls never invent a selection. Remote results are cached only after a successful
/// explicit `(distribution, package type)` request, while the version list delegates all local slicing and retention
/// to [ViewportChoiceList]'s measured viewport strategy.
@NotNullByDefault
final class DiscoJavaAcquisitionPanel extends JPanel implements AutoCloseable {
    /// Parent-owned operation and integration listener.
    private final Listener listener;

    /// Immutable distributions supported by the service's exact platform.
    private final @Unmodifiable List<DiscoJavaDistribution> distributions;

    /// Immutable external download choices for exceptional platforms.
    private final @Unmodifiable List<JavaRuntimePlatformLinks.Link> platformLinks;

    /// Compact explicit distribution choice.
    private final JComboBox<DiscoJavaDistribution> distributionChoice = new JComboBox<>();

    /// Compact explicit JDK or JRE package choice.
    private final JComboBox<JavaPackageType> packageChoice = new JComboBox<>();

    /// Mutable immutable-snapshot source behind the viewport-driven remote version list.
    private final VersionDataSource versionDataSource = new VersionDataSource();

    /// Viewport-driven single-select remote version list.
    private final ViewportChoiceList<DiscoJavaRemoteVersion> versionChoice =
            new ViewportChoiceList<>(versionDataSource, DiscoJavaAcquisitionPanel::versionText);

    /// Inline loading, empty, failure, or selection guidance.
    private final JLabel loadStatusLabel = new JLabel(" ");

    /// Explicit retry command shown only after the current selection fails.
    private final JButton retryButton = new JButton(i18n("button.retry"));

    /// Holds the suggested or user-edited managed runtime name.
    private final JTextField installNameField = new JTextField();

    /// Shows exact managed-name validation feedback.
    private final JLabel installNameStatusLabel = new JLabel(" ");

    /// Contains the name confirmation controls shown only after an explicit version choice.
    private final JPanel installNameArea = new JPanel(new MigLayout(
            "insets 0, fillx, wrap 1",
            "[grow,fill]",
            "[]2[]"));

    /// Starts the parent-owned download and safe installation pipeline.
    private final JButton installButton = new JButton(i18n("button.install"));

    /// External platform commands rendered without direct desktop access.
    private final JPanel externalLinksArea = new JPanel(new MigLayout("insets 0, gap 6", "[]", "[]"));

    /// Tracks sparse model publications so retained explicit selections can be restored after loading.
    private final ListDataListener versionDataListener = new VersionDataListener();

    /// Tracks user edits to the install-name confirmation field.
    private final DocumentListener installNameListener = new InstallNameDocumentListener();

    /// Successfully loaded immutable results keyed by the exact explicit selection.
    private final Map<SelectionKey, @Unmodifiable List<DiscoJavaRemoteVersion>> successfulResults =
            new HashMap<>();

    /// Explicit package choices retained independently for each distribution.
    private final Map<DiscoJavaDistribution, JavaPackageType> explicitPackageSelections =
            new EnumMap<>(DiscoJavaDistribution.class);

    /// Explicit remote version identities retained independently for each loaded selection.
    private final Map<SelectionKey, String> explicitVersionSelections = new HashMap<>();

    /// Selection keys whose latest explicit request failed and therefore require an explicit retry.
    private final Set<SelectionKey> failedSelections = new HashSet<>();

    /// Distribution explicitly selected by the user, or null before selection.
    private @Nullable DiscoJavaDistribution selectedDistribution;

    /// Package type explicitly selected by the user, or null before selection.
    private @Nullable JavaPackageType selectedPackageType;

    /// Version explicitly selected by the user and still valid for the current loaded result.
    private @Nullable DiscoJavaRemoteVersion selectedVersion;

    /// Current selection key represented by the version source, or null before package selection.
    private @Nullable SelectionKey displayedSelection;

    /// Current selection key awaiting a remote result, or null while idle.
    private @Nullable SelectionKey loadingSelection;

    /// Monotonic identity attached to version requests so late results cannot replace newer state.
    private long versionLoadRevision;

    /// Monotonic identity attached to version and install-name selection state.
    private long installSelectionRevision;

    /// Exact install name most recently accepted for the current explicit version.
    private @Nullable String validatedInstallName;

    /// Suppresses callbacks while programmatic control updates restore explicit state.
    private boolean adjustingControls;

    /// Suppresses document callbacks while a parent-supplied suggestion is applied.
    private boolean applyingInstallName;

    /// Whether an acquisition install operation prevents user input.
    private boolean busy;

    /// Whether this component has released resources and rejects late results.
    private boolean closed;

    /// Creates the third-party workflow from immutable local capability data.
    ///
    /// @param supportedDistributions exact platform-supported distributions
    /// @param externalLinks external choices applicable to the same platform
    /// @param listener parent-owned operation listener
    DiscoJavaAcquisitionPanel(
            @Unmodifiable List<DiscoJavaDistribution> supportedDistributions,
            @Unmodifiable List<JavaRuntimePlatformLinks.Link> externalLinks,
            Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        distributions = List.copyOf(Objects.requireNonNull(supportedDistributions, "supportedDistributions"));
        platformLinks = List.copyOf(Objects.requireNonNull(externalLinks, "externalLinks"));
        configureComponents();
        showDistributionPrompt();
    }

    /// Returns whether the card can offer either an integrated distribution or a platform download page.
    ///
    /// @return whether this card has any actionable content
    boolean hasContent() {
        return !distributions.isEmpty() || !platformLinks.isEmpty();
    }

    /// Returns the explicitly selected distribution, or null before selection.
    ///
    /// @return explicit distribution selection
    @Nullable DiscoJavaDistribution selectedDistribution() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedDistribution;
    }

    /// Returns the explicitly selected package type, or null before selection.
    ///
    /// @return explicit package selection
    @Nullable JavaPackageType selectedPackageType() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedPackageType;
    }

    /// Returns the explicitly selected loaded version, or null before selection.
    ///
    /// @return explicit version selection
    @Nullable DiscoJavaRemoteVersion selectedVersion() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedVersion;
    }

    /// Applies one successful immutable remote result when it still matches the active request.
    ///
    /// @param revision request revision
    /// @param distribution requested distribution
    /// @param packageType requested package type
    /// @param versions immutable newest-first versions
    void applyVersions(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            @Unmodifiable List<DiscoJavaRemoteVersion> versions) {
        EdtDispatcher.requireEventDispatchThread();
        SelectionKey key = new SelectionKey(distribution, packageType);
        if (!acceptsVersionResult(revision, key)) {
            return;
        }
        @Unmodifiable List<DiscoJavaRemoteVersion> immutableVersions = List.copyOf(
                Objects.requireNonNull(versions, "versions"));
        successfulResults.put(key, immutableVersions);
        failedSelections.remove(key);
        loadingSelection = null;
        displayVersions(key, immutableVersions);
    }

    /// Applies one failed remote request when it still matches the active selection and revision.
    ///
    /// @param revision request revision
    /// @param distribution requested distribution
    /// @param packageType requested package type
    void applyVersionLoadFailure(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        EdtDispatcher.requireEventDispatchThread();
        SelectionKey key = new SelectionKey(distribution, packageType);
        if (!acceptsVersionResult(revision, key)) {
            return;
        }
        loadingSelection = null;
        failedSelections.add(key);
        versionDataSource.replace(List.of());
        versionChoice.reloadData();
        selectedVersion = null;
        displayedSelection = key;
        loadStatusLabel.setText(i18n("java.download.disco.failed"));
        retryButton.setVisible(true);
        updateActionAvailability();
    }

    /// Applies a service-derived name suggestion and validation result for the exact selected version.
    ///
    /// @param revision install selection revision
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @param version selected remote version
    /// @param suggestion suggested managed-runtime name
    /// @param status validation result for the suggestion
    void applyInstallNameSuggestion(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version,
            String suggestion,
            JavaRuntimeInstallNameStatus status) {
        EdtDispatcher.requireEventDispatchThread();
        if (!acceptsInstallResult(revision, distribution, packageType, version)) {
            return;
        }
        String candidate = Objects.requireNonNull(suggestion, "suggestion");
        applyingInstallName = true;
        try {
            installNameField.setText(candidate);
        } finally {
            applyingInstallName = false;
        }
        applyInstallNameStatus(revision, distribution, packageType, version, candidate, status);
    }

    /// Applies one exact parent-owned managed-name validation result.
    ///
    /// @param revision install selection revision
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @param version selected remote version
    /// @param candidate exact trimmed candidate
    /// @param status validation result
    void applyInstallNameStatus(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version,
            String candidate,
            JavaRuntimeInstallNameStatus status) {
        EdtDispatcher.requireEventDispatchThread();
        String validatedCandidate = Objects.requireNonNull(candidate, "candidate");
        JavaRuntimeInstallNameStatus validationStatus = Objects.requireNonNull(status, "status");
        if (!acceptsInstallResult(revision, distribution, packageType, version)
                || !validatedCandidate.equals(normalizedInstallName())) {
            return;
        }
        if (validationStatus == JavaRuntimeInstallNameStatus.VALID) {
            validatedInstallName = validatedCandidate;
            clearNameStatus();
        } else {
            validatedInstallName = null;
            showNameStatus(validationStatus);
        }
        updateActionAvailability();
    }

    /// Enables or disables all explicit input around installation operations.
    ///
    /// Version discovery uses its own inline state and does not freeze unrelated choices.
    ///
    /// @param newBusy whether an installation operation is active
    void setBusy(boolean newBusy) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            busy = newBusy;
            updateActionAvailability();
        }
    }

    /// Cancels remote loading and releases the viewport list and document listeners.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        versionLoadRevision++;
        installSelectionRevision++;
        listener.versionLoadCancellationRequested();
        versionChoice.getList().getModel().removeListDataListener(versionDataListener);
        installNameField.getDocument().removeDocumentListener(installNameListener);
        versionChoice.close();
        updateActionAvailability();
    }

    /// Builds the stable explicit-selection form and platform commands.
    private void configureComponents() {
        setName("javaManagementAcquireDiscoView");
        setOpaque(false);
        setLayout(new MigLayout(
                "insets 8 0 0 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[]8[grow,fill]8[]8[]"));

        add(createChoiceRow(), "growx");
        add(createLoadStatusRow(), "growx");
        configureVersionList();
        add(versionChoice, "grow, push, hmin 120");
        configureInstallNameArea();
        add(installNameArea, "growx");
        add(createActionsRow(), "growx");

        configureChoices();
        configureButtons();
    }

    /// Creates the compact distribution and package choice row.
    ///
    /// @return configured choice row
    private JPanel createChoiceRow() {
        JPanel row = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill]12[][grow,fill]",
                "[]"));
        row.setOpaque(false);
        JLabel distributionLabel = new JLabel(i18n("java.download.distribution"));
        distributionLabel.setLabelFor(distributionChoice);
        row.add(distributionLabel);
        row.add(distributionChoice, "wmin 160");
        JLabel packageLabel = new JLabel(i18n("java.download.packageType"));
        packageLabel.setLabelFor(packageChoice);
        row.add(packageLabel);
        row.add(packageChoice, "wmin 110");
        return row;
    }

    /// Creates inline load feedback with a compact retry command.
    ///
    /// @return configured status row
    private JPanel createLoadStatusRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        row.setOpaque(false);
        loadStatusLabel.setName("javaManagementAcquireDiscoStatus");
        row.add(loadStatusLabel, "growx");
        row.add(retryButton);
        return row;
    }

    /// Configures the version choice list and explicit-selection tracking.
    private void configureVersionList() {
        versionChoice.setName("javaManagementAcquireDiscoVersions");
        versionChoice.setBorder(BorderFactory.createEmptyBorder());
        versionChoice.getList().setName("javaManagementAcquireDiscoVersionList");
        versionChoice.getList().getModel().addListDataListener(versionDataListener);
        versionChoice.getList().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !adjustingControls) {
                versionSelectionChanged();
            }
        });
    }

    /// Configures the initially hidden install-name confirmation area.
    private void configureInstallNameArea() {
        installNameArea.setName("javaManagementAcquireDiscoInstallNameArea");
        installNameArea.setOpaque(false);
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[120!,fill][grow,fill]", "[]"));
        row.setOpaque(false);
        JLabel label = new JLabel(i18n("java.install.name"));
        label.setLabelFor(installNameField);
        row.add(label);
        installNameField.setName("javaManagementAcquireDiscoInstallName");
        SwingTextFields.showClearButton(installNameField);
        row.add(installNameField, "growx");
        installNameArea.add(row, "growx");
        installNameStatusLabel.setName("javaManagementAcquireDiscoInstallNameStatus");
        installNameArea.add(installNameStatusLabel, "gapleft 128, growx, h 22!");
        installNameField.getDocument().addDocumentListener(installNameListener);
        installNameArea.setVisible(false);
    }

    /// Creates platform links and the final installation command row.
    ///
    /// @return configured action row
    private JPanel createActionsRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        row.setOpaque(false);
        externalLinksArea.setName("javaManagementAcquireDiscoExternalLinks");
        externalLinksArea.setOpaque(false);
        for (int index = 0; index < platformLinks.size(); index++) {
            JavaRuntimePlatformLinks.Link link = platformLinks.get(index);
            JButton button = new JButton(i18n(link.labelKey()));
            button.setName("javaManagementAcquireExternalLink" + index);
            button.setIcon(themeIcon("assets/swing/icons/open-in-new.svg"));
            button.setToolTipText(button.getText());
            button.getAccessibleContext().setAccessibleName(button.getText());
            button.addActionListener(event -> openExternalLink(link.uri()));
            externalLinksArea.add(button);
        }
        row.add(externalLinksArea, "growx");
        row.add(installButton);
        return row;
    }

    /// Configures both compact combo boxes without implicit first-item selection.
    private void configureChoices() {
        distributionChoice.setName("javaManagementAcquireDiscoDistribution");
        distributionChoice.setModel(new DefaultComboBoxModel<>(
                distributions.toArray(DiscoJavaDistribution[]::new)));
        distributionChoice.setSelectedIndex(-1);
        distributionChoice.setRenderer(new ChoiceRenderer<>(DiscoJavaDistribution::getDisplayName));
        distributionChoice.getAccessibleContext().setAccessibleName(i18n("java.download.distribution"));
        distributionChoice.addActionListener(event -> distributionSelectionChanged());

        packageChoice.setName("javaManagementAcquireDiscoPackageType");
        packageChoice.setModel(new DefaultComboBoxModel<>());
        packageChoice.setSelectedIndex(-1);
        packageChoice.setRenderer(new ChoiceRenderer<>(JavaPackageType::getDisplayName));
        packageChoice.getAccessibleContext().setAccessibleName(i18n("java.download.packageType"));
        packageChoice.addActionListener(event -> packageSelectionChanged());
    }

    /// Configures retry and installation icons, labels, and commands.
    private void configureButtons() {
        retryButton.setName("javaManagementAcquireDiscoRetry");
        retryButton.setIcon(themeIcon("assets/swing/icons/refresh.svg"));
        retryButton.setToolTipText(retryButton.getText());
        retryButton.getAccessibleContext().setAccessibleName(retryButton.getText());
        retryButton.addActionListener(event -> retryVersionLoad());
        retryButton.setVisible(false);

        installButton.setName("javaManagementAcquireDiscoInstall");
        installButton.setIcon(themeIcon("assets/swing/icons/file-import.svg"));
        installButton.setToolTipText(installButton.getText());
        installButton.getAccessibleContext().setAccessibleName(installButton.getText());
        installButton.addActionListener(event -> installSelectedVersion());
    }

    /// Handles an explicit distribution choice and restores only lower choices previously made by the user.
    private void distributionSelectionChanged() {
        if (closed || busy || adjustingControls) {
            return;
        }
        @Nullable DiscoJavaDistribution distribution = selectedItem(
                distributionChoice,
                DiscoJavaDistribution.class);
        selectedDistribution = distribution;
        selectedPackageType = null;
        selectedVersion = null;
        invalidateInstallSelection();
        cancelCurrentLoad();

        adjustingControls = true;
        try {
            @Unmodifiable List<JavaPackageType> packageTypes = distribution == null
                    ? List.of()
                    : listener.supportedPackageTypes(distribution).stream()
                            .filter(packageType -> !packageType.isJavaFXBundled())
                            .distinct()
                            .toList();
            packageChoice.setModel(new DefaultComboBoxModel<>(packageTypes.toArray(JavaPackageType[]::new)));
            @Nullable JavaPackageType retained = distribution == null
                    ? null
                    : explicitPackageSelections.get(distribution);
            if (retained != null && packageTypes.contains(retained)) {
                packageChoice.setSelectedItem(retained);
                selectedPackageType = retained;
            } else {
                packageChoice.setSelectedIndex(-1);
            }
        } finally {
            adjustingControls = false;
        }

        if (distribution == null) {
            showDistributionPrompt();
        } else if (selectedPackageType == null) {
            showPackagePrompt();
        } else {
            showSelection(new SelectionKey(distribution, selectedPackageType), true);
        }
        updateActionAvailability();
    }

    /// Handles an explicit package choice and loads or restores its exact immutable result.
    private void packageSelectionChanged() {
        if (closed || busy || adjustingControls) {
            return;
        }
        @Nullable DiscoJavaDistribution distribution = selectedDistribution;
        @Nullable JavaPackageType packageType = selectedItem(packageChoice, JavaPackageType.class);
        selectedPackageType = packageType;
        selectedVersion = null;
        invalidateInstallSelection();
        cancelCurrentLoad();
        if (distribution == null || packageType == null) {
            showPackagePrompt();
            return;
        }
        explicitPackageSelections.put(distribution, packageType);
        showSelection(new SelectionKey(distribution, packageType), true);
    }

    /// Displays one cached, failed, or newly requested explicit selection.
    ///
    /// @param key exact selection
    /// @param allowRequest whether this transition represents explicit user input eligible to load
    private void showSelection(SelectionKey key, boolean allowRequest) {
        @Nullable List<DiscoJavaRemoteVersion> cached = successfulResults.get(key);
        if (cached != null) {
            displayVersions(key, cached);
        } else if (failedSelections.contains(key)) {
            displayedSelection = key;
            versionDataSource.replace(List.of());
            versionChoice.reloadData();
            loadStatusLabel.setText(i18n("java.download.disco.failed"));
            retryButton.setVisible(true);
            updateActionAvailability();
        } else if (allowRequest) {
            requestVersions(key);
        } else {
            displayedSelection = key;
            versionDataSource.replace(List.of());
            versionChoice.reloadData();
            loadStatusLabel.setText(i18n("java.download.disco.choose_package"));
            retryButton.setVisible(false);
            updateActionAvailability();
        }
    }

    /// Starts one revisioned parent-owned version request for an explicit selection.
    private void requestVersions(SelectionKey key) {
        loadingSelection = key;
        displayedSelection = key;
        selectedVersion = null;
        long revision = ++versionLoadRevision;
        versionDataSource.replace(List.of());
        versionChoice.reloadData();
        loadStatusLabel.setText(i18n("java.download.disco.loading"));
        retryButton.setVisible(false);
        updateActionAvailability();
        listener.versionsRequested(revision, key.distribution(), key.packageType());
    }

    /// Retries only the currently failed explicit selection.
    private void retryVersionLoad() {
        if (closed || busy) {
            return;
        }
        @Nullable SelectionKey key = currentSelectionKey();
        if (key == null || !failedSelections.remove(key)) {
            return;
        }
        requestVersions(key);
    }

    /// Publishes one immutable version list through the local viewport data source.
    private void displayVersions(
            SelectionKey key,
            @Unmodifiable List<DiscoJavaRemoteVersion> versions) {
        displayedSelection = key;
        versionDataSource.replace(versions);
        versionChoice.reloadData();
        loadStatusLabel.setText(versions.isEmpty()
                ? i18n("java.download.disco.empty")
                : i18n("java.download.disco.choose_version"));
        retryButton.setVisible(false);
        restoreExplicitVersionSelection(key, versions);
        updateActionAvailability();
    }

    /// Restores only a version previously selected by the user and still present in the current result.
    private void restoreExplicitVersionSelection(
            SelectionKey key,
            @Unmodifiable List<DiscoJavaRemoteVersion> versions) {
        @Nullable String retainedId = explicitVersionSelections.get(key);
        int retainedIndex = indexOfVersionId(versions, retainedId);
        adjustingControls = true;
        try {
            if (retainedIndex < 0) {
                versionChoice.getList().clearSelection();
                selectedVersion = null;
                if (retainedId != null) {
                    explicitVersionSelections.remove(key);
                }
            } else {
                versionChoice.getList().setSelectedIndex(retainedIndex);
                selectedVersion = versions.get(retainedIndex);
                requestInstallNameSuggestion();
            }
        } finally {
            adjustingControls = false;
        }
    }

    /// Records one loaded explicit version selection without selecting placeholders or defaults.
    private void versionSelectionChanged() {
        @Nullable SelectionKey key = currentSelectionKey();
        @Nullable DiscoJavaRemoteVersion version = versionChoice.getSelectedValue();
        selectedVersion = version;
        invalidateInstallSelection();
        if (key != null && version != null) {
            explicitVersionSelections.put(key, version.getId());
            requestInstallNameSuggestion();
        }
        updateActionAvailability();
    }

    /// Requests a service-owned suggestion and exact local name validation for the current version.
    private void requestInstallNameSuggestion() {
        @Nullable DiscoJavaDistribution distribution = selectedDistribution;
        @Nullable JavaPackageType packageType = selectedPackageType;
        @Nullable DiscoJavaRemoteVersion version = selectedVersion;
        if (distribution == null || packageType == null || version == null) {
            return;
        }
        long revision = ++installSelectionRevision;
        installNameArea.setVisible(true);
        applyingInstallName = true;
        try {
            installNameField.setText("");
        } finally {
            applyingInstallName = false;
        }
        clearNameStatus();
        listener.installNameSuggestionRequested(revision, distribution, packageType, version);
    }

    /// Requests validation after one explicit install-name edit.
    private void installNameChanged() {
        if (closed || applyingInstallName) {
            return;
        }
        validatedInstallName = null;
        clearNameStatus();
        @Nullable DiscoJavaDistribution distribution = selectedDistribution;
        @Nullable JavaPackageType packageType = selectedPackageType;
        @Nullable DiscoJavaRemoteVersion version = selectedVersion;
        if (distribution != null && packageType != null && version != null) {
            listener.installNameValidationRequested(
                    installSelectionRevision,
                    distribution,
                    packageType,
                    version,
                    normalizedInstallName());
        }
        updateActionAvailability();
    }

    /// Starts installation only for the exact selected version and accepted current name.
    private void installSelectedVersion() {
        @Nullable DiscoJavaDistribution distribution = selectedDistribution;
        @Nullable JavaPackageType packageType = selectedPackageType;
        @Nullable DiscoJavaRemoteVersion version = selectedVersion;
        @Nullable String name = validatedInstallName;
        if (closed || busy || distribution == null || packageType == null || version == null || name == null) {
            return;
        }
        listener.installRequested(distribution, packageType, version, name);
    }

    /// Forwards one immutable validated HTTPS platform link to the parent interaction boundary.
    ///
    /// @param uri target URI
    private void openExternalLink(URI uri) {
        if (!closed && !busy) {
            listener.externalLinkRequested(Objects.requireNonNull(uri, "uri"));
        }
    }

    /// Clears the current install-name state whenever an upper selection changes.
    private void invalidateInstallSelection() {
        installSelectionRevision++;
        validatedInstallName = null;
        applyingInstallName = true;
        try {
            installNameField.setText("");
        } finally {
            applyingInstallName = false;
        }
        clearNameStatus();
        installNameArea.setVisible(false);
    }

    /// Cancels the current parent-owned remote request and invalidates its revision.
    private void cancelCurrentLoad() {
        if (loadingSelection != null) {
            loadingSelection = null;
            versionLoadRevision++;
            listener.versionLoadCancellationRequested();
        }
    }

    /// Shows the initial distribution selection guidance.
    private void showDistributionPrompt() {
        displayedSelection = null;
        versionDataSource.replace(List.of());
        versionChoice.reloadData();
        loadStatusLabel.setText(distributions.isEmpty()
                ? i18n("java.download.disco.unavailable")
                : i18n("java.download.disco.choose_distribution"));
        retryButton.setVisible(false);
        updateActionAvailability();
    }

    /// Shows package selection guidance after one explicit distribution choice.
    private void showPackagePrompt() {
        displayedSelection = null;
        versionDataSource.replace(List.of());
        versionChoice.reloadData();
        loadStatusLabel.setText(i18n("java.download.disco.choose_package"));
        retryButton.setVisible(false);
        updateActionAvailability();
    }

    /// Reconciles enabled state from closure, installation activity, and exact selections.
    private void updateActionAvailability() {
        boolean interactive = !closed && !busy;
        distributionChoice.setEnabled(interactive && !distributions.isEmpty());
        packageChoice.setEnabled(interactive && selectedDistribution != null && packageChoice.getItemCount() > 0);
        versionChoice.setEnabled(interactive && loadingSelection == null);
        versionChoice.getList().setEnabled(interactive && loadingSelection == null);
        retryButton.setEnabled(interactive && retryButton.isVisible());
        installNameField.setEnabled(interactive && selectedVersion != null);
        installButton.setEnabled(interactive
                && selectedVersion != null
                && validatedInstallName != null
                && validatedInstallName.equals(normalizedInstallName()));
        for (Component component : externalLinksArea.getComponents()) {
            if (component instanceof AbstractButton button) {
                button.setEnabled(interactive);
            }
        }
    }

    /// Returns whether one version result still belongs to the current active request.
    private boolean acceptsVersionResult(long revision, SelectionKey key) {
        return !closed
                && revision == versionLoadRevision
                && key.equals(loadingSelection)
                && key.equals(currentSelectionKey());
    }

    /// Returns whether one name result still belongs to the current exact version and candidate revision.
    private boolean acceptsInstallResult(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version) {
        return !closed
                && revision == installSelectionRevision
                && distribution == selectedDistribution
                && packageType == selectedPackageType
                && sameVersion(version, selectedVersion);
    }

    /// Returns the exact current distribution/package key, or null when either choice is absent.
    private @Nullable SelectionKey currentSelectionKey() {
        @Nullable DiscoJavaDistribution distribution = selectedDistribution;
        @Nullable JavaPackageType packageType = selectedPackageType;
        return distribution == null || packageType == null ? null : new SelectionKey(distribution, packageType);
    }

    /// Returns the current trimmed installation name.
    private String normalizedInstallName() {
        return installNameField.getText().trim();
    }

    /// Shows one exact invalid-name classification.
    ///
    /// @param status invalid status
    private void showNameStatus(JavaRuntimeInstallNameStatus status) {
        String text = switch (Objects.requireNonNull(status, "status")) {
            case INVALID_CHARACTERS,
                    RESERVED_MOJANG_PREFIX,
                    RESERVED_PLATFORM_NAME,
                    UNSAFE_PATH -> i18n("java.install.warning.invalid_character");
            case ALREADY_INSTALLED -> i18n("java.install.failed.exists");
            case VALID -> "";
        };
        installNameStatusLabel.setText(text.isEmpty() ? " " : text);
        installNameField.putClientProperty("JComponent.outline", text.isEmpty() ? null : "error");
    }

    /// Clears inline name validation styling.
    private void clearNameStatus() {
        installNameStatusLabel.setText(" ");
        installNameField.putClientProperty("JComponent.outline", null);
    }

    /// Returns one typed combo-box selection without relying on an implicit cast.
    ///
    /// @param comboBox source combo box
    /// @param expectedType expected item type
    /// @param <T> item type
    /// @return selected item, or null
    private static <T> @Nullable T selectedItem(JComboBox<T> comboBox, Class<T> expectedType) {
        @Nullable Object selected = Objects.requireNonNull(comboBox, "comboBox").getSelectedItem();
        return Objects.requireNonNull(expectedType, "expectedType").isInstance(selected)
                ? expectedType.cast(selected)
                : null;
    }

    /// Returns a stable remote-version row label with LTS state when supplied by Disco.
    ///
    /// @param version remote version
    /// @return visible row text
    private static String versionText(DiscoJavaRemoteVersion version) {
        DiscoJavaRemoteVersion remote = Objects.requireNonNull(version, "version");
        String label = "Java " + remote.getMajorVersion() + " - " + remote.getJavaVersion();
        return remote.isLTS() ? label + " (LTS)" : label;
    }

    /// Finds one stable remote version identity in an immutable result.
    ///
    /// @param versions immutable versions
    /// @param id retained version identity, or null
    /// @return matching index, or `-1`
    private static int indexOfVersionId(
            @Unmodifiable List<DiscoJavaRemoteVersion> versions,
            @Nullable String id) {
        if (id == null) {
            return -1;
        }
        for (int index = 0; index < versions.size(); index++) {
            if (id.equals(versions.get(index).getId())) {
                return index;
            }
        }
        return -1;
    }

    /// Compares remote versions by stable Disco identity.
    ///
    /// @param left first version, possibly null
    /// @param right second version, possibly null
    /// @return whether both represent the same non-null identity
    private static boolean sameVersion(
            @Nullable DiscoJavaRemoteVersion left,
            @Nullable DiscoJavaRemoteVersion right) {
        return left != null && right != null && Objects.equals(left.getId(), right.getId());
    }

    /// Creates a component-foreground-filtered bundled SVG icon for light and dark themes.
    ///
    /// @param resource classpath SVG resource
    /// @return configured icon
    private static FlatSVGIcon themeIcon(String resource) {
        FlatSVGIcon icon = new FlatSVGIcon(Objects.requireNonNull(resource, "resource"), 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(DiscoJavaAcquisitionPanel::resolveIconColor));
        return icon;
    }

    /// Resolves one icon color from its owning component's current foreground.
    ///
    /// @param component icon owner, or null during standalone rendering
    /// @param originalColor SVG fallback color
    /// @return active component foreground or fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color fallback = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        return foreground == null ? fallback : foreground;
    }

    /// Identifies one exact explicit version-list request and cache entry.
    ///
    /// @param distribution selected distribution
    /// @param packageType selected non-JavaFX package type
    @NotNullByDefault
    private record SelectionKey(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        /// Validates the immutable exact selection.
        private SelectionKey {
            Objects.requireNonNull(distribution, "distribution");
            Objects.requireNonNull(packageType, "packageType");
            if (packageType.isJavaFXBundled()) {
                throw new IllegalArgumentException("JavaFX-bundled packages are not available in Swing acquisition");
            }
        }
    }

    /// Parent-owned operations emitted by the third-party input surface.
    @NotNullByDefault
    interface Listener {
        /// Returns the service-authoritative package choices for one explicit distribution.
        ///
        /// @param distribution explicit distribution
        /// @return immutable package choices
        @Unmodifiable List<JavaPackageType> supportedPackageTypes(DiscoJavaDistribution distribution);

        /// Requests versions for one exact explicit choice and revision.
        void versionsRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType);

        /// Requests cancellation of the currently running version fetch, if any.
        void versionLoadCancellationRequested();

        /// Requests a service-owned suggested name and its immediate validation.
        void installNameSuggestionRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version);

        /// Requests exact validation of one edited managed-runtime name.
        void installNameValidationRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String candidate);

        /// Requests download and safe installation of one validated explicit version.
        void installRequested(
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String installName);

        /// Requests opening one immutable HTTPS platform destination.
        void externalLinkRequested(URI uri);
    }

    /// Renders compact choice values with their product-facing display names.
    ///
    /// @param <T> choice type
    @NotNullByDefault
    private static final class ChoiceRenderer<T> extends DefaultListCellRenderer {
        /// Serialization version for the Swing renderer.
        private static final long serialVersionUID = 1L;

        /// Text provider for non-null choice values.
        private final Function<T, String> textProvider;

        /// Creates one renderer using the supplied display-text provider.
        ///
        /// @param textProvider display-text provider
        private ChoiceRenderer(Function<T, String> textProvider) {
            this.textProvider = Objects.requireNonNull(textProvider, "textProvider");
        }

        /// Renders a nullable combo-box item without manufacturing a selected value.
        ///
        /// @param list owning list
        /// @param value item value, or null for no selection
        /// @param index row index
        /// @param selected whether the row is selected
        /// @param focused whether the row has focus
        /// @return configured renderer component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            @Nullable Object renderedValue = value;
            if (value != null) {
                @SuppressWarnings("unchecked")
                T typedValue = (T) value;
                renderedValue = textProvider.apply(typedValue);
            }
            return super.getListCellRendererComponent(list, renderedValue, index, selected, focused);
        }
    }

    /// Forwards all install-name document mutations to one common handler.
    @NotNullByDefault
    private final class InstallNameDocumentListener implements DocumentListener {
        /// Handles inserted name text.
        @Override
        public void insertUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            installNameChanged();
        }

        /// Handles removed name text.
        @Override
        public void removeUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            installNameChanged();
        }

        /// Handles document attribute changes.
        @Override
        public void changedUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            installNameChanged();
        }
    }

    /// Restores explicitly selected versions when sparse row contents become available.
    @NotNullByDefault
    private final class VersionDataListener implements ListDataListener {
        /// Handles changed sparse row contents.
        @Override
        public void contentsChanged(ListDataEvent event) {
            Objects.requireNonNull(event, "event");
            restoreLoadedSelectionIfNeeded();
        }

        /// Handles logical row additions.
        @Override
        public void intervalAdded(ListDataEvent event) {
            Objects.requireNonNull(event, "event");
            restoreLoadedSelectionIfNeeded();
        }

        /// Handles logical row removals.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            Objects.requireNonNull(event, "event");
            restoreLoadedSelectionIfNeeded();
        }
    }

    /// Restores one retained selection after its sparse row finishes loading.
    private void restoreLoadedSelectionIfNeeded() {
        if (closed || adjustingControls || selectedVersion != null) {
            updateActionAvailability();
            return;
        }
        @Nullable SelectionKey key = displayedSelection;
        @Nullable String retainedId = key == null ? null : explicitVersionSelections.get(key);
        @Nullable List<DiscoJavaRemoteVersion> versions = key == null ? null : successfulResults.get(key);
        int retainedIndex = versions == null ? -1 : indexOfVersionId(versions, retainedId);
        if (retainedIndex >= 0 && versionChoice.getChoiceModel().loadedValueAt(retainedIndex) != null) {
            adjustingControls = true;
            try {
                versionChoice.getList().setSelectedIndex(retainedIndex);
                selectedVersion = versions.get(retainedIndex);
                requestInstallNameSuggestion();
            } finally {
                adjustingControls = false;
            }
        }
        updateActionAvailability();
    }

    /// Stores immutable versions and slices only the exact range requested by the measured viewport.
    @NotNullByDefault
    private static final class VersionDataSource
            implements ViewportChoiceDataSource<DiscoJavaRemoteVersion> {
        /// Immutable successful version snapshot.
        private volatile @Unmodifiable List<DiscoJavaRemoteVersion> versions = List.of();

        /// Monotonic local snapshot revision.
        private final AtomicLong revision = new AtomicLong();

        /// Replaces the immutable snapshot and advances its revision.
        ///
        /// @param replacement immutable replacement versions
        private void replace(@Unmodifiable List<DiscoJavaRemoteVersion> replacement) {
            versions = List.copyOf(Objects.requireNonNull(replacement, "replacement"));
            revision.incrementAndGet();
        }

        /// Returns the exact immutable result size.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(versions.size());
        }

        /// Returns the current immutable snapshot revision.
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(revision.get());
        }

        /// Returns exactly the requested local range without remote I/O or a fixed page constant.
        ///
        /// @param desiredRange measured viewport-derived range
        /// @param cancellation cooperative cancellation signal
        /// @return immediately completed immutable slice
        @Override
        public CompletionStage<ChoicePage<DiscoJavaRemoteVersion>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            @Unmodifiable List<DiscoJavaRemoteVersion> current = versions;
            IndexRange actual = Objects.requireNonNull(desiredRange, "desiredRange")
                    .clampToItemCount(current.size());
            @Unmodifiable List<DiscoJavaRemoteVersion> items = List.copyOf(current.subList(
                    actual.startInclusive(),
                    actual.endExclusive()));
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    actual,
                    items,
                    OptionalInt.of(current.size()),
                    actual.endExclusive() == current.size()));
        }
    }
}
