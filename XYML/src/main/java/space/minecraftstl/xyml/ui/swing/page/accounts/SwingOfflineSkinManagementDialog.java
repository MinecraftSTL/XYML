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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Native Swing workflow for configuring and previewing one offline account's skin and optional cape.
///
/// Local and bundled image decoding always runs on the supplied worker. Provider sources are persisted without
/// contacting their endpoints, so opening this dialog never performs an implicit network request.
@NotNullByDefault
final class SwingOfflineSkinManagementDialog extends JDialog implements AutoCloseable {
    /// Card shown for sources without extra configuration controls.
    private static final String EMPTY_CARD = "empty";

    /// Card shown for a local skin and optional local cape.
    private static final String LOCAL_CARD = "local";

    /// Card shown for LittleSkin account-name guidance.
    private static final String LITTLE_SKIN_CARD = "littleSkin";

    /// Card shown for a custom-skin-loader endpoint.
    private static final String CUSTOM_API_CARD = "customApi";

    /// Persistable source choices in stable user-facing order.
    private static final Skin.Type @Unmodifiable [] SOURCE_TYPES = {
            Skin.Type.DEFAULT,
            Skin.Type.STEVE,
            Skin.Type.ALEX,
            Skin.Type.ARI,
            Skin.Type.EFE,
            Skin.Type.KAI,
            Skin.Type.MAKENA,
            Skin.Type.NOOR,
            Skin.Type.SUNNY,
            Skin.Type.ZURI,
            Skin.Type.LOCAL_FILE,
            Skin.Type.LITTLE_SKIN,
            Skin.Type.CUSTOM_SKIN_LOADER_API
    };

    /// Offered arm models for local image files.
    private static final TextureModel @Unmodifiable [] TEXTURE_MODELS = {
            TextureModel.WIDE,
            TextureModel.SLIM
    };

    /// Persistent account-owned skin source and sink.
    private final OfflineSkinStore store;

    /// Stable account identifier captured from the currently selected loaded row.
    private final String accountId;

    /// Caller-owned worker used only for image decoding and file validation.
    private final Executor worker;

    /// Account name shown above the editor.
    private final JLabel profileName = new JLabel();

    /// Current persisted source displayed independently from the staged selection.
    private final JLabel source = new JLabel();

    /// Staged skin source selector.
    private final JComboBox<Skin.Type> sourceType = new JComboBox<>(SOURCE_TYPES);

    /// Layout controlling the source-specific settings cards.
    private final CardLayout settingsCardLayout = new CardLayout();

    /// Source-specific settings card container.
    private final JPanel settingsCards = new JPanel(settingsCardLayout);

    /// Arm model applied to a local image configuration.
    private final JComboBox<TextureModel> textureModel = new JComboBox<>(TEXTURE_MODELS);

    /// Read-only staged local skin path.
    private final JTextField localFile = new JTextField();

    /// Read-only staged local cape path.
    private final JTextField capeFile = new JTextField();

    /// Custom-skin-loader endpoint input.
    private final JTextField customApi = new JTextField();

    /// Opens native local skin file selection.
    private final JButton chooseLocalFile = new JButton(i18n("account.skin.choose"));

    /// Opens native local cape file selection.
    private final JButton chooseCapeFile = new JButton(i18n("account.cape.choose"));

    /// Removes the optional staged local cape path.
    private final JButton clearCapeFile = new JButton(i18n("button.clear"));

    /// Immediately restores the profile-derived launcher default.
    private final JButton restoreDefault = new JButton(i18n("button.reset"));

    /// Persists the fully validated staged source.
    private final JButton saveButton = new JButton(i18n("button.save"));

    /// Closes the dialog without persisting further staged edits.
    private final JButton closeButton = new JButton(i18n("button.cancel"));

    /// Inline validation, persistence, and worker progress text.
    private final JLabel status = new JLabel(" ");

    /// Stable decoded-image preview surface.
    private final OfflineSkinPreviewPanel preview = new OfflineSkinPreviewPanel();

    /// Latest skin state rendered in the dialog, or null after the account disappears.
    private @Nullable OfflineSkinSnapshot snapshot;

    /// Staged local skin image path, or null until one has been selected.
    private @Nullable Path stagedSkinFile;

    /// Staged optional local cape image path.
    private @Nullable Path stagedCapeFile;

