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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Native Swing dialog for inspecting, replacing, and restoring one offline account's local skin.
///
/// Only a decodable user-selected local image can be persisted. The dialog never opens web content or downloads
/// skin data, and it disables mutations when the account's backing metadata is read-only.
@NotNullByDefault
final class SwingOfflineSkinManagementDialog extends JDialog {
    /// Offered arm models for local image files.
    private static final TextureModel @Unmodifiable [] TEXTURE_MODELS = {
            TextureModel.WIDE,
            TextureModel.SLIM
    };

    /// Persistent account-owned skin source and sink.
    private final OfflineSkinStore store;

    /// Stable account identifier captured from the currently selected loaded row.
    private final String accountId;

    /// Account name shown above the current skin state.
    private final JLabel profileName = new JLabel();

    /// Current effective skin source.
    private final JLabel source = new JLabel();

    /// Current configured local image path when available.
    private final JLabel localFile = new JLabel();

    /// Arm-model selector applied to the next local image configuration.
    private final JComboBox<TextureModel> textureModel = new JComboBox<>(TEXTURE_MODELS);

    /// User-triggered native file selection command.
    private final JButton chooseLocalFile = new JButton(i18n("account.skin.file"));

    /// Restores the UUID-derived launcher default skin.
    private final JButton restoreDefault = new JButton(i18n("button.reset"));

    /// Closes the modal dialog without any pending staged state.
    private final JButton closeButton = new JButton(i18n("button.cancel"));

    /// Inline validation and persistence failure text.
    private final JLabel status = new JLabel(" ");

    /// Latest skin state rendered in the dialog, or null after the account disappears.
    private @Nullable OfflineSkinSnapshot snapshot;

    /// Whether this dialog has been permanently closed.
    private boolean closed;

    /// Creates the modal skin-management dialog on the EDT.
    ///
    /// @param owner component that owns the dialog, or null for an unowned dialog
    /// @param store persistent offline-skin bridge
    /// @param accountId selected stable offline account identifier
    SwingOfflineSkinManagementDialog(
            @Nullable Component owner,
            OfflineSkinStore store,
            String accountId) {
        super(ownerWindow(owner), i18n("account.skin"), ModalityType.APPLICATION_MODAL);
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        configureComponents();
        refreshSnapshot();
    }

