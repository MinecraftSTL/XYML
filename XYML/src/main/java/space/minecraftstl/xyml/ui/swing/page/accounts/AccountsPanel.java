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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.task.Schedulers;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents a sparse, viewport-driven list for choosing exactly one launcher account.
///
/// The panel owns its model subscription and viewport requests. It must be closed when its cached
/// shell page is permanently discarded.
@NotNullByDefault
public final class AccountsPanel extends JPanel implements AutoCloseable {
    /// Card name used while at least one account exists.
    private static final String LIST_CARD = "list";

    /// Card name used for an empty exact source.
    private static final String EMPTY_CARD = "empty";

    /// Toolkit-neutral account source and command model.
    private final AccountsModel model;

    /// Localized page and account-action text.
    private final AccountsStrings strings;

    /// Destructive confirmation, clipboard, and failure presentation boundary.
    private final AccountManagementInteraction interaction;

    /// Viewport-measured single-choice list.
    private final ViewportChoiceList<AccountListItem> choiceList;

    /// Cards that switch between the lazy list and exact empty state.
    private final JPanel listCards = new JPanel(new CardLayout());

    /// Add-account command.
    private final JButton addButton = new JButton();

    /// Configured authlib-injector server management command when the model supports it.
    private final JButton authlibServersButton = new JButton();

    /// Refresh or reauthentication command for the loaded selection.
    private final JButton refreshButton = new JButton();

    /// Profile UUID clipboard command for the loaded selection.
    private final JButton copyUuidButton = new JButton();

    /// Offline-skin management command for the selected loaded offline account.
    private final JButton offlineSkinButton = new JButton();

    /// Portable/global storage movement command for the selected account.
    private final JButton moveButton = new JButton();

    /// Explicit online skin upload command for the selected upload-capable account.
    private final JButton onlineSkinButton = new JButton();

    /// Permanent removal command for the loaded selection.
    private final JButton removeButton = new JButton();

    /// Owned model listener registration.
    private final Subscription modelSubscription;

    /// Listener that commits a user-selected placeholder after its row finishes loading.
    private final ListDataListener listDataListener = new ListDataListener() {
        /// Rechecks a changed loaded row.
        @Override
        public void intervalAdded(ListDataEvent event) {
            loadedRowsChanged();
        }

        /// Rechecks a changed loaded row.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            loadedRowsChanged();
        }