    /// Fully validated staged configuration, or null for default and invalid states.
    private @Nullable Skin validatedSkin;

    /// Whether the validated null value currently represents a valid default selection.
    private boolean validatedDefault;

    /// Most recently submitted image decode, or null when no decode is active.
    private @Nullable CompletableFuture<PreviewResult> activePreview;

    /// Monotonic identity used to discard stale image decode completions.
    private long previewRevision;

    /// Whether snapshot restoration is suppressing control listeners.
    private boolean applyingSnapshot;

    /// Whether this dialog has permanently released its callbacks.
    private boolean closed;

    /// Creates the modal skin-management dialog on the EDT using the shared launcher worker.
    ///
    /// @param owner component that owns the dialog, or null for an unowned dialog
    /// @param store persistent offline-skin bridge
    /// @param accountId selected stable offline account identifier
    SwingOfflineSkinManagementDialog(
            @Nullable Component owner,
            OfflineSkinStore store,
            String accountId) {
        this(owner, store, accountId, Schedulers.io());
    }

    /// Creates the modal skin-management dialog with an injected worker for focused tests.
    ///
    /// @param owner component that owns the dialog, or null for an unowned dialog
    /// @param store persistent offline-skin bridge
    /// @param accountId selected stable offline account identifier
    /// @param worker caller-owned image decoding executor
    SwingOfflineSkinManagementDialog(
            @Nullable Component owner,
            OfflineSkinStore store,
            String accountId,
            Executor worker) {
        super(ownerWindow(owner), i18n("account.skin"), ModalityType.APPLICATION_MODAL);
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.worker = Objects.requireNonNull(worker, "worker");
        configureComponents();
        refreshSnapshot();
    }