    /// Opens this modal dialog with a stable minimum working size.
    void open() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Offline skin dialog is closed");
        }
        pack();
        setMinimumSize(new Dimension(540, 270));
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    /// Builds state rows and commands without retaining any image or account object.
    private void configureComponents() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /// Marks the dialog closed after a window-manager or command close.
            @Override
            public void windowClosed(WindowEvent event) {
                closed = true;
            }
        });

        JPanel root = new JPanel(new MigLayout(
                "insets 16, fillx, wrap 2",
                "[pref!][grow,fill]",
                "[]8[]8[]8[]16[]8[]16[]"));
        root.add(new JLabel(i18n("account.skin")), "span 2");
        root.add(new JLabel("Profile"));
        profileName.setName("offlineSkinProfileName");
        root.add(profileName, "growx");
        root.add(new JLabel("Source"));
        source.setName("offlineSkinSource");
        root.add(source, "growx");
        root.add(new JLabel(i18n("account.skin.file")));
        localFile.setName("offlineSkinLocalFile");
        root.add(localFile, "growx, wmin 300");
        root.add(new JLabel(i18n("account.skin.model")));
        textureModel.setName("offlineSkinTextureModel");
        textureModel.setRenderer((list, value, index, selected, focused) -> new JLabel(
                value == TextureModel.SLIM
                        ? i18n("account.skin.model.slim")
                        : i18n("account.skin.model.default")));
        root.add(textureModel, "growx");

        chooseLocalFile.setName("offlineSkinChooseLocalFile");
        chooseLocalFile.addActionListener(event -> chooseLocalSkin());
        root.add(chooseLocalFile, "span 2, right, w 180!");

        restoreDefault.setName("offlineSkinRestoreDefault");
        restoreDefault.addActionListener(event -> restoreDefaultSkin());
        root.add(restoreDefault, "span 2, right, w 180!");

        status.setName("offlineSkinStatus");
        root.add(status, "growx");
        closeButton.setName("offlineSkinClose");
        closeButton.addActionListener(event -> dispose());
        root.add(closeButton, "w 110!");
        setContentPane(root);
    }

    /// Opens a native local-file chooser and persists a validated local image configuration.
    private void chooseLocalSkin() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (closed || current == null || !current.writable()) {
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n("account.skin.file"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG", "png"));
        @Nullable String currentPath = current.localSkinPath();
        if (currentPath != null) {
            chooser.setSelectedFile(Path.of(currentPath).toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        @Nullable java.io.File selectedFile = chooser.getSelectedFile();
        if (selectedFile == null) {
            return;
        }
        try {
            TextureModel model = Objects.requireNonNull(
                    (TextureModel) textureModel.getSelectedItem(),
                    "selected texture model");
            Skin skin = OfflineSkinService.createLocalSkin(selectedFile.toPath(), model);
            store.setSkin(accountId, skin);
            status.setText(" ");
            refreshSnapshot();
        } catch (IOException failure) {
            status.setText(i18n("account.skin.invalid_skin"));
        } catch (RuntimeException failure) {
            showFailure(failure);
        }
    }

    /// Restores the launcher default skin configuration after an explicit command.
    private void restoreDefaultSkin() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (closed || current == null || !current.writable() || current.usesDefaultSkin()) {
            return;
        }
        try {
            store.setSkin(accountId, null);
            status.setText(" ");
            refreshSnapshot();
        } catch (RuntimeException failure) {
            showFailure(failure);
        }
    }

    /// Reads and displays the latest selected offline-account skin state.
    private void refreshSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        Optional<OfflineSkinSnapshot> next = store.snapshot(accountId);
        snapshot = next.orElse(null);
        @Nullable OfflineSkinSnapshot current = snapshot;
        if (current == null) {
            profileName.setText(" ");
            source.setText(" ");
            localFile.setText(" ");
            status.setText(i18n("message.failed"));
            updateActionAvailability();
            return;
        }

        profileName.setText(current.profileName());
        source.setText(sourceText(current.skinType()));
        @Nullable String currentPath = current.localSkinPath();
        localFile.setText(currentPath == null ? " " : currentPath);
        @Nullable Skin skin = current.skin();
        if (skin != null && skin.type() == Skin.Type.LOCAL_FILE) {
            textureModel.setSelectedItem(skin.textureModel());
        }
        updateActionAvailability();
    }

    /// Enables only the commands that can persist the currently rendered offline account.
    private void updateActionAvailability() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable OfflineSkinSnapshot current = snapshot;
        boolean writable = !closed && current != null && current.writable();
        chooseLocalFile.setEnabled(writable);
        textureModel.setEnabled(writable);
        restoreDefault.setEnabled(writable && current != null && !current.usesDefaultSkin());
        closeButton.setEnabled(!closed);
    }

    /// Shows a localized or diagnostic persistence failure without opening another modal dialog.
    ///
    /// @param failure operation failure
    private void showFailure(RuntimeException failure) {
        @Nullable String message = Objects.requireNonNull(failure, "failure").getLocalizedMessage();
        status.setText(message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message);
    }

    /// Formats an effective persisted source with existing launcher translations where available.
    ///
    /// @param type effective skin source
    /// @return localized concise source name
    private static String sourceText(Skin.Type type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case DEFAULT -> i18n("message.default");
            case LOCAL_FILE -> i18n("account.skin.type.local_file");
            case STEVE -> i18n("account.skin.type.steve");
            case ALEX -> i18n("account.skin.type.alex");
            case LITTLE_SKIN -> i18n("account.skin.type.little_skin");
            case CUSTOM_SKIN_LOADER_API -> i18n("account.skin.type.csl_api");
            default -> type.name();
        };
    }

    /// Resolves an owning top-level window without retaining the source component.
    ///
    /// @param owner source component, or null for an unowned dialog
    /// @return owning window, or null when unavailable
    private static @Nullable Window ownerWindow(@Nullable Component owner) {
        return owner == null ? null : javax.swing.SwingUtilities.getWindowAncestor(owner);
    }
}
