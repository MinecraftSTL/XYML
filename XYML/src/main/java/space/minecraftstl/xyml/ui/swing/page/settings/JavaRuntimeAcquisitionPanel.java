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
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents the pure Swing input surface for acquiring launcher-managed Java runtimes.
///
/// The panel owns no service, task, native chooser, filesystem probe, or progress presentation. It forwards every
/// operation to [Listener] and accepts completed snapshots, archive inspections, and name-validation results from
/// its parent. Mojang and third-party version choices use viewport-driven lists so row demand follows measured
/// geometry rather than an arbitrary page size. Explicit selections and local drafts survive mode changes.
@NotNullByDefault
public final class JavaRuntimeAcquisitionPanel extends JPanel implements AutoCloseable {
    /// Card identifier for built-in Mojang runtimes.
    private static final String MOJANG_CARD = "mojang";

    /// Card identifier for third-party Disco Java distributions.
    private static final String DISCO_CARD = "disco";

    /// Card identifier for local Java archives.
    private static final String ARCHIVE_CARD = "archive";

    /// Receives every operation that requires parent-owned state, I/O, or task execution.
    private final Listener listener;

    /// Mutable bounded data source behind the viewport-driven Mojang choice list.
    private final MojangOptionDataSource mojangDataSource;

    /// Single-select viewport list for current-platform Mojang runtime choices.
    private final ViewportChoiceList<MojangJavaRuntimeOption> mojangChoiceList;

    /// Explicit lazy third-party distribution workflow retained across mode changes.
    private final DiscoJavaAcquisitionPanel discoPanel;

    /// Returns to the parent Java management card.
    private final JButton backButton = new JButton(i18n("java.management"));

    /// Selects the built-in Mojang acquisition mode.
    private final JToggleButton mojangModeButton = new JToggleButton(i18n("java.download.title"));

    /// Selects the third-party Java distribution acquisition mode.
    private final JToggleButton discoModeButton = new JToggleButton(i18n("java.download.more"));

    /// Selects the local archive acquisition mode.
    private final JToggleButton archiveModeButton = new JToggleButton(i18n("java.install"));

    /// Switches between the two acquisition modes without reconstructing their state.
    private final CardLayout cardLayout = new CardLayout();

    /// Contains both persistent acquisition mode surfaces.
    private final JPanel cards = new JPanel(cardLayout);

    /// Starts downloading the explicitly selected, not-yet-installed Mojang runtime.
    private final JButton downloadButton = new JButton(i18n("java.download"));

    /// Displays the user-selected archive path without allowing manual path edits.
    private final JTextField archivePathField = readOnlyField("javaManagementAcquireArchivePath");

    /// Requests a parent-owned native archive chooser.
    private final JButton chooseArchiveButton = new JButton(i18n("selector.choose_file"));

    /// Displays the inspected archive's exact Java version.
    private final JTextField archiveVersionField = readOnlyField("javaManagementAcquireArchiveVersion");

    /// Displays the inspected archive's reported vendor.
    private final JTextField archiveVendorField = readOnlyField("javaManagementAcquireArchiveVendor");

    /// Displays the inspected archive's platform and architecture.
    private final JTextField archiveArchitectureField = readOnlyField("javaManagementAcquireArchiveArchitecture");

    /// Holds the explicit launcher-managed installation name draft.
    private final JTextField installNameField = new JTextField();

    /// Shows precise inline validation feedback for the installation name.
    private final JLabel installNameStatusLabel = new JLabel(" ");

    /// Starts installing the inspected archive under the validated draft name.
    private final JButton installButton = new JButton(i18n("button.install"));

    /// Tracks list page changes so a newly loaded selected row can enable its action.
    private final ListDataListener mojangDataListener = new MojangDataListener();

    /// Tracks archive-name edits without replacing the user's draft during mode changes.
    private final DocumentListener installNameListener = new InstallNameDocumentListener();

    /// Accepts exactly one supported archive dropped anywhere on the panel background.
    private final TransferHandler archiveTransferHandler = new ArchiveTransferHandler();

    /// Snapshot currently represented by the Mojang mode.
    private JavaRuntimeAcquisitionSnapshot snapshot;

    /// Current visible acquisition mode.
    private Mode mode = Mode.MOJANG;

    /// Last Mojang version explicitly selected by the user, or null before selection.
    private @Nullable GameJavaVersion selectedMojangVersion;

    /// Current user-selected archive path, or null before chooser/drop input.
    private @Nullable Path selectedArchive;

    /// Inspection corresponding exactly to [#selectedArchive], or null before successful inspection.
    private @Nullable LocalJavaArchiveInspection archiveInspection;

    /// Monotonic identity of the most recently selected archive, including repeated selections of the same path.
    private long archiveRevision;

    /// Name candidate most recently accepted as valid for [#archiveInspection], or null otherwise.
    private @Nullable String validatedInstallName;

    /// Suggested name awaiting parent validation before it may populate an untouched blank draft.
    private @Nullable String pendingSuggestedName;

    /// Whether the user has explicitly edited the installation-name draft.
    private boolean installNameEdited;

    /// Suppresses document callbacks while the panel applies a validated suggestion.
    private boolean applyingInstallName;

    /// Whether a parent-owned operation is currently preventing input.
    private boolean busy;

    /// Whether the panel has released list resources and rejects future input.
    private volatile boolean closed;