    /// Opens this modal dialog with a stable working size and releases pending preview callbacks on closure.
    void open() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Offline skin dialog is closed");
        }
        pack();
        setMinimumSize(new Dimension(860, 530));
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    /// Cancels pending preview publication and disposes the dialog on the EDT.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ++previewRevision;
        @Nullable CompletableFuture<PreviewResult> operation = activePreview;
        activePreview = null;
        if (operation != null) {
            operation.cancel(true);
        }
        EdtDispatcher.execute(this::dispose);
    }

    /// Builds account metadata, source settings, preview, and explicit persistence commands.
    private void configureComponents() {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /// Releases pending preview callbacks after a window-manager close.
            ///
            /// @param event terminal window event
            @Override
            public void windowClosed(WindowEvent event) {
                close();
            }
        });

        JPanel editor = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 2",
                "[pref!][grow,fill]",
                "[]8[]12[]12[grow,fill]"));
        editor.add(new JLabel(i18n("account.skin.profile")));
        profileName.setName("offlineSkinProfileName");
        editor.add(profileName, "growx");
        editor.add(new JLabel(i18n("account.skin.source.current")));
        source.setName("offlineSkinSource");
        editor.add(source, "growx");
        editor.add(new JLabel(i18n("account.skin.source")));
        configureSourceSelector();
        editor.add(sourceType, "growx");

        configureSettingsCards();
        editor.add(settingsCards, "span 2, grow, pushy");

        preview.getAccessibleContext().setAccessibleName(i18n("account.skin.preview"));
        JPanel content = new JPanel(new MigLayout(
                "insets 16, fill",
                "[grow,fill][320:360:420,fill]",
                "[grow,fill]"));
        content.add(editor, "grow");
        content.add(preview, "grow");

        JPanel root = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[grow,fill][]"));
        root.add(content, "grow");
        JPanel actions = new JPanel(new MigLayout(
                "insets 8 16 16 16, fillx",
                "[grow,fill][pref!][pref!][pref!]",
                "[]"));
        status.setName("offlineSkinStatus");
        actions.add(status, "growx");
        restoreDefault.setName("offlineSkinRestoreDefault");
        restoreDefault.addActionListener(event -> restoreDefaultSkin());
        actions.add(restoreDefault, "w 130!");
        saveButton.setName("offlineSkinSave");
        saveButton.addActionListener(event -> saveChanges());
        actions.add(saveButton, "w 110!");
        closeButton.setName("offlineSkinClose");
        closeButton.addActionListener(event -> close());
        actions.add(closeButton, "w 110!");
        root.add(actions, "growx");
        setContentPane(root);
    }

    /// Configures localized source rendering and staged-source transitions.
    private void configureSourceSelector() {
        sourceType.setName("offlineSkinSourceType");
        sourceType.setRenderer((
                JList<? extends Skin.Type> list,
                @Nullable Skin.Type value,
                int index,
                boolean selected,
                boolean focused) -> {
            DefaultListCellRenderer renderer = new DefaultListCellRenderer();
            return renderer.getListCellRendererComponent(
                    list,
                    value == null ? " " : sourceText(value),
                    index,
                    selected,
                    focused);
        });
        sourceType.addActionListener(event -> stagedConfigurationChanged());
    }

    /// Builds the empty, local-file, LittleSkin, and custom-provider settings cards.
    private void configureSettingsCards() {
        settingsCards.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        settingsCards.add(new JPanel(), EMPTY_CARD);
        settingsCards.add(createLocalSettings(), LOCAL_CARD);
        settingsCards.add(createLittleSkinSettings(), LITTLE_SKIN_CARD);
        settingsCards.add(createCustomApiSettings(), CUSTOM_API_CARD);
    }

    /// Creates local skin, model, and optional cape selectors.
    ///
    /// @return local-file settings panel
    private JPanel createLocalSettings() {
        JPanel local = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3",
                "[pref!][grow,fill][pref!]",
                "[]8[]8[]"));
        local.add(new JLabel(i18n("account.skin.model")));
        textureModel.setName("offlineSkinTextureModel");
        textureModel.setRenderer((
                JList<? extends TextureModel> list,
                @Nullable TextureModel value,
                int index,
                boolean selected,
                boolean focused) -> {
            DefaultListCellRenderer renderer = new DefaultListCellRenderer();
            String text = value == TextureModel.SLIM
                    ? i18n("account.skin.model.slim")
                    : i18n("account.skin.model.default");
            return renderer.getListCellRendererComponent(list, text, index, selected, focused);
        });
        textureModel.addActionListener(event -> stagedConfigurationChanged());
        local.add(textureModel, "span 2, growx");

        local.add(new JLabel(i18n("account.skin.file")));
        configurePathField(localFile, "offlineSkinLocalFile");
        local.add(localFile, "growx");
        chooseLocalFile.setName("offlineSkinChooseLocalFile");
        chooseLocalFile.addActionListener(event -> chooseLocalImage(false));
        local.add(chooseLocalFile, "w 130!");

        local.add(new JLabel(i18n("account.cape")));
        configurePathField(capeFile, "offlineSkinCapeFile");
        local.add(capeFile, "growx");
        JPanel capeActions = new JPanel(new MigLayout("insets 0", "[pref!][pref!]", "[]"));
        chooseCapeFile.setName("offlineSkinChooseCapeFile");
        chooseCapeFile.addActionListener(event -> chooseLocalImage(true));
        capeActions.add(chooseCapeFile, "w 130!");
        clearCapeFile.setName("offlineSkinClearCapeFile");
        clearCapeFile.addActionListener(event -> clearCape());
        capeActions.add(clearCapeFile, "w 90!");
        local.add(capeActions);
        return local;
    }

    /// Creates the non-editable LittleSkin account-name guidance card.
    ///
    /// @return LittleSkin guidance panel
    private static JPanel createLittleSkinSettings() {
        JPanel panel = new JPanel(new MigLayout("insets 0, fill", "[grow,fill]", "[grow,fill]"));
        JTextArea hint = new JTextArea(i18n("account.skin.type.little_skin.hint"));
        hint.setName("offlineSkinLittleSkinHint");
        hint.setEditable(false);
        hint.setFocusable(false);
        hint.setOpaque(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        panel.add(hint, "grow");
        return panel;
    }

    /// Creates the custom-skin-loader endpoint card.
    ///
    /// @return custom-provider settings panel
    private JPanel createCustomApiSettings() {
        JPanel panel = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[]8[]"));
        panel.add(new JLabel(i18n("account.skin.type.csl_api.location")));
        customApi.setName("offlineSkinCustomApi");
        customApi.putClientProperty(
                "JTextField.placeholderText",
                i18n("account.skin.type.csl_api.location.hint"));
        customApi.getDocument().addDocumentListener(new DocumentListener() {
            /// Handles text insertion.
            ///
            /// @param event document event
            @Override
            public void insertUpdate(DocumentEvent event) {
                changed();
            }

            /// Handles text removal.
            ///
            /// @param event document event
            @Override
            public void removeUpdate(DocumentEvent event) {
                changed();
            }

            /// Handles styled-document attribute changes.
            ///
            /// @param event document event
            @Override
            public void changedUpdate(DocumentEvent event) {
                changed();
            }

            /// Revalidates the staged custom endpoint.
            private void changed() {
                stagedConfigurationChanged();
            }
        });
        panel.add(customApi, "growx");
        return panel;
    }

    /// Configures one non-editable path display field.
    ///
    /// @param field path display field
    /// @param name stable component name
    private static void configurePathField(JTextField field, String name) {
        field.setName(name);
        field.setEditable(false);
        field.setFocusable(true);
    }

    /// Opens a native PNG chooser and stages either a skin or cape path for asynchronous validation.
    ///
    /// @param cape whether the selected file is an optional cape instead of the required skin
    private void chooseLocalImage(boolean cape) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (closed || current == null || !current.writable()) {
            return;
        }

        JFileChooser chooser = new EditablePathChooser();
        chooser.setDialogTitle(i18n(cape ? "account.cape.choose" : "account.skin.choose"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(i18n("account.skin.png_filter"), "png"));
        @Nullable Path currentPath = cape ? stagedCapeFile : stagedSkinFile;
        if (currentPath != null) {
            chooser.setSelectedFile(currentPath.toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        @Nullable java.io.File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        if (cape) {
            stagedCapeFile = selected.toPath();
        } else {
            stagedSkinFile = selected.toPath();
        }
        renderStagedPaths();
        schedulePreview();
    }

    /// Clears the optional local cape and revalidates the remaining local skin.
    private void clearCape() {
        EdtDispatcher.requireEventDispatchThread();
        stagedCapeFile = null;
        renderStagedPaths();
        schedulePreview();
    }

    /// Persists the fully validated staged source without re-decoding files on the EDT.
    private void saveChanges() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (closed || current == null || !current.writable()) {
            return;
        }
        if (!validatedDefault && validatedSkin == null) {
            return;
        }
        try {
            store.setSkin(accountId, validatedDefault ? null : validatedSkin);
            status.setText(i18n("account.skin.saved"));
            refreshSnapshot();
        } catch (RuntimeException failure) {
            showPersistenceFailure();
        }
    }

    /// Immediately restores the launcher default after an explicit reset command.
    private void restoreDefaultSkin() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (closed || current == null || !current.writable() || current.usesDefaultSkin()) {
            return;
        }
        try {
            store.setSkin(accountId, null);
            status.setText(i18n("account.skin.saved"));
            refreshSnapshot();
        } catch (RuntimeException failure) {
            showPersistenceFailure();
        }
    }

    /// Reads and stages the latest selected offline-account skin state.
    private void refreshSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        Optional<OfflineSkinSnapshot> next = store.snapshot(accountId);
        snapshot = next.orElse(null);
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (current == null) {
            profileName.setText(" ");
            source.setText(" ");
            status.setText(i18n("account.skin.account_missing"));
            validatedSkin = null;
            validatedDefault = false;
            preview.showMessage(i18n("account.skin.account_missing"));
            updateActionAvailability();
            return;
        }

        applyingSnapshot = true;
        try {
            profileName.setText(current.profileName());
            source.setText(sourceText(current.skinType()));
            @Nullable Skin skin = current.skin();
            Skin.Type selectedType = supportedSelection(current.skinType());
            sourceType.setSelectedItem(selectedType);
            customApi.setText(skin == null || skin.cslApi() == null ? "" : skin.cslApi());
            textureModel.setSelectedItem(skin == null ? TextureModel.WIDE : skin.textureModel());
            stagedSkinFile = skin == null ? null : pathOrNull(skin.localSkinPath());
            stagedCapeFile = skin == null ? null : pathOrNull(skin.localCapePath());
            renderStagedPaths();
            showSettingsCard(selectedType);
        } finally {
            applyingSnapshot = false;
        }
        if (!current.writable()) {
            status.setText(i18n("account.storage.read_only"));
        } else {
            status.setText(" ");
        }
        schedulePreview();
    }

    /// Handles a source, model, or endpoint edit after snapshot restoration completes.
    private void stagedConfigurationChanged() {
        if (applyingSnapshot || closed) {
            return;
        }
        @Nullable Skin.Type selected = (Skin.Type) sourceType.getSelectedItem();
        showSettingsCard(selected == null ? Skin.Type.DEFAULT : selected);
        schedulePreview();
    }

    /// Displays the settings card appropriate for one staged source.
    ///
    /// @param selected staged skin source
    private void showSettingsCard(Skin.Type selected) {
        String card = switch (Objects.requireNonNull(selected, "selected")) {
            case LOCAL_FILE -> LOCAL_CARD;
            case LITTLE_SKIN -> LITTLE_SKIN_CARD;
            case CUSTOM_SKIN_LOADER_API -> CUSTOM_API_CARD;
            default -> EMPTY_CARD;
        };
        settingsCardLayout.show(settingsCards, card);
    }

    /// Starts or replaces the network-free asynchronous preview validation for the staged source.
    private void schedulePreview() {
        EdtDispatcher.requireEventDispatchThread();
        ++previewRevision;
        long revision = previewRevision;
        @Nullable CompletableFuture<PreviewResult> previous = activePreview;
        activePreview = null;
        if (previous != null) {
            previous.cancel(true);
        }
        validatedSkin = null;
        validatedDefault = false;

        @Nullable OfflineSkinSnapshot current = snapshot;
        @Nullable Skin.Type selected = (Skin.Type) sourceType.getSelectedItem();
        if (closed || current == null || selected == null) {
            updateActionAvailability();
            return;
        }
        if (selected == Skin.Type.LITTLE_SKIN || selected == Skin.Type.CUSTOM_SKIN_LOADER_API) {
            stageProviderSource(selected);
            return;
        }

        preview.showMessage(i18n("account.skin.preview.loading"));
        if (current.writable()) {
            status.setText(i18n("account.skin.preview.loading"));
        }
        updateActionAvailability();
        String profile = current.profileName();
        @Nullable Path selectedSkin = stagedSkinFile;
        @Nullable Path selectedCape = stagedCapeFile;
        TextureModel model = Objects.requireNonNull(
                (TextureModel) textureModel.getSelectedItem(),
                "selected texture model");

        CompletableFuture<PreviewResult> operation = CompletableFuture.supplyAsync(() -> {
            try {
                @Nullable Skin candidate = switch (selected) {
                    case DEFAULT -> null;
                    case LOCAL_FILE -> {
                        if (selectedSkin == null) {
                            throw new IOException("Local skin path is missing");
                        }
                        yield OfflineSkinService.createLocalSkin(selectedSkin, selectedCape, model);
                    }
                    default -> OfflineSkinService.createBundledSkin(selected);
                };
                OfflineSkinPreview loaded = OfflineSkinPreviewLoader.load(
                        candidate,
                        profile,
                        current.profileId());
                return new PreviewResult(candidate, loaded);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, worker);
        activePreview = operation;
        operation.whenComplete((@Nullable PreviewResult result, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> completePreview(revision, result, failure)));
    }

    /// Validates a provider source without contacting it and displays the provider preview state.
    ///
    /// @param selected LittleSkin or custom-skin-loader source
    private void stageProviderSource(Skin.Type selected) {
        try {
            validatedSkin = OfflineSkinService.createProviderSkin(selected, customApi.getText());
            validatedDefault = false;
            preview.showMessage(i18n("account.skin.preview.remote"));
            @Nullable OfflineSkinSnapshot current = snapshot;
            if (current != null && current.writable()) {
                status.setText(" ");
            }
        } catch (IllegalArgumentException failure) {
            validatedSkin = null;
            validatedDefault = false;
            preview.showMessage(i18n("account.skin.invalid_api"));
            status.setText(i18n("account.skin.invalid_api"));
        }
        updateActionAvailability();
    }

    /// Publishes one image decode only when it still represents the latest staged state.
    ///
    /// @param revision submitted preview identity
    /// @param result decoded result, or null after failure
    /// @param failure decode failure, or null after success
    private void completePreview(
            long revision,
            @Nullable PreviewResult result,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || revision != previewRevision) {
            return;
        }
        activePreview = null;
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (failure != null || result == null) {
            validatedSkin = null;
            validatedDefault = false;
            preview.showMessage(i18n("account.skin.invalid_skin"));
            status.setText(i18n("account.skin.invalid_skin"));
            updateActionAvailability();
            return;
        }
        validatedSkin = result.skin();
        validatedDefault = result.skin() == null;
        preview.showPreview(result.preview());
        if (current != null && current.writable()) {
            status.setText(" ");
        }
        updateActionAvailability();
    }

    /// Enables only controls that are valid for the current account, source, and validation state.
    private void updateActionAvailability() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable OfflineSkinSnapshot current = snapshot;
        boolean writable = !closed && current != null && current.writable();
        @Nullable Skin.Type selected = (Skin.Type) sourceType.getSelectedItem();
        boolean local = selected == Skin.Type.LOCAL_FILE;
        boolean custom = selected == Skin.Type.CUSTOM_SKIN_LOADER_API;
        sourceType.setEnabled(writable);
        textureModel.setEnabled(writable && local);
        chooseLocalFile.setEnabled(writable && local);
        chooseCapeFile.setEnabled(writable && local);
        clearCapeFile.setEnabled(writable && local && stagedCapeFile != null);
        customApi.setEnabled(writable && custom);
        saveButton.setEnabled(writable && (validatedDefault || validatedSkin != null));
        restoreDefault.setEnabled(writable && current != null && !current.usesDefaultSkin());
        closeButton.setEnabled(!closed);
    }

    /// Copies staged path values into non-editable fields without changing their validation state.
    private void renderStagedPaths() {
        localFile.setText(stagedSkinFile == null ? "" : stagedSkinFile.toString());
        localFile.setCaretPosition(0);
        capeFile.setText(stagedCapeFile == null ? "" : stagedCapeFile.toString());
        capeFile.setCaretPosition(0);
    }

    /// Shows a localized persistence failure without exposing implementation exception text.
    private void showPersistenceFailure() {
        status.setText(i18n("message.failed"));
    }

    /// Maps a potentially reserved persisted source to one offered editor choice.
    ///
    /// @param type persisted source
    /// @return offered source, defaulting only for the unsupported reserved Yggdrasil source
    private static Skin.Type supportedSelection(Skin.Type type) {
        return type == Skin.Type.YGGDRASIL_API ? Skin.Type.DEFAULT : type;
    }

    /// Parses a persisted path without allowing malformed metadata to break the entire account dialog.
    ///
    /// @param text persisted path text, or null
    /// @return parsed path, or null for absent or malformed text
    private static @Nullable Path pathOrNull(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException failure) {
            return null;
        }
    }

    /// Formats a persisted source with launcher translations.
    ///
    /// @param type effective skin source
    /// @return localized concise source name
    private static String sourceText(Skin.Type type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case DEFAULT -> i18n("message.default");
            case LOCAL_FILE -> i18n("account.skin.type.local_file");
            case STEVE -> i18n("account.skin.type.steve");
            case ALEX -> i18n("account.skin.type.alex");
            case ARI -> i18n("account.skin.type.ari");
            case EFE -> i18n("account.skin.type.efe");
            case KAI -> i18n("account.skin.type.kai");
            case MAKENA -> i18n("account.skin.type.makena");
            case NOOR -> i18n("account.skin.type.noor");
            case SUNNY -> i18n("account.skin.type.sunny");
            case ZURI -> i18n("account.skin.type.zuri");
            case LITTLE_SKIN -> i18n("account.skin.type.little_skin");
            case CUSTOM_SKIN_LOADER_API -> i18n("account.skin.type.csl_api");
            case YGGDRASIL_API -> i18n("account.skin.type.yggdrasil_api");
        };
    }

    /// Resolves an owning top-level window without retaining the source component.
    ///
    /// @param owner source component, or null for an unowned dialog
    /// @return owning window, or null when unavailable
    private static @Nullable Window ownerWindow(@Nullable Component owner) {
        return owner == null ? null : javax.swing.SwingUtilities.getWindowAncestor(owner);
    }

    /// One generation-bound decoded preview and its exact validated persistence value.
    ///
    /// @param skin exact staged skin, or null for the launcher default
    /// @param preview decoded image payload
    @NotNullByDefault
    private record PreviewResult(@Nullable Skin skin, OfflineSkinPreview preview) {
        /// Validates the decoded image payload while preserving a meaningful nullable default skin.
        private PreviewResult {
            Objects.requireNonNull(preview, "preview");
        }
    }
}