        /// Rechecks a changed loaded row.
        @Override
        public void contentsChanged(ListDataEvent event) {
            loadedRowsChanged();
        }
    };

    /// Snapshot currently represented by controls, or null before initialization.
    private @Nullable AccountsSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its loaded value, or -1 when none is pending.
    private int pendingUserSelectionIndex = -1;

    /// Whether programmatic selection restoration is suppressing command delegation.
    private boolean applyingSnapshot;

    /// Whether this panel has released subscriptions and load resources.
    private boolean closed;

    /// Whether one page-initiated authentication operation is active.
    private boolean refreshInProgress;

    /// Creates an account-selection panel on the EDT.
    ///
    /// @param model toolkit-neutral account model and viewport source
    /// @param strings localized page text
    public AccountsPanel(AccountsModel model, AccountsStrings strings) {
        this(model, strings, new SwingAccountManagementInteraction());
    }

    /// Creates an account-selection panel with injectable native side effects for headless tests.
    ///
    /// @param model toolkit-neutral account model and viewport source
    /// @param strings localized page text
    /// @param interaction confirmation, clipboard, and error boundary
    AccountsPanel(
            AccountsModel model,
            AccountsStrings strings,
            AccountManagementInteraction interaction) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[]16[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
        choiceList = new ViewportChoiceList<>(model, new AccountListCellRenderer());

        configureComponents(strings);
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(model.snapshot());
    }

    /// Returns the immutable snapshot currently represented by this page.
    ///
    /// @return displayed account-page state
    public AccountsSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial account snapshot was not applied");
    }

    /// Returns the viewport list for shell integrations and focused verification.
    ///
    /// @return viewport-driven account list
    public ViewportChoiceList<AccountListItem> choiceList() {
        return choiceList;
    }

    /// Releases the model subscription and viewport requests from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                modelSubscription.unsubscribe();
                choiceList.getChoiceModel().removeListDataListener(listDataListener);
                choiceList.close();
                updateActionAvailability();
            }
        });
    }

    /// Builds the stable title, command, list, and empty-state layout.
    ///
    /// @param strings localized page text
    private void configureComponents(AccountsStrings strings) {
        setOpaque(false);
        listCards.setName("accountsListCards");
        listCards.setOpaque(false);

        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][][]",
                "[]"));
        toolbar.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("accountsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        toolbar.add(heading);

        authlibServersButton.setName("accountsAuthlibServers");
        authlibServersButton.setText(i18n("account.methods.authlib_injector"));
        authlibServersButton.addActionListener(event -> openAuthlibServerManagement());
        authlibServersButton.setVisible(model.authlibServerStore().isPresent());
        toolbar.add(authlibServersButton, "h 40!");

        addButton.setName("accountsAdd");
        addButton.setText(strings.addAction());
        addButton.addActionListener(event -> model.addAccount());
        toolbar.add(addButton, "h 40!");
        add(toolbar, "growx");

        JPanel actions = new JPanel(new MigLayout(
                "insets 0, fillx, hidemode 3",
                "[grow][][][][][][]",
                "[]"));
        actions.setOpaque(false);
        actions.add(new JLabel(), "growx, pushx");

        configureActionButton(
                refreshButton,
                "accountsRefresh",
                "assets/swing/icons/refresh.svg",
                strings.refreshAction());
        refreshButton.addActionListener(event -> refreshSelectedAccount());
        actions.add(refreshButton, "w 36!, h 36!");

        configureActionButton(
                copyUuidButton,
                "accountsCopyUuid",
                "assets/swing/icons/content-copy.svg",
                strings.copyUuidAction());
        copyUuidButton.addActionListener(event -> copySelectedUuid());
        actions.add(copyUuidButton, "w 36!, h 36!");

        configureActionButton(
                offlineSkinButton,
                "accountsOfflineSkin",
                "assets/swing/icons/image.svg",
                i18n("account.skin"));
        offlineSkinButton.addActionListener(event -> openOfflineSkinManagement());
        offlineSkinButton.setVisible(model.offlineSkinStore().isPresent());
        actions.add(offlineSkinButton, "w 36!, h 36!");

        configureActionButton(
                moveButton,
                "accountsMove",
                "assets/swing/icons/output.svg",
                i18n("account.move_to_portable"));
        moveButton.addActionListener(event -> moveSelectedAccount());
        moveButton.setVisible(model.accountPortabilityStore().isPresent());
        actions.add(moveButton, "w 36!, h 36!");

        configureActionButton(
                onlineSkinButton,
                "accountsOnlineSkin",
                "assets/swing/icons/file-import.svg",
                i18n("account.skin.upload"));
        onlineSkinButton.addActionListener(event -> openOnlineSkinUpload());
        onlineSkinButton.setVisible(model.accountSkinUploadStore().isPresent());
        actions.add(onlineSkinButton, "w 36!, h 36!");

        configureActionButton(
                removeButton,
                "accountsRemove",
                "assets/swing/icons/delete-forever.svg",
                strings.removeAction());
        removeButton.addActionListener(event -> removeSelectedAccount());
        actions.add(removeButton, "w 36!, h 36!");
        add(actions, "growx");

        choiceList.setName("accountsList");
        choiceList.setOpaque(false);
        choiceList.getViewport().setOpaque(false);
        JList<ChoiceListEntry<AccountListItem>> list = choiceList.getList();
        list.setName("accountsListView");
        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !applyingSnapshot) {
                pendingUserSelectionIndex = list.getSelectedIndex();
                submitPendingUserSelection();
                updateActionAvailability();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        JLabel emptyLabel = new JLabel(strings.emptyText(), SwingConstants.CENTER);
        emptyLabel.setName("accountsEmpty");
        listCards.add(choiceList, LIST_CARD);
        listCards.add(emptyLabel, EMPTY_CARD);
        add(listCards, "grow");
    }

    /// Coalesces a worker-published transition to the model's latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated the displayed page
    private void modelChanged(ValueChange<AccountsSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies one immutable state and reloads only when indexed content changed.
    ///
    /// @param snapshot latest page state
    private void applySnapshot(AccountsSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable AccountsSnapshot previous = displayedSnapshot;
        boolean contentChanged = previous == null
                || previous.contentRevision() != snapshot.contentRevision();
        displayedSnapshot = snapshot;

        if (contentChanged) {
            pendingUserSelectionIndex = -1;
            choiceList.reloadData();
        }

        ((CardLayout) listCards.getLayout()).show(
                listCards,
                snapshot.itemCount() == 0 ? EMPTY_CARD : LIST_CARD);
        restoreSelection(snapshot.selectedIndex());
        updateActionAvailability();
    }

    /// Restores the model-selected row without delegating it back as a user command.
    ///
    /// @param selectedIndex selected source index, or empty for no selection
    private void restoreSelection(OptionalInt selectedIndex) {
        int targetIndex = selectedIndex.orElse(-1);
        if (targetIndex >= choiceList.getChoiceModel().getSize()) {
            targetIndex = -1;
        }
        if (choiceList.getList().getSelectedIndex() == targetIndex) {
            return;
        }

        pendingUserSelectionIndex = -1;
        applyingSnapshot = true;
        try {
            choiceList.getList().setSelectedIndex(targetIndex);
            if (targetIndex >= 0) {
                choiceList.getList().ensureIndexIsVisible(targetIndex);
            }
        } finally {
            applyingSnapshot = false;
        }
        choiceList.refreshLoadPlan();
        updateActionAvailability();
    }

    /// Commits a pending user selection once its sparse row has loaded.
    private void submitPendingUserSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || applyingSnapshot || pendingUserSelectionIndex < 0
                || choiceList.getList().getSelectedIndex() != pendingUserSelectionIndex) {
            return;
        }

        @Nullable AccountListItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            pendingUserSelectionIndex = -1;
            model.selectAccount(selected.accountId());
            updateActionAvailability();
        }
    }

    /// Reconciles commands and a pending user choice after sparse row content changes.
    private void loadedRowsChanged() {
        submitPendingUserSelection();
        updateActionAvailability();
    }

    /// Confirms and permanently removes the currently loaded account.
    private void removeSelectedAccount() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null || refreshInProgress) {
            return;
        }
        String message = strings.removeConfirmation() + "\n\n" + selected.displayName();
        if (!interaction.confirmRemoval(this, strings.removeAction(), message)) {
            return;
        }
        try {
            model.removeAccount(selected.accountId(), false);
        } catch (AccountStorageOverwriteRequiredException failure) {
            confirmOverwriteAndRemove(selected, failure);
        } catch (RuntimeException failure) {
            showActionFailure(failure);
        }
        updateActionAvailability();
    }

    /// Confirms storage recovery and retries one stable account removal with explicit overwrite consent.
    ///
    /// @param selected loaded account row captured before the first removal attempt
    /// @param failure read-only signal from that exact attempt
    private void confirmOverwriteAndRemove(
            AccountListItem selected,
            AccountStorageOverwriteRequiredException failure) {
        if (!selected.accountId().equals(failure.accountId())) {
            showActionFailure(failure);
            return;
        }
        if (!interaction.confirmReadOnlyOverwrite(this)) {
            return;
        }
        try {
            model.removeAccount(selected.accountId(), true);
        } catch (RuntimeException retryFailure) {
            showActionFailure(retryFailure);
        }
    }

    /// Starts nonblocking reauthentication for the currently loaded account.
    private void refreshSelectedAccount() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null || refreshInProgress) {
            return;
        }

        refreshInProgress = true;
        updateActionAvailability();
        final CompletionStage<Void> completion;
        try {
            completion = Objects.requireNonNull(
                    model.refreshAccount(selected.accountId()),
                    "account model returned no refresh completion");
        } catch (Throwable failure) {
            refreshInProgress = false;
            updateActionAvailability();
            showActionFailure(failure);
            return;
        }
        completion.whenComplete((ignored, failure) -> SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            refreshInProgress = false;
            updateActionAvailability();
            @Nullable Throwable resolved = unwrapFailure(failure);
            if (resolved != null
                    && !(resolved instanceof CancellationException)
                    && !(resolved instanceof AccountReauthenticationException)) {
                showActionFailure(resolved);
            }
        }));
    }

    /// Copies the selected profile UUID through the injected AWT clipboard boundary.
    private void copySelectedUuid() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null || refreshInProgress) {
            return;
        }
        try {
            interaction.copyText(selected.profileId());
        } catch (Throwable failure) {
            showActionFailure(failure);
        }
    }

    /// Opens the owned persistent authlib-injector server workflow when this account source supports it.
    private void openAuthlibServerManagement() {
        openAuthlibServerManagement(this, null);
    }

    /// Opens server registration for an endpoint dropped anywhere on the application shell.
    ///
    /// The dialog resolves metadata automatically but preserves its explicit Add confirmation.
    ///
    /// @param owner visible shell component owning the modal workflow
    /// @param endpoint decoded authlib-injector server endpoint
    public void openDroppedAuthlibServer(Component owner, String endpoint) {
        EdtDispatcher.requireEventDispatchThread();
        openAuthlibServerManagement(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(endpoint, "endpoint"));
    }

    /// Opens ordinary management or prefilled endpoint registration when supported by the model.
    ///
    /// @param owner component owning the modal workflow
    /// @param initialEndpoint prefilled endpoint, or null for ordinary management
    private void openAuthlibServerManagement(
            Component owner,
            @Nullable String initialEndpoint) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || refreshInProgress) {
            return;
        }
        model.authlibServerStore().ifPresent(store -> {
            SwingAuthlibServerManagementDialog dialog = new SwingAuthlibServerManagementDialog(
                    owner,
                    store,
                    Schedulers.io());
            if (initialEndpoint == null) {
                dialog.open();
            } else {
                dialog.openForEndpoint(initialEndpoint);
            }
        });
    }

    /// Opens local skin management only when the loaded selection remains an actual offline account.
    private void openOfflineSkinManagement() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || refreshInProgress) {
            return;
        }
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null) {
            return;
        }
        model.offlineSkinStore().ifPresent(store -> {
            if (store.snapshot(selected.accountId()).isPresent()) {
                new SwingOfflineSkinManagementDialog(this, store, selected.accountId()).open();
            }
        });
    }

    /// Moves the loaded selection between portable and global storage with read-only recovery consent.
    private void moveSelectedAccount() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || refreshInProgress) {
            return;
        }
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null) {
            return;
        }
        model.accountPortabilityStore().ifPresent(store -> {
            try {
                store.move(selected.accountId(), false);
            } catch (AccountStorageOverwriteRequiredException failure) {
                if (!selected.accountId().equals(failure.accountId())) {
                    showActionFailure(failure);
                } else if (interaction.confirmReadOnlyOverwrite(this)) {
                    try {
                        store.move(selected.accountId(), true);
                    } catch (RuntimeException retryFailure) {
                        showActionFailure(retryFailure);
                    }
                }
            } catch (RuntimeException failure) {
                showActionFailure(failure);
            }
        });
        updateActionAvailability();
    }

    /// Opens local validation and preview for an upload-capable online account.
    private void openOnlineSkinUpload() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || refreshInProgress) {
            return;
        }
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null) {
            return;
        }
        model.accountSkinUploadStore().ifPresent(store -> {
            if (!store.canUpload(selected.accountId())) {
                return;
            }
            AccountSkinUploadCommand command = (skinFile, slim) -> model
                    .refreshAccount(selected.accountId())
                    .thenComposeAsync(
                            ignored -> store.upload(selected.accountId(), skinFile, slim),
                            Schedulers.ui())
                    .thenComposeAsync(
                            ignored -> model.refreshAccount(selected.accountId()),
                            Schedulers.ui());
            new SwingOnlineSkinUploadDialog(this, selected.displayName(), command).open();
            updateActionAvailability();
        });
    }

    /// Returns the currently loaded selected row, excluding sparse placeholders.
    ///
    /// @return selected account row, or null while no loaded row is selected
    private @Nullable AccountListItem selectedItem() {
        return choiceList.getSelectedValue();
    }

    /// Enables selection actions only for one loaded row outside active refresh.
    private void updateActionAvailability() {
        EdtDispatcher.requireEventDispatchThread();
        boolean hasSelection = !closed && !refreshInProgress && selectedItem() != null;
        refreshButton.setEnabled(hasSelection);
        copyUuidButton.setEnabled(hasSelection);
        removeButton.setEnabled(hasSelection);
        addButton.setEnabled(!closed && !refreshInProgress);
        authlibServersButton.setEnabled(
                !closed && !refreshInProgress && model.authlibServerStore().isPresent());
        offlineSkinButton.setVisible(model.offlineSkinStore().isPresent());
        offlineSkinButton.setEnabled(
                hasSelection && selectedOfflineSkin() != null);
        moveButton.setVisible(model.accountPortabilityStore().isPresent());
        @Nullable AccountPortabilitySnapshot portability = hasSelection ? selectedPortability() : null;
        moveButton.setEnabled(hasSelection && portability != null);
        if (portability != null) {
            updateButtonDescription(
                    moveButton,
                    i18n(portability.portable()
                            ? "account.move_to_global"
                            : "account.move_to_portable"));
        }
        onlineSkinButton.setVisible(model.accountSkinUploadStore().isPresent());
        @Nullable AccountListItem selected = selectedItem();
        onlineSkinButton.setEnabled(hasSelection
                && selected != null
                && model.accountSkinUploadStore()
                        .map(store -> store.canUpload(selected.accountId()))
                        .orElse(false));
        choiceList.getList().setEnabled(!closed && !refreshInProgress);
    }

    /// Returns the selected account's offline-skin state only after its sparse row has loaded.
    ///
    /// @return selected offline account skin state, or null for another account type or no selection
    private @Nullable OfflineSkinSnapshot selectedOfflineSkin() {
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null) {
            return null;
        }
        return model.offlineSkinStore()
                .flatMap(store -> store.snapshot(selected.accountId()))
                .orElse(null);
    }

    /// Returns storage-location state only for a currently loaded selected account.
    ///
    /// @return selected account portability, or null for no loaded selection or unsupported source
    private @Nullable AccountPortabilitySnapshot selectedPortability() {
        @Nullable AccountListItem selected = selectedItem();
        if (selected == null) {
            return null;
        }
        return model.accountPortabilityStore()
                .flatMap(store -> store.portability(selected.accountId()))
                .orElse(null);
    }

    /// Presents one synchronous or asynchronous account-action failure through Swing.
    ///
    /// @param failure action failure
    private void showActionFailure(Throwable failure) {
        Throwable resolved = Objects.requireNonNull(unwrapFailure(failure), "resolved failure");
        @Nullable String localized = resolved.getLocalizedMessage();
        String message = localized == null || localized.isBlank()
                ? resolved.getClass().getSimpleName()
                : localized;
        interaction.showFailure(this, strings.errorTitle(), message);
    }

    /// Removes common asynchronous wrappers from one optional failure.
    ///
    /// @param failure asynchronous failure, or null after success
    /// @return meaningful cause, or null after success
    private static @Nullable Throwable unwrapFailure(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Configures one fixed icon-only account command with tooltip and accessibility text.
    ///
    /// @param button target command button
    /// @param name stable component name
    /// @param iconResource bundled SVG resource
    /// @param description localized command description
    private static void configureActionButton(
            JButton button,
            String name,
            String iconResource,
            String description) {
        button.setName(Objects.requireNonNull(name, "name"));
        button.setText(null);
        button.setIcon(themeIcon(iconResource));
        updateButtonDescription(button, description);
    }

    /// Creates a bundled SVG command icon that follows the owning button foreground.
    ///
    /// @param iconResource classpath SVG resource
    /// @return theme-aware fixed-size icon
    private static FlatSVGIcon themeIcon(String iconResource) {
        FlatSVGIcon icon = new FlatSVGIcon(Objects.requireNonNull(iconResource, "iconResource"), 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(AccountsPanel::resolveIconColor));
        return icon;
    }

    /// Resolves SVG color from its current owning component in light and dark themes.
    ///
    /// @param component owning button, or null during standalone rendering
    /// @param originalColor authored SVG fallback color
    /// @return current component foreground or the authored fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color fallback = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        return foreground == null ? fallback : foreground;
    }

    /// Updates tooltip and accessibility text for a state-dependent icon command.
    ///
    /// @param button target command button
    /// @param description localized command description, possibly containing line breaks
    private static void updateButtonDescription(JButton button, String description) {
        String value = Objects.requireNonNull(description, "description");
        button.setToolTipText("<html>" + value.replace("\n", "<br>") + "</html>");
        button.getAccessibleContext().setAccessibleName(value.replace('\n', ' '));
    }
}