    /// Creates a pure acquisition input panel from one already-loaded local capability snapshot.
    ///
    /// @param initialSnapshot current platform and built-in Mojang runtime choices
    /// @param listener parent operation listener
    public JavaRuntimeAcquisitionPanel(
            JavaRuntimeAcquisitionSnapshot initialSnapshot,
            Listener listener) {
        this(initialSnapshot, List.of(), JavaRuntimePlatformLinks.forPlatform(initialSnapshot.platform()), listener);
    }

    /// Creates a pure acquisition input panel with explicit third-party capabilities and platform links.
    ///
    /// @param initialSnapshot current platform and built-in Mojang runtime choices
    /// @param supportedDistributions exact platform-supported third-party distributions
    /// @param platformLinks immutable external choices for exceptional platforms
    /// @param listener parent operation listener
    JavaRuntimeAcquisitionPanel(
            JavaRuntimeAcquisitionSnapshot initialSnapshot,
            @Unmodifiable List<DiscoJavaDistribution> supportedDistributions,
            @Unmodifiable List<JavaRuntimePlatformLinks.Link> platformLinks,
            Listener listener) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        snapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        this.listener = Objects.requireNonNull(listener, "listener");
        mojangDataSource = new MojangOptionDataSource(snapshot.mojangRuntimes());
        mojangChoiceList = new ViewportChoiceList<>(mojangDataSource, JavaRuntimeAcquisitionPanel::mojangText);
        discoPanel = new DiscoJavaAcquisitionPanel(
                Objects.requireNonNull(supportedDistributions, "supportedDistributions"),
                Objects.requireNonNull(platformLinks, "platformLinks"),
                new DiscoListener());
        configureComponents();
        applySnapshot(initialSnapshot);
    }

    /// Returns the mode currently shown to the user.
    ///
    /// @return current acquisition mode
    public Mode mode() {
        EdtDispatcher.requireEventDispatchThread();
        return mode;
    }

    /// Returns the explicitly selected Mojang version, if its row has loaded.
    ///
    /// @return selected Mojang version, or null before explicit selection
    public @Nullable GameJavaVersion selectedMojangVersion() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedMojangVersion;
    }

    /// Returns the explicitly selected third-party distribution, or null before selection.
    ///
    /// @return explicit Disco distribution selection
    public @Nullable DiscoJavaDistribution selectedDiscoDistribution() {
        EdtDispatcher.requireEventDispatchThread();
        return discoPanel.selectedDistribution();
    }

    /// Returns the explicitly selected third-party package type, or null before selection.
    ///
    /// @return explicit Disco package selection
    public @Nullable JavaPackageType selectedDiscoPackageType() {
        EdtDispatcher.requireEventDispatchThread();
        return discoPanel.selectedPackageType();
    }

    /// Returns the explicitly selected third-party version, or null before selection.
    ///
    /// @return explicit Disco version selection
    public @Nullable DiscoJavaRemoteVersion selectedDiscoVersion() {
        EdtDispatcher.requireEventDispatchThread();
        return discoPanel.selectedVersion();
    }

    /// Returns the normalized archive path selected through chooser integration or drag and drop.
    ///
    /// @return selected archive, or null before selection
    public @Nullable Path selectedArchive() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedArchive;
    }

    /// Replaces local Mojang capability state while preserving an explicitly selected version when still present.
    ///
    /// @param replacement replacement acquisition snapshot
    public void applySnapshot(JavaRuntimeAcquisitionSnapshot replacement) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        snapshot = Objects.requireNonNull(replacement, "replacement");
        @Nullable GameJavaVersion retainedVersion = selectedMojangVersion;
        mojangDataSource.replace(snapshot.mojangRuntimes());
        mojangChoiceList.reloadData();

        int retainedIndex = indexOfMojangVersion(retainedVersion);
        if (retainedIndex < 0) {
            selectedMojangVersion = null;
            mojangChoiceList.getList().clearSelection();
        } else {
            mojangChoiceList.getList().setSelectedIndex(retainedIndex);
        }
        updateActionAvailability();
    }

    /// Applies a successful parent-owned inspection for the currently selected archive.
    ///
    /// Stale inspections for a replaced archive are ignored. An untouched blank name is not populated until the
    /// parent explicitly reports that the archive suggestion has [JavaRuntimeInstallNameStatus#VALID] status.
    ///
    /// @param revision archive selection revision attached to the parent request
    /// @param inspection completed archive inspection
    public void applyArchiveInspection(long revision, LocalJavaArchiveInspection inspection) {
        EdtDispatcher.requireEventDispatchThread();
        LocalJavaArchiveInspection completedInspection = Objects.requireNonNull(inspection, "inspection");
        if (closed
                || revision != archiveRevision
                || !completedInspection.archiveFile().equals(selectedArchive)) {
            return;
        }

        archiveInspection = completedInspection;
        archiveVersionField.setText(completedInspection.javaInfo().getVersion());
        @Nullable String vendor = completedInspection.javaInfo().getVendor();
        archiveVendorField.setText(displayText(vendor));
        archiveArchitectureField.setText(completedInspection.javaInfo().getPlatform().toString());
        validatedInstallName = null;
        clearNameStatus();

        String currentDraft = normalizedInstallName();
        if (!installNameEdited && currentDraft.isEmpty()) {
            pendingSuggestedName = completedInspection.suggestedName();
            listener.installNameValidationRequested(
                    archiveRevision,
                    completedInspection,
                    completedInspection.suggestedName());
        } else {
            pendingSuggestedName = null;
            listener.installNameValidationRequested(archiveRevision, completedInspection, currentDraft);
        }
        updateActionAvailability();
    }

    /// Applies one parent-owned name validation result when it still matches the current inspection and candidate.
    ///
    /// @param revision archive selection revision attached to the parent validation request
    /// @param inspection inspection used by the parent validation request
    /// @param candidate exact candidate validated by the parent
    /// @param status resulting validation classification
    public void applyInstallNameStatus(
            long revision,
            LocalJavaArchiveInspection inspection,
            String candidate,
            JavaRuntimeInstallNameStatus status) {
        EdtDispatcher.requireEventDispatchThread();
        LocalJavaArchiveInspection validatedInspection = Objects.requireNonNull(inspection, "inspection");
        String validatedCandidate = Objects.requireNonNull(candidate, "candidate");
        JavaRuntimeInstallNameStatus validationStatus = Objects.requireNonNull(status, "status");
        if (closed
                || revision != archiveRevision
                || !validatedInspection.equals(archiveInspection)) {
            return;
        }

        if (validatedCandidate.equals(pendingSuggestedName)) {
            pendingSuggestedName = null;
            if (!installNameEdited
                    && installNameField.getText().isBlank()
                    && validationStatus == JavaRuntimeInstallNameStatus.VALID) {
                applyingInstallName = true;
                try {
                    installNameField.setText(validatedCandidate);
                } finally {
                    applyingInstallName = false;
                }
                validatedInstallName = validatedCandidate;
                clearNameStatus();
                updateActionAvailability();
            }
            return;
        }

        if (!validatedCandidate.equals(normalizedInstallName())) {
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

    /// Applies one successful third-party version result when its selection and revision remain current.
    ///
    /// @param revision version request revision
    /// @param distribution requested distribution
    /// @param packageType requested package type
    /// @param versions immutable newest-first versions
    public void applyDiscoVersions(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            @Unmodifiable List<DiscoJavaRemoteVersion> versions) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            discoPanel.applyVersions(revision, distribution, packageType, versions);
        }
    }

    /// Applies one failed third-party version request when its selection and revision remain current.
    ///
    /// @param revision version request revision
    /// @param distribution requested distribution
    /// @param packageType requested package type
    public void applyDiscoVersionLoadFailure(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            discoPanel.applyVersionLoadFailure(revision, distribution, packageType);
        }
    }

    /// Applies a third-party managed-name suggestion and its current validation result.
    ///
    /// @param revision install selection revision
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @param version selected remote version
    /// @param suggestion service-derived suggested name
    /// @param status validation result for the suggestion
    public void applyDiscoInstallNameSuggestion(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version,
            String suggestion,
            JavaRuntimeInstallNameStatus status) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            discoPanel.applyInstallNameSuggestion(
                    revision,
                    distribution,
                    packageType,
                    version,
                    suggestion,
                    status);
        }
    }

    /// Applies one exact third-party managed-name validation result.
    ///
    /// @param revision install selection revision
    /// @param distribution selected distribution
    /// @param packageType selected package type
    /// @param version selected remote version
    /// @param candidate exact trimmed candidate
    /// @param status validation result
    public void applyDiscoInstallNameStatus(
            long revision,
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version,
            String candidate,
            JavaRuntimeInstallNameStatus status) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            discoPanel.applyInstallNameStatus(
                    revision,
                    distribution,
                    packageType,
                    version,
                    candidate,
                    status);
        }
    }

    /// Selects and requests inspection of one archive returned by the parent chooser.
    ///
    /// This method performs lexical suffix validation only and never probes the filesystem.
    ///
    /// @param archiveFile chooser-selected local archive
    /// @return whether the supported archive was accepted
    public boolean selectArchive(Path archiveFile) {
        EdtDispatcher.requireEventDispatchThread();
        Path normalizedArchive = Objects.requireNonNull(archiveFile, "archiveFile")
                .toAbsolutePath()
                .normalize();
        if (closed || busy || !supportsArchivePath(normalizedArchive)) {
            return false;
        }

        selectedArchive = normalizedArchive;
        long requestedRevision = ++archiveRevision;
        archiveInspection = null;
        validatedInstallName = null;
        pendingSuggestedName = null;
        archivePathField.setText(normalizedArchive.toString());
        archiveVersionField.setText("");
        archiveVendorField.setText("");
        archiveArchitectureField.setText("");
        clearNameStatus();
        showMode(Mode.ARCHIVE);
        updateActionAvailability();
        listener.archiveInspectionRequested(requestedRevision, normalizedArchive);
        return true;
    }

    /// Enables or disables input around one parent-owned operation without clearing any explicit state.
    ///
    /// @param newBusy whether an operation is running
    public void setBusy(boolean newBusy) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        busy = newBusy;
        discoPanel.setBusy(newBusy);
        updateActionAvailability();
    }

    /// Releases the viewport list and rejects future user input or late parent results.
    @Override
    public void close() {
        closed = true;
        SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
    }

    /// Builds the stable header, persistent cards, and drag-and-drop integration.
    private void configureComponents() {
        setName("javaManagementAcquirePanel");
        setOpaque(false);
        setTransferHandler(archiveTransferHandler);

        JPanel root = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[grow,fill]"));
        root.setOpaque(false);
        root.add(createHeader(), "growx");

        cards.setName("javaManagementAcquireCards");
        cards.setOpaque(false);
        cards.add(createMojangMode(), MOJANG_CARD);
        cards.add(discoPanel, DISCO_CARD);
        cards.add(createArchiveMode(), ARCHIVE_CARD);
        root.add(cards, "grow, push");
        add(root, BorderLayout.CENTER);

        configureButtons();
        configureMojangList();
        configureArchiveForm();
        showMode(snapshot.mojangRuntimes().isEmpty() && discoPanel.hasContent()
                ? Mode.DISCO
                : Mode.MOJANG);
    }

    /// Creates the compact return command and FlatLaf segmented mode control.
    ///
    /// @return configured header
    private JPanel createHeader() {
        JPanel header = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[]10[grow,fill][]",
                "[]"));
        header.setOpaque(false);
        header.add(backButton);

        JLabel heading = new JLabel(i18n("java.download.title"));
        heading.setName("javaManagementAcquireTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        header.add(heading, "growx");

        JPanel modes = new JPanel(new MigLayout("insets 0, gap 0", "[][][]", "[]"));
        modes.setName("javaManagementAcquireModes");
        modes.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        configureModeButton(mojangModeButton, "javaManagementAcquireMojangMode", "first", Mode.MOJANG);
        configureModeButton(discoModeButton, "javaManagementAcquireDiscoMode", "middle", Mode.DISCO);
        configureModeButton(archiveModeButton, "javaManagementAcquireArchiveMode", "last", Mode.ARCHIVE);
        group.add(mojangModeButton);
        group.add(discoModeButton);
        group.add(archiveModeButton);
        modes.add(mojangModeButton);
        modes.add(discoModeButton);
        modes.add(archiveModeButton);
        header.add(modes);
        return header;
    }

    /// Creates the viewport-driven Mojang mode.
    ///
    /// @return configured Mojang acquisition surface
    private JPanel createMojangMode() {
        JPanel content = new JPanel(new MigLayout(
                "insets 8 0 0 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[grow,fill]8[]"));
        content.setName("javaManagementAcquireMojangView");
        content.setOpaque(false);

        JLabel prompt = new JLabel(i18n("java.download.prompt"));
        prompt.setName("javaManagementAcquireMojangPrompt");
        content.add(prompt, "growx");
        content.add(mojangChoiceList, "grow, push, hmin 120");

        JPanel actions = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        actions.setOpaque(false);
        JLabel platform = new JLabel(snapshot.platform().toString());
        platform.setName("javaManagementAcquirePlatform");
        actions.add(platform, "growx");
        actions.add(downloadButton);
        content.add(actions, "growx");
        return content;
    }

    /// Creates the compact local archive form.
    ///
    /// @return configured archive acquisition surface
    private JPanel createArchiveMode() {
        JPanel content = new JPanel(new MigLayout(
                "insets 8 0 0 0, fillx, wrap 1",
                "[grow,fill]",
                "[]8[]8[]8[]8[]4[]8[]"));
        content.setName("javaManagementAcquireArchiveView");
        content.setOpaque(false);
        content.add(createArchivePathRow(), "growx");
        content.add(new JSeparator(), "growx");
        content.add(createDetailRow(i18n("java.info.version"), archiveVersionField), "growx");
        content.add(createDetailRow(i18n("java.info.vendor"), archiveVendorField), "growx");
        content.add(createDetailRow(i18n("java.info.architecture"), archiveArchitectureField), "growx");
        content.add(createInstallNameRow(), "growx");
        installNameStatusLabel.setName("javaManagementAcquireInstallNameStatus");
        content.add(installNameStatusLabel, "gapleft 128, growx, h 22!");

        JPanel actions = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        actions.setOpaque(false);
        actions.add(new JLabel(), "growx");
        actions.add(installButton);
        content.add(actions, "growx");
        return content;
    }

    /// Creates the archive path row with its parent-owned chooser command.
    ///
    /// @return configured archive path row
    private JPanel createArchivePathRow() {
        JPanel row = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[120!,fill][grow,fill]8[]",
                "[]"));
        row.setOpaque(false);
        JLabel label = new JLabel(i18n("java.install.archive"));
        label.setLabelFor(archivePathField);
        row.add(label);
        row.add(archivePathField, "growx");
        row.add(chooseArchiveButton);
        return row;
    }

    /// Creates the editable installation-name row.
    ///
    /// @return configured installation-name row
    private JPanel createInstallNameRow() {
        JPanel row = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[120!,fill][grow,fill]",
                "[]"));
        row.setOpaque(false);
        JLabel label = new JLabel(i18n("java.install.name"));
        label.setLabelFor(installNameField);
        row.add(label);
        row.add(installNameField, "growx");
        return row;
    }

    /// Configures stable command names, icons, accessibility labels, and callbacks.
    private void configureButtons() {
        configureIconOnlyButton(
                backButton,
                "javaManagementAcquireBack",
                "assets/swing/icons/arrow-back.svg");
        backButton.addActionListener(event -> {
            if (!closed && !busy) {
                listener.backRequested();
            }
        });

        downloadButton.setName("javaManagementAcquireDownload");
        downloadButton.setIcon(themeIcon("assets/swing/icons/nav-downloads.svg"));
        configureTextButtonAccessibility(downloadButton);
        downloadButton.addActionListener(event -> downloadSelectedMojangRuntime());

        chooseArchiveButton.setName("javaManagementAcquireChooseArchive");
        chooseArchiveButton.setIcon(themeIcon("assets/swing/icons/folder-open.svg"));
        configureTextButtonAccessibility(chooseArchiveButton);
        chooseArchiveButton.addActionListener(event -> {
            if (!closed && !busy) {
                listener.archiveChooserRequested();
            }
        });

        installButton.setName("javaManagementAcquireInstall");
        installButton.setIcon(themeIcon("assets/swing/icons/file-import.svg"));
        configureTextButtonAccessibility(installButton);
        installButton.addActionListener(event -> installSelectedArchive());
    }

    /// Configures the static Mojang data source's viewport list and selection listener.
    private void configureMojangList() {
        mojangChoiceList.setName("javaManagementAcquireMojangChoices");
        mojangChoiceList.setBorder(BorderFactory.createEmptyBorder());
        mojangChoiceList.getList().setName("javaManagementAcquireMojangList");
        mojangChoiceList.getList().getModel().addListDataListener(mojangDataListener);
        mojangChoiceList.getList().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateMojangSelection();
            }
        });
    }

    /// Configures archive field identity and live name validation requests.
    private void configureArchiveForm() {
        installNameField.setName("javaManagementAcquireInstallName");
        SwingTextFields.showClearButton(installNameField);
        installNameField.getDocument().addDocumentListener(installNameListener);
    }

    /// Configures one FlatLaf segmented acquisition-mode button.
    ///
    /// @param button target toggle button
    /// @param name stable component name
    /// @param position FlatLaf segment position
    /// @param buttonMode mode represented by the button
    private void configureModeButton(
            JToggleButton button,
            String name,
            String position,
            Mode buttonMode) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.putClientProperty("JButton.buttonType", "segmented");
        button.putClientProperty("JButton.segmentPosition", Objects.requireNonNull(position, "position"));
        button.addActionListener(event -> {
            if (!closed && !busy && button.isSelected()) {
                showMode(buttonMode);
            }
        });
    }

    /// Shows one persistent mode card and updates its toggle without clearing either mode's state.
    ///
    /// @param replacement mode to show
    private void showMode(Mode replacement) {
        EdtDispatcher.requireEventDispatchThread();
        mode = Objects.requireNonNull(replacement, "replacement");
        mojangModeButton.setSelected(mode == Mode.MOJANG);
        discoModeButton.setSelected(mode == Mode.DISCO);
        archiveModeButton.setSelected(mode == Mode.ARCHIVE);
        String card = switch (mode) {
            case MOJANG -> MOJANG_CARD;
            case DISCO -> DISCO_CARD;
            case ARCHIVE -> ARCHIVE_CARD;
        };
        cardLayout.show(cards, card);
        updateActionAvailability();
    }

    /// Records a loaded explicit Mojang selection without inventing a default choice.
    private void updateMojangSelection() {
        if (mojangChoiceList.getList().getSelectedIndex() < 0) {
            selectedMojangVersion = null;
        } else {
            @Nullable MojangJavaRuntimeOption selectedOption = mojangChoiceList.getSelectedValue();
            selectedMojangVersion = selectedOption == null ? null : selectedOption.version();
        }
        updateActionAvailability();
    }

    /// Requests download of the loaded explicit Mojang selection when it is not already installed.
    private void downloadSelectedMojangRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable MojangJavaRuntimeOption selectedOption = mojangChoiceList.getSelectedValue();
        if (closed || busy || selectedOption == null || selectedOption.installed()) {
            return;
        }
        listener.mojangDownloadRequested(selectedOption.version());
    }

    /// Requests installation of the current inspected archive under its current validated name.
    private void installSelectedArchive() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable LocalJavaArchiveInspection inspection = archiveInspection;
        @Nullable String validatedName = validatedInstallName;
        if (closed || busy || inspection == null || validatedName == null) {
            return;
        }
        listener.archiveInstallRequested(inspection, validatedName);
    }

    /// Responds to an explicit installation-name edit with a parent-owned validation request.
    private void installNameChanged() {
        if (applyingInstallName || closed) {
            return;
        }
        installNameEdited = true;
        validatedInstallName = null;
        pendingSuggestedName = null;
        clearNameStatus();
        @Nullable LocalJavaArchiveInspection inspection = archiveInspection;
        if (inspection != null) {
            listener.installNameValidationRequested(
                    archiveRevision,
                    inspection,
                    normalizedInstallName());
        }
        updateActionAvailability();
    }

    /// Enables controls exactly when the panel state permits their operation.
    private void updateActionAvailability() {
        boolean interactive = !closed && !busy;
        backButton.setEnabled(interactive);
        mojangModeButton.setEnabled(interactive);
        discoModeButton.setEnabled(interactive);
        archiveModeButton.setEnabled(interactive);
        mojangChoiceList.setEnabled(interactive);
        mojangChoiceList.getList().setEnabled(interactive);
        chooseArchiveButton.setEnabled(interactive);
        installNameField.setEnabled(interactive && archiveInspection != null);

        @Nullable MojangJavaRuntimeOption selectedOption = mojangChoiceList.getSelectedValue();
        downloadButton.setEnabled(interactive && selectedOption != null && !selectedOption.installed());
        updateDownloadAccessibility(selectedOption);
        installButton.setEnabled(interactive
                && archiveInspection != null
                && validatedInstallName != null
                && validatedInstallName.equals(normalizedInstallName()));
    }

    /// Clears all interaction resources on the Swing event dispatch thread.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        mojangChoiceList.getList().getModel().removeListDataListener(mojangDataListener);
        installNameField.getDocument().removeDocumentListener(installNameListener);
        mojangChoiceList.close();
        discoPanel.close();
        setTransferHandler(null);
        busy = false;
        updateActionAvailability();
    }

    /// Returns the trimmed installation-name draft.
    ///
    /// @return normalized visible draft
    private String normalizedInstallName() {
        return installNameField.getText().trim();
    }

    /// Finds a Mojang version in the current immutable snapshot.
    ///
    /// @param version version to find, or null for no retained selection
    /// @return matching stable row index, or `-1` when absent
    private int indexOfMojangVersion(@Nullable GameJavaVersion version) {
        if (version == null) {
            return -1;
        }
        @Unmodifiable List<MojangJavaRuntimeOption> options = snapshot.mojangRuntimes();
        for (int index = 0; index < options.size(); index++) {
            GameJavaVersion candidate = options.get(index).version();
            if (candidate.majorVersion() == version.majorVersion()
                    && Objects.equals(candidate.component(), version.component())) {
                return index;
            }
        }
        return -1;
    }

    /// Shows one invalid name classification with FlatLaf inline error styling.
    ///
    /// @param status invalid validation classification
    private void showNameStatus(JavaRuntimeInstallNameStatus status) {
        String text = switch (status) {
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

    /// Clears name-validation text and FlatLaf error styling.
    private void clearNameStatus() {
        installNameStatusLabel.setText(" ");
        installNameField.putClientProperty("JComponent.outline", null);
    }

    /// Returns whether a path has one of the two locally supported archive suffixes.
    ///
    /// This check is lexical and performs no filesystem access.
    ///
    /// @param archiveFile candidate archive path
    /// @return whether the name ends in `.zip` or `.tar.gz`
    private static boolean supportsArchivePath(Path archiveFile) {
        @Nullable Path fileName = Objects.requireNonNull(archiveFile, "archiveFile").getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip") || name.endsWith(".tar.gz");
    }

    /// Returns concise display text for one built-in Mojang runtime option.
    ///
    /// Installed state remains part of the option and disables the download action when selected.
    ///
    /// @param option runtime option
    /// @return visible row text
    private static String mojangText(MojangJavaRuntimeOption option) {
        MojangJavaRuntimeOption runtime = Objects.requireNonNull(option, "option");
        String label = "Java " + runtime.version().majorVersion();
        return runtime.installed() ? label + " - " + i18n("java.installed") : label;
    }

    /// Converts optional metadata text into a stable read-only field value.
    ///
    /// @param text optional metadata value
    /// @return original non-blank text, or `-` when unavailable
    private static String displayText(@Nullable String text) {
        return text == null || text.isBlank() ? "-" : text;
    }

    /// Creates one read-only text field with a stable UI-audit name.
    ///
    /// @param name component name
    /// @return configured read-only field
    private static JTextField readOnlyField(String name) {
        JTextField field = new JTextField();
        field.setName(Objects.requireNonNull(name, "name"));
        field.setEditable(false);
        return field;
    }

    /// Creates one compact archive metadata row.
    ///
    /// @param labelText localized metadata label
    /// @param valueField read-only metadata field
    /// @return configured row
    private static JPanel createDetailRow(String labelText, JTextField valueField) {
        JPanel row = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[120!,fill][grow,fill]",
                "[]"));
        row.setOpaque(false);
        JLabel label = new JLabel(Objects.requireNonNull(labelText, "labelText"));
        label.setLabelFor(Objects.requireNonNull(valueField, "valueField"));
        row.add(label);
        row.add(valueField, "growx");
        return row;
    }

    /// Configures an icon-only command while retaining its localized accessible label.
    ///
    /// @param button target button
    /// @param name stable UI-audit name
    /// @param iconResource classpath SVG resource
    private static void configureIconOnlyButton(
            JButton button,
            String name,
            String iconResource) {
        JButton target = Objects.requireNonNull(button, "button");
        String accessibleLabel = target.getText();
        target.setName(Objects.requireNonNull(name, "name"));
        target.setIcon(themeIcon(iconResource));
        target.setText("");
        target.setToolTipText(accessibleLabel);
        target.getAccessibleContext().setAccessibleName(accessibleLabel);
    }

    /// Retains one visible text command as both a tooltip and accessible name.
    ///
    /// @param button target text command
    private static void configureTextButtonAccessibility(JButton button) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setToolTipText(target.getText());
        target.getAccessibleContext().setAccessibleName(target.getText());
    }

    /// Creates a bundled SVG icon that follows its component foreground in light and dark themes.
    ///
    /// @param iconResource classpath SVG resource
    /// @return configured theme-aware icon
    private static FlatSVGIcon themeIcon(String iconResource) {
        FlatSVGIcon icon = new FlatSVGIcon(Objects.requireNonNull(iconResource, "iconResource"), 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(JavaRuntimeAcquisitionPanel::resolveIconColor));
        return icon;
    }

    /// Resolves an SVG color from its owning component's current foreground.
    ///
    /// @param component owning component, or null during standalone rendering
    /// @param originalColor SVG fallback color
    /// @return component foreground when available, otherwise the authored color
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color fallback = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        return foreground == null ? fallback : foreground;
    }

    /// Describes why the Mojang download action is available or unavailable for assistive technology.
    ///
    /// @param selectedOption loaded selected option, or null for no loaded explicit selection
    private void updateDownloadAccessibility(@Nullable MojangJavaRuntimeOption selectedOption) {
        String action = i18n("java.download");
        String state;
        if (selectedOption == null) {
            state = i18n("java.download.prompt");
        } else if (selectedOption.installed()) {
            state = i18n("install.success");
        } else {
            state = action;
        }
        downloadButton.setToolTipText(state);
        downloadButton.getAccessibleContext().setAccessibleName(action + " - " + state);
        downloadButton.getAccessibleContext().setAccessibleDescription(state);
    }

    /// Returns the one supported archive path carried by a transfer, or null for every rejected shape.
    ///
    /// @param transferable transfer payload
    /// @return normalized archive path, or null when the transfer is unsupported
    private static @Nullable Path transferredArchive(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        if (!payload.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return null;
        }
        try {
            Object data = payload.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(data instanceof List<?> files)
                    || files.size() != 1
                    || !(files.get(0) instanceof File file)) {
                return null;
            }
            Path archive = file.toPath().toAbsolutePath().normalize();
            return supportsArchivePath(archive) ? archive : null;
        } catch (UnsupportedFlavorException | IOException | RuntimeException ignored) {
            return null;
        }
    }

    /// Acquisition modes represented by the FlatLaf segmented control.
    @NotNullByDefault
    public enum Mode {
        /// Built-in current-platform Mojang runtimes.
        MOJANG,

        /// Explicit third-party Disco distributions.
        DISCO,

        /// User-selected local `.zip` or `.tar.gz` Java archive.
        ARCHIVE
    }

    /// Parent-owned acquisition operations emitted by this pure UI component.
    @NotNullByDefault
    public interface Listener {
        /// Requests returning to installed Java runtime management.
        void backRequested();

        /// Requests a parent-owned native chooser for one local Java archive.
        void archiveChooserRequested();

        /// Requests parent-owned inspection of one lexically supported local archive.
        ///
        /// @param revision monotonic archive-selection identity
        /// @param archiveFile normalized absolute archive path
        void archiveInspectionRequested(long revision, Path archiveFile);

        /// Requests parent-owned download and registration of one explicit Mojang runtime.
        ///
        /// @param version explicitly selected Mojang runtime version
        void mojangDownloadRequested(GameJavaVersion version);

        /// Requests parent-owned validation of one install name against syntax and managed-runtime state.
        ///
        /// @param revision monotonic archive-selection identity
        /// @param inspection current archive inspection
        /// @param candidate trimmed name candidate, possibly empty
        void installNameValidationRequested(
                long revision,
                LocalJavaArchiveInspection inspection,
                String candidate);

        /// Requests parent-owned installation and registration of one inspected local archive.
        ///
        /// @param inspection current archive inspection
        /// @param name validated launcher-managed runtime name
        void archiveInstallRequested(LocalJavaArchiveInspection inspection, String name);

        /// Returns service-authoritative package choices for one explicit third-party distribution.
        ///
        /// @param distribution selected distribution
        /// @return immutable package choices
        @Unmodifiable List<JavaPackageType> discoPackageTypesRequested(
                DiscoJavaDistribution distribution);

        /// Requests third-party versions for one exact explicit distribution and package choice.
        ///
        /// @param revision version-load revision
        /// @param distribution selected distribution
        /// @param packageType selected non-JavaFX package type
        void discoVersionsRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType);

        /// Requests cancellation of the currently running third-party version fetch, if any.
        void discoVersionLoadCancellationRequested();

        /// Requests a service-owned name suggestion and validation for one exact explicit version.
        ///
        /// @param revision install selection revision
        /// @param distribution selected distribution
        /// @param packageType selected non-JavaFX package type
        /// @param version selected remote version
        void discoInstallNameSuggestionRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version);

        /// Requests exact local validation for one edited third-party install name.
        ///
        /// @param revision install selection revision
        /// @param distribution selected distribution
        /// @param packageType selected non-JavaFX package type
        /// @param version selected remote version
        /// @param candidate exact trimmed candidate
        void discoInstallNameValidationRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String candidate);

        /// Requests download and safe installation of one exact third-party Java version.
        ///
        /// @param distribution selected distribution
        /// @param packageType selected non-JavaFX package type
        /// @param version selected remote version
        /// @param installName validated managed-runtime name
        void discoInstallRequested(
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String installName);

        /// Requests opening one immutable validated HTTPS Java download page.
        ///
        /// @param uri external destination
        void externalJavaDownloadRequested(URI uri);
    }

    /// Adapts the focused third-party surface to this panel's public parent listener.
    @NotNullByDefault
    private final class DiscoListener implements DiscoJavaAcquisitionPanel.Listener {
        /// Returns service-authoritative package choices for one explicit distribution.
        @Override
        public @Unmodifiable List<JavaPackageType> supportedPackageTypes(
                DiscoJavaDistribution distribution) {
            return listener.discoPackageTypesRequested(distribution);
        }

        /// Forwards one exact explicit version request.
        @Override
        public void versionsRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType) {
            listener.discoVersionsRequested(revision, distribution, packageType);
        }

        /// Forwards cancellation of a replaced or closed version request.
        @Override
        public void versionLoadCancellationRequested() {
            listener.discoVersionLoadCancellationRequested();
        }

        /// Forwards one exact suggested-name request.
        @Override
        public void installNameSuggestionRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version) {
            listener.discoInstallNameSuggestionRequested(
                    revision,
                    distribution,
                    packageType,
                    version);
        }

        /// Forwards one exact edited-name validation request.
        @Override
        public void installNameValidationRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String candidate) {
            listener.discoInstallNameValidationRequested(
                    revision,
                    distribution,
                    packageType,
                    version,
                    candidate);
        }

        /// Forwards one validated download-and-install command.
        @Override
        public void installRequested(
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String installName) {
            listener.discoInstallRequested(distribution, packageType, version, installName);
        }

        /// Forwards one platform external-link command without desktop access in the child panel.
        @Override
        public void externalLinkRequested(URI uri) {
            listener.externalJavaDownloadRequested(uri);
        }
    }

    /// Forwards all installation-name document mutations to one common handler.
    @NotNullByDefault
    private final class InstallNameDocumentListener implements DocumentListener {
        /// Handles inserted name text.
        ///
        /// @param event document event
        @Override
        public void insertUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            installNameChanged();
        }

        /// Handles removed name text.
        ///
        /// @param event document event
        @Override
        public void removeUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            installNameChanged();
        }

        /// Handles attribute changes for document implementations that emit them.
        ///
        /// @param event document event
        @Override
        public void changedUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            installNameChanged();
        }
    }

    /// Refreshes action state whenever the sparse Mojang model loads or replaces rows.
    @NotNullByDefault
    private final class MojangDataListener implements ListDataListener {
        /// Handles changed sparse row contents.
        ///
        /// @param event list data event
        @Override
        public void contentsChanged(ListDataEvent event) {
            Objects.requireNonNull(event, "event");
            updateMojangSelection();
        }

        /// Handles logical Mojang row additions.
        ///
        /// @param event list data event
        @Override
        public void intervalAdded(ListDataEvent event) {
            Objects.requireNonNull(event, "event");
            updateMojangSelection();
        }

        /// Handles logical Mojang row removals.
        ///
        /// @param event list data event
        @Override
        public void intervalRemoved(ListDataEvent event) {
            Objects.requireNonNull(event, "event");
            updateMojangSelection();
        }
    }

    /// Imports one supported archive transfer without performing filesystem access.
    @NotNullByDefault
    private final class ArchiveTransferHandler extends TransferHandler {
        /// Returns whether one exact supported archive is present and input is available.
        ///
        /// @param support Swing transfer context
        /// @return whether the transfer may be imported
        @Override
        public boolean canImport(TransferSupport support) {
            TransferSupport transferSupport = Objects.requireNonNull(support, "support");
            return !closed
                    && !busy
                    && transferredArchive(transferSupport.getTransferable()) != null;
        }

        /// Selects and forwards the exact supported archive carried by the transfer.
        ///
        /// @param support Swing transfer context
        /// @return whether the archive was accepted
        @Override
        public boolean importData(TransferSupport support) {
            TransferSupport transferSupport = Objects.requireNonNull(support, "support");
            if (!canImport(transferSupport)) {
                return false;
            }
            @Nullable Path archive = transferredArchive(transferSupport.getTransferable());
            return archive != null && selectArchive(archive);
        }
    }

    /// Bounded in-memory data source used through the same viewport contract as remote lists.
    @NotNullByDefault
    private static final class MojangOptionDataSource
            implements ViewportChoiceDataSource<MojangJavaRuntimeOption> {
        /// Immutable option snapshot visible to new range requests.
        private volatile @Unmodifiable List<MojangJavaRuntimeOption> options;

        /// Monotonic source revision used to reject results from a replaced snapshot.
        private final AtomicLong revision = new AtomicLong();

        /// Creates the bounded source from immutable option values.
        ///
        /// @param initialOptions initial Mojang options
        private MojangOptionDataSource(@Unmodifiable List<MojangJavaRuntimeOption> initialOptions) {
            options = List.copyOf(Objects.requireNonNull(initialOptions, "initialOptions"));
        }

        /// Replaces all option values and advances the source revision.
        ///
        /// @param replacement replacement Mojang options
        private void replace(@Unmodifiable List<MojangJavaRuntimeOption> replacement) {
            options = List.copyOf(Objects.requireNonNull(replacement, "replacement"));
            revision.incrementAndGet();
        }

        /// Returns the exact current option count.
        ///
        /// @return exact bounded row count
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(options.size());
        }

        /// Returns the current immutable-option revision.
        ///
        /// @return current source revision
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(revision.get());
        }

        /// Returns exactly the measured requested portion of the immutable option snapshot.
        ///
        /// @param desiredRange viewport-derived desired range
        /// @param cancellation cooperative cancellation signal
        /// @return immediately completed bounded page
        @Override
        public CompletionStage<ChoicePage<MojangJavaRuntimeOption>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            @Unmodifiable List<MojangJavaRuntimeOption> currentOptions = options;
            IndexRange actualRange = Objects.requireNonNull(desiredRange, "desiredRange")
                    .clampToItemCount(currentOptions.size());
            @Unmodifiable List<MojangJavaRuntimeOption> pageItems = List.copyOf(currentOptions.subList(
                    actualRange.startInclusive(),
                    actualRange.endExclusive()));
            ChoicePage<MojangJavaRuntimeOption> page = new ChoicePage<>(
                    actualRange,
                    pageItems,
                    OptionalInt.of(currentOptions.size()),
                    actualRange.endExclusive() == currentOptions.size());
            return CompletableFuture.completedFuture(page);
        }
    }
}
