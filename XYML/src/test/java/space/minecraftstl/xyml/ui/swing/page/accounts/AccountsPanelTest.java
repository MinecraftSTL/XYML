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

import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests account commands, placeholder selection, dynamic reload, and viewport-sized demand.
@NotNullByDefault
public final class AccountsPanelTest {
    /// Localized strings used by the focused page tests.
    private static final AccountsStrings STRINGS = new AccountsStrings(
            "Accounts",
            "Add account",
            "Refresh",
            "Copy UUID",
            "Delete",
            "Remove permanently?",
            "Account error",
            "No accounts");

    /// Account list containers and renderer rows leave rounded selection corners transparent.
    @Test
    public void keepsAccountContentBackgroundTransparent() {
        FakeAccountsModel model = FakeAccountsModel.immediate(items(2), snapshot(0, 2, 0L));
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            JList<ChoiceListEntry<AccountListItem>> list = panel.choiceList().getList();
            ListCellRenderer<? super ChoiceListEntry<AccountListItem>> renderer = list.getCellRenderer();
            JComponent unselectedRow = (JComponent) renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loading(0),
                    0,
                    false,
                    false);
            boolean unselectedOpaque = unselectedRow.isOpaque();
            JComponent selectedRow = (JComponent) renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loading(0),
                    0,
                    true,
                    false);

            assertAll(
                    () -> assertFalse(panel.isOpaque()),
                    () -> assertFalse(((JComponent) findComponent(
                            panel, "accountsListCards")).isOpaque()),
                    () -> assertFalse(panel.choiceList().isOpaque()),
                    () -> assertFalse(panel.choiceList().getViewport().isOpaque()),
                    () -> assertFalse(list.isOpaque()),
                    () -> assertFalse(unselectedOpaque),
                    () -> assertFalse(selectedRow.isOpaque()));
            panel.close();
        });
    }

    /// Loaded rows delegate commands once and warm one measured viewport beyond first display.
    @Test
    public void delegatesCommandsAndUsesMeasuredVisibleRange() {
        FakeAccountsModel model = FakeAccountsModel.immediate(items(1_000), snapshot(0, 1_000, 0L));
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 520));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<ChoiceListEntry<AccountListItem>> list = panel.choiceList().getList();
            IndexRange requested = model.requestedRanges().get(0);
            int viewportHeight = panel.choiceList().getViewport().getExtentSize().height;
            int measuredRowHeight = list.getFixedCellHeight();
            int expectedVisibleRows = (viewportHeight + measuredRowHeight - 1) / measuredRowHeight;

            list.setSelectedIndex(1);
            findButton(panel, "accountsAdd").doClick();

            assertAll(
                    () -> assertEquals(ListSelectionModel.SINGLE_SELECTION, list.getSelectionMode()),
                    () -> assertEquals(expectedVisibleRows * 2, requested.length()),
                    () -> assertTrue(requested.length() < model.exactItemCount().orElseThrow()),
                    () -> assertEquals(List.of("account-1"), model.selectedIds()),
                    () -> assertEquals(1, model.additions.get()));
            panel.close();
        });
    }

    /// Management actions use stable loaded-row data, injected confirmation, and nonblocking refresh completion.
    @Test
    public void delegatesRemovalRefreshAndUuidCopyThroughInjectedBoundaries() {
        FakeAccountsModel model = FakeAccountsModel.immediate(items(2), snapshot(0, 2, 0L));
        CompletableFuture<Void> refreshCompletion = new CompletableFuture<>();
        model.setRefreshCompletion(refreshCompletion);
        RecordingAccountManagementInteraction interaction = new RecordingAccountManagementInteraction();
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(model, STRINGS, interaction));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            findButton(panel, "accountsCopyUuid").doClick();
            interaction.allowRemoval = false;
            findButton(panel, "accountsRemove").doClick();
            interaction.allowRemoval = true;
            findButton(panel, "accountsRemove").doClick();
            findButton(panel, "accountsRefresh").doClick();

            assertAll(
                    () -> assertEquals(List.of("profile-0"), interaction.copiedText),
                    () -> assertEquals(2, interaction.confirmations.get()),
                    () -> assertEquals(List.of("account-0"), model.removedIds()),
                    () -> assertEquals(List.of("account-0"), model.refreshedIds()),
                    () -> assertFalse(findButton(panel, "accountsRefresh").isEnabled()),
                    () -> assertFalse(panel.choiceList().getList().isEnabled()));
        });

        refreshCompletion.complete(null);
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(findButton(panel, "accountsRefresh").isEnabled()),
                    () -> assertTrue(panel.choiceList().getList().isEnabled()),
                    () -> assertEquals(List.of(), interaction.failures));
            panel.close();
        });
    }

    /// Read-only storage requires a separate explicit backup-and-overwrite decision before deletion.
    @Test
    public void confirmsReadOnlyOverwriteBeforeRemovalRetry() {
        FakeAccountsModel model = FakeAccountsModel.immediate(items(1), snapshot(0, 1, 0L));
        model.setRemovalRequiresOverwrite(true);
        RecordingAccountManagementInteraction interaction = new RecordingAccountManagementInteraction();
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(model, STRINGS, interaction));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 360));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            findButton(panel, "accountsRemove").doClick();
            interaction.allowOverwrite = true;
            findButton(panel, "accountsRemove").doClick();

            assertAll(
                    () -> assertEquals(2, interaction.confirmations.get()),
                    () -> assertEquals(2, interaction.overwriteConfirmations.get()),
                    () -> assertEquals(List.of("account-0"), model.removedIds()),
                    () -> assertEquals(List.of(), interaction.failures));
            panel.close();
        });
    }

    /// Generic refresh failures are routed to the injected Swing error boundary on the EDT.
    @Test
    public void presentsRefreshFailureThroughInteraction() {
        FakeAccountsModel model = FakeAccountsModel.immediate(items(1), snapshot(0, 1, 0L));
        model.setRefreshCompletion(CompletableFuture.failedFuture(
                new IllegalStateException("refresh failed")));
        RecordingAccountManagementInteraction interaction = new RecordingAccountManagementInteraction();
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(model, STRINGS, interaction));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 360));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            findButton(panel, "accountsRefresh").doClick();

            assertEquals(List.of("refresh failed"), interaction.failures);
            panel.close();
        });
    }

    /// A user-selected placeholder is committed exactly once after its sparse row finishes loading.
    @Test
    public void commitsPlaceholderSelectionAfterLoad() {
        FakeAccountsModel model = FakeAccountsModel.controlled(items(40), snapshot(-1, 40, 0L));
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 360));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(2);
            assertEquals(List.of(), model.selectedIds());
        });

        model.completePendingLoads();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertEquals(List.of("account-2"), model.selectedIds());
            panel.choiceList().refreshLoadPlan();
            assertEquals(List.of("account-2"), model.selectedIds());
            panel.close();
        });
    }

    /// A worker-published content revision reloads the exact source and restores selection on the EDT.
    @Test
    public void reloadsWorkerPublishedRevisionAndClosesIdempotently() throws InterruptedException {
        FakeAccountsModel model = FakeAccountsModel.immediate(items(3), snapshot(1, 3, 0L));
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(model, STRINGS));
        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(820, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });
        int requestsBeforeRevision = model.requestedRanges().size();

        AccountsSnapshot replacement = new AccountsSnapshot(OptionalInt.of(4), 5, 1L);
        Thread publisher = new Thread(
                () -> model.replaceItemsAndPublish(items(5), replacement),
                "accounts-panel-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(replacement, panel.displayedSnapshot()),
                    () -> assertEquals(5, panel.choiceList().getChoiceModel().getSize()),
                    () -> assertEquals(4, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertTrue(model.requestedRanges().size() > requestsBeforeRevision));
            panel.close();
            panel.close();
            assertFalse(model.hasSubscribers());
        });
    }

    /// Exact empty and long-row states paint correctly inside a constrained page surface.
    ///
    /// @throws IOException when the visual report cannot be written
    @Test
    public void paintsConstrainedAndEmptySurfaces() throws IOException {
        FakeAccountsModel populated = FakeAccountsModel.immediate(
                List.of(new AccountListItem(
                        "long-account",
                        "A very long account display name that must remain inside its viewport row",
                        "External authentication provider with a long descriptive status",
                        "profile-long-account")),
                snapshot(0, 1, 0L));
        populated.setOfflineSkinStore(new FakeOfflineSkinStore("long-account"));
        populated.setAccountPortabilityStore(new FakeAccountPortabilityStore("long-account", false, true));
        populated.setAccountSkinUploadStore(new FakeAccountSkinUploadStore("long-account"));
        onEventDispatchThread(() -> {
            FlatLightLaf.setup();
        });
        AccountsPanel panel = onEventDispatchThread(() -> new AccountsPanel(populated, STRINGS));

        onEventDispatchThread(() -> {
            Dimension size = new Dimension(720, 420);
            panel.setSize(size);
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
        });
        awaitLoadedAvatar(panel);

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = new Dimension(720, 420);
            layoutRecursively(panel);
            BufferedImage rendered = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            panel.close();
            return rendered;
        });
        assertTrue(distinctColors(image).size() > 4);
        String configuredReportRoot = Objects.toString(System.getenv("XYML_VISUAL_REPORT_DIR"), "");
        Path reportRoot = configuredReportRoot.isBlank()
                ? Path.of("build", "reports", "swing-pages").toAbsolutePath()
                : Path.of(configuredReportRoot).toAbsolutePath();
        Files.createDirectories(reportRoot);
        assertTrue(ImageIO.write(image, "PNG", reportRoot.resolve("accounts-page.png").toFile()));

        FakeAccountsModel empty = FakeAccountsModel.immediate(List.of(), snapshot(-1, 0, 0L));
        AccountsPanel emptyPanel = onEventDispatchThread(() -> new AccountsPanel(empty, STRINGS));
        onEventDispatchThread(() -> {
            assertTrue(findComponent(emptyPanel, "accountsEmpty").isVisible());
            emptyPanel.close();
        });
    }

    /// Waits for one real viewport row and its asynchronously decoded bundled avatar.
    ///
    /// @param panel account page whose first row is being rendered
    private static void awaitLoadedAvatar(AccountsPanel panel) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            boolean loaded = onEventDispatchThread(() -> {
                JList<ChoiceListEntry<AccountListItem>> list = panel.choiceList().getList();
                if (list.getModel().getSize() == 0) {
                    return false;
                }
                ChoiceListEntry<AccountListItem> entry = list.getModel().getElementAt(0);
                Component renderer = list.getCellRenderer().getListCellRendererComponent(
                        list,
                        entry,
                        0,
                        true,
                        false);
                if (!(renderer instanceof Container container)) {
                    return false;
                }
                Component avatar = findComponent(container, "accountListAvatar");
                return avatar instanceof JLabel label && label.getIcon() instanceof ImageIcon;
            });
            if (loaded) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for account avatar", interrupted);
            }
        }
        assertTrue(false, "Timed out waiting for account avatar");
    }

    /// The persistent authlib-injector server command is visible only for a model that actually owns it.
    @Test
    public void showsAuthlibServerManagementOnlyWhenModelSupportsIt() {
        FakeAccountsModel generic = FakeAccountsModel.immediate(items(1), snapshot(0, 1, 0L));
        AccountsPanel genericPanel = onEventDispatchThread(() -> new AccountsPanel(generic, STRINGS));

        FakeAccountsModel capable = FakeAccountsModel.immediate(items(1), snapshot(0, 1, 0L));
        capable.setAuthlibServerStore(new FakeAuthlibServerStore());
        AccountsPanel capablePanel = onEventDispatchThread(() -> new AccountsPanel(capable, STRINGS));

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertFalse(findButton(genericPanel, "accountsAuthlibServers").isVisible()),
                    () -> assertTrue(findButton(capablePanel, "accountsAuthlibServers").isVisible()));
            genericPanel.close();
            capablePanel.close();
        });
    }

    /// The local skin command remains unavailable for non-offline rows even when the model owns a skin store.
    @Test
    public void enablesOfflineSkinManagementOnlyForSelectedOfflineAccount() {
        FakeAccountsModel generic = FakeAccountsModel.immediate(items(2), snapshot(0, 2, 0L));
        AccountsPanel genericPanel = onEventDispatchThread(() -> new AccountsPanel(generic, STRINGS));

        FakeAccountsModel capable = FakeAccountsModel.immediate(items(2), snapshot(0, 2, 0L));
        capable.setOfflineSkinStore(new FakeOfflineSkinStore("account-0"));
        AccountsPanel capablePanel = onEventDispatchThread(() -> new AccountsPanel(capable, STRINGS));

        onEventDispatchThread(() -> {
            genericPanel.setSize(new Dimension(820, 420));
            capablePanel.setSize(new Dimension(820, 420));
            layoutRecursively(genericPanel);
            layoutRecursively(capablePanel);
            genericPanel.choiceList().refreshLoadPlan();
            capablePanel.choiceList().refreshLoadPlan();
        });
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            AbstractButton genericButton = findButton(genericPanel, "accountsOfflineSkin");
            AbstractButton capableButton = findButton(capablePanel, "accountsOfflineSkin");
            assertAll(
                    () -> assertFalse(genericButton.isVisible()),
                    () -> assertTrue(capableButton.isVisible()),
                    () -> assertTrue(capableButton.isEnabled()));

            capablePanel.choiceList().getList().setSelectedIndex(1);
            assertFalse(capableButton.isEnabled());
            genericPanel.close();
            capablePanel.close();
        });
    }

    /// Portability and online upload commands reflect exact model capabilities without eager uploads.
    @Test
    public void exposesPortableStorageAndExplicitOnlineSkinCommands() {
        FakeAccountsModel generic = FakeAccountsModel.immediate(items(1), snapshot(0, 1, 0L));
        AccountsPanel genericPanel = onEventDispatchThread(() -> new AccountsPanel(generic, STRINGS));

        FakeAccountsModel capable = FakeAccountsModel.immediate(items(1), snapshot(0, 1, 0L));
        FakeAccountPortabilityStore portability = new FakeAccountPortabilityStore("account-0", false, false);
        FakeAccountSkinUploadStore uploads = new FakeAccountSkinUploadStore("account-0");
        capable.setAccountPortabilityStore(portability);
        capable.setAccountSkinUploadStore(uploads);
        RecordingAccountManagementInteraction interaction = new RecordingAccountManagementInteraction();
        interaction.allowOverwrite = true;
        AccountsPanel capablePanel = onEventDispatchThread(
                () -> new AccountsPanel(capable, STRINGS, interaction));

        onEventDispatchThread(() -> {
            genericPanel.setSize(new Dimension(680, 420));
            capablePanel.setSize(new Dimension(680, 420));
            layoutRecursively(genericPanel);
            layoutRecursively(capablePanel);
            genericPanel.choiceList().refreshLoadPlan();
            capablePanel.choiceList().refreshLoadPlan();
        });
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            AbstractButton genericMove = findButton(genericPanel, "accountsMove");
            AbstractButton genericUpload = findButton(genericPanel, "accountsOnlineSkin");
            AbstractButton move = findButton(capablePanel, "accountsMove");
            AbstractButton upload = findButton(capablePanel, "accountsOnlineSkin");
            assertAll(
                    () -> assertFalse(genericMove.isVisible()),
                    () -> assertFalse(genericUpload.isVisible()),
                    () -> assertTrue(move.isVisible()),
                    () -> assertTrue(move.isEnabled()),
                    () -> assertTrue(upload.isVisible()),
                    () -> assertTrue(upload.isEnabled()),
                    () -> assertEquals(null, move.getText()),
                    () -> assertEquals(null, upload.getText()),
                    () -> assertTrue(move.getIcon() != null),
                    () -> assertTrue(upload.getIcon() != null),
                    () -> assertEquals(0, uploads.uploads.get()));

            String previousMoveTooltip = Objects.requireNonNull(move.getToolTipText());
            move.doClick();
            assertAll(
                    () -> assertEquals(List.of(false, true), portability.overwritePermissions),
                    () -> assertTrue(portability.portable),
                    () -> assertEquals(1, interaction.overwriteConfirmations.get()),
                    () -> assertFalse(previousMoveTooltip.equals(move.getToolTipText())));
            genericPanel.close();
            capablePanel.close();
        });
    }

    /// Creates deterministic account rows.
    ///
    /// @param count item count
    /// @return immutable ordered rows
    private static @Unmodifiable List<AccountListItem> items(int count) {
        List<AccountListItem> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new AccountListItem(
                    "account-" + index,
                    "Player " + index,
                    index % 2 == 0 ? "Microsoft" : "Offline",
                    "profile-" + index));
        }
        return List.copyOf(result);
    }

    /// Creates a snapshot with an exact source count.
    ///
    /// @param selectedIndex selected source index, or -1 for no selection
    /// @param itemCount exact item count
    /// @param revision content revision
    /// @return immutable snapshot
    private static AccountsSnapshot snapshot(int selectedIndex, int itemCount, long revision) {
        OptionalInt selected = selectedIndex < 0
                ? OptionalInt.empty()
                : OptionalInt.of(selectedIndex);
        return new AccountsSnapshot(selected, itemCount, revision);
    }

    /// Finds a named button in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching command button
    private static AbstractButton findButton(Container root, String name) {
        Component component = findComponent(root, name);
        if (component instanceof AbstractButton button) {
            return button;
        }
        throw new IllegalArgumentException("Named component is not a button: " + name);
    }

    /// Finds a named component in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching component
    private static Component findComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                try {
                    return findComponent(nested, name);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        throw new IllegalArgumentException("Missing component: " + name);
    }

    /// Runs a value-producing operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs an operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Recursively lays out a component hierarchy before measurement or off-screen painting.
    ///
    /// @param container hierarchy root
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    /// Collects all pixel colors painted into an image.
    ///
    /// @param image rendered account page
    /// @return mutable distinct-color set
    private static Set<Integer> distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Headless confirmation, clipboard, and failure recorder used by account-action tests.
    @NotNullByDefault
    private static final class RecordingAccountManagementInteraction implements AccountManagementInteraction {
        /// Whether the next and subsequent removal confirmations are accepted.
        private boolean allowRemoval = true;

        /// Number of destructive confirmations shown.
        private final AtomicInteger confirmations = new AtomicInteger();

        /// Number of backup-and-overwrite confirmations shown.
        private final AtomicInteger overwriteConfirmations = new AtomicInteger();

        /// Whether backup-and-overwrite confirmation is accepted.
        private boolean allowOverwrite;

        /// Exact copied strings.
        private final List<String> copiedText = new ArrayList<>();

        /// Presented failure messages.
        private final List<String> failures = new ArrayList<>();

        /// Records one confirmation and returns the configured decision.
        @Override
        public boolean confirmRemoval(Component owner, String title, String message) {
            EdtDispatcher.requireEventDispatchThread();
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(message, "message");
            confirmations.incrementAndGet();
            return allowRemoval;
        }

        /// Records one read-only backup-and-overwrite confirmation.
        @Override
        public boolean confirmReadOnlyOverwrite(Component owner) {
            EdtDispatcher.requireEventDispatchThread();
            Objects.requireNonNull(owner, "owner");
            overwriteConfirmations.incrementAndGet();
            return allowOverwrite;
        }

        /// Records one exact clipboard value.
        @Override
        public void copyText(String text) {
            EdtDispatcher.requireEventDispatchThread();
            copiedText.add(Objects.requireNonNull(text, "text"));
        }

        /// Records one terminal failure message.
        @Override
        public void showFailure(Component owner, String title, String message) {
            EdtDispatcher.requireEventDispatchThread();
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(title, "title");
            failures.add(Objects.requireNonNull(message, "message"));
        }
    }

    /// Minimal configured-server source used only to exercise account-page capability visibility.
    @NotNullByDefault
    private static final class FakeAuthlibServerStore implements AuthlibServerStore {
        /// Empty immutable source sufficient for a page-level command-availability check.
        private final AuthlibServerSnapshot snapshot = new AuthlibServerSnapshot(List.of());

        /// Returns the immutable empty configured-server snapshot.
        @Override
        public AuthlibServerSnapshot snapshot() {
            return snapshot;
        }

        /// Registers a no-op listener because this focused test does not publish server mutations.
        @Override
        public Subscription subscribe(
                ValueChangeListener<AuthlibServerSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Rejects endpoint discovery because this focused page test never opens the native dialog.
        @Override
        public PreparedAuthlibServer prepareServer(String endpoint) throws IOException {
            throw new UnsupportedOperationException("Not used by account page visibility test");
        }

        /// Rejects persistence because this focused page test never opens the native dialog.
        @Override
        public void addServer(PreparedAuthlibServer server, boolean allowReadOnlyOverwrite) {
            throw new UnsupportedOperationException("Not used by account page visibility test");
        }

        /// Rejects removal because this focused page test never opens the native dialog.
        @Override
        public void removeServer(String serverUrl, boolean allowReadOnlyOverwrite) {
            throw new UnsupportedOperationException("Not used by account page visibility test");
        }
    }

    /// Minimal offline-account skin source used only to exercise account-page eligibility.
    @NotNullByDefault
    private static final class FakeOfflineSkinStore implements OfflineSkinStore {
        /// Stable identifier treated as an actual offline account by this fake source.
        private final String offlineAccountId;

        /// Creates a fake source for one exact account row.
        ///
        /// @param offlineAccountId stable offline account identifier
        private FakeOfflineSkinStore(String offlineAccountId) {
            this.offlineAccountId = Objects.requireNonNull(offlineAccountId, "offlineAccountId");
        }

        /// Returns a writable default-skin state for the supported fake account only.
        @Override
        public Optional<OfflineSkinSnapshot> snapshot(String accountId) {
            Objects.requireNonNull(accountId, "accountId");
            return offlineAccountId.equals(accountId)
                    ? Optional.of(new OfflineSkinSnapshot(accountId, "Player 0", null, true, null))
                    : Optional.empty();
        }

        /// Rejects mutations because the focused account-page test never opens the native dialog.
        @Override
        public void setSkin(String accountId, @Nullable Skin skin) {
            throw new UnsupportedOperationException("Not used by account page eligibility test");
        }
    }

    /// In-memory portable/global store that can require explicit recovery consent.
    @NotNullByDefault
    private static final class FakeAccountPortabilityStore implements AccountPortabilityStore {
        /// Stable supported account identifier.
        private final String accountId;

        /// Whether the account is currently launcher-local.
        private boolean portable;

        /// Whether ordinary movement is writable.
        private final boolean writable;

        /// Overwrite permissions received by movement attempts.
        private final List<Boolean> overwritePermissions = new ArrayList<>();

        /// Creates a deterministic fake storage bridge.
        ///
        /// @param accountId supported stable account identifier
        /// @param portable initial storage location
        /// @param writable whether movement succeeds without recovery consent
        private FakeAccountPortabilityStore(String accountId, boolean portable, boolean writable) {
            this.accountId = Objects.requireNonNull(accountId, "accountId");
            this.portable = portable;
            this.writable = writable;
        }

        /// Returns state only for the supported fake account.
        @Override
        public Optional<AccountPortabilitySnapshot> portability(String requestedAccountId) {
            return accountId.equals(Objects.requireNonNull(requestedAccountId, "requestedAccountId"))
                    ? Optional.of(new AccountPortabilitySnapshot(accountId, portable, writable))
                    : Optional.empty();
        }

        /// Records consent, rejects an ordinary read-only attempt, and otherwise toggles storage.
        @Override
        public void move(String requestedAccountId, boolean allowReadOnlyOverwrite) {
            if (!accountId.equals(Objects.requireNonNull(requestedAccountId, "requestedAccountId"))) {
                throw new IllegalArgumentException("Unknown fake account");
            }
            overwritePermissions.add(allowReadOnlyOverwrite);
            if (!writable && !allowReadOnlyOverwrite) {
                throw new AccountStorageOverwriteRequiredException(accountId, "read-only fake storage");
            }
            portable = !portable;
        }
    }

    /// Online skin-upload capability recorder used to prove page opening is side-effect free.
    @NotNullByDefault
    private static final class FakeAccountSkinUploadStore implements AccountSkinUploadStore {
        /// Stable upload-capable account identifier.
        private final String accountId;

        /// Provider upload invocation count.
        private final AtomicInteger uploads = new AtomicInteger();

        /// Creates one exact upload-capable fake account.
        ///
        /// @param accountId supported stable account identifier
        private FakeAccountSkinUploadStore(String accountId) {
            this.accountId = Objects.requireNonNull(accountId, "accountId");
        }

        /// Reports capability only for the configured account.
        @Override
        public boolean canUpload(String requestedAccountId) {
            return accountId.equals(Objects.requireNonNull(requestedAccountId, "requestedAccountId"));
        }

        /// Records an explicit upload command.
        @Override
        public CompletionStage<Void> upload(String requestedAccountId, Path skinFile, boolean slim) {
            if (!canUpload(requestedAccountId)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown fake account"));
            }
            Objects.requireNonNull(skinFile, "skinFile");
            uploads.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    /// A captured viewport load whose completion can be controlled by a test.
    ///
    /// @param range requested source range
    /// @param itemSnapshot immutable source snapshot captured at request time
    /// @param future request completion
    @NotNullByDefault
    private record PendingLoad(
            IndexRange range,
            @Unmodifiable List<AccountListItem> itemSnapshot,
            CompletableFuture<ChoicePage<AccountListItem>> future) {
    }

    /// Thread-safe fake model supporting immediate and explicitly controlled viewport loads.
    @NotNullByDefault
    private static final class FakeAccountsModel implements AccountsModel {
        /// Latest immutable source rows.
        private volatile @Unmodifiable List<AccountListItem> items;

        /// Latest immutable page state.
        private final AtomicReference<AccountsSnapshot> current;

        /// Page-state transition publisher.
        private final ValueChangeSupport<AccountsSnapshot> changes = new ValueChangeSupport<>(this);

        /// Whether load requests complete immediately.
        private final boolean immediateLoads;

        /// Requested viewport ranges in invocation order.
        private final List<IndexRange> requestedRanges = new ArrayList<>();

        /// Controlled requests awaiting explicit completion.
        private final List<PendingLoad> pendingLoads = new ArrayList<>();

        /// Selected stable account identifiers.
        private final List<String> selectedIds = new ArrayList<>();

        /// Permanently removed stable account identifiers.
        private final List<String> removedIds = new ArrayList<>();

        /// Refreshed stable account identifiers.
        private final List<String> refreshedIds = new ArrayList<>();

        /// Controllable refresh completion.
        private CompletableFuture<Void> refreshCompletion = CompletableFuture.completedFuture(null);

        /// Whether removal requires explicit backup-and-overwrite consent.
        private boolean removalRequiresOverwrite;

        /// Optional configured-server persistence capability exposed to the tested page.
        private @Nullable AuthlibServerStore authlibServerStore;

        /// Optional offline-account skin persistence capability exposed to the tested page.
        private @Nullable OfflineSkinStore offlineSkinStore;

        /// Optional portable/global account persistence capability exposed to the tested page.
        private @Nullable AccountPortabilityStore accountPortabilityStore;

        /// Optional online skin upload capability exposed to the tested page.
        private @Nullable AccountSkinUploadStore accountSkinUploadStore;

        /// Add command count.
        private final AtomicInteger additions = new AtomicInteger();

        /// Creates a fake model.
        ///
        /// @param items initial immutable rows
        /// @param initialSnapshot initial page state
        /// @param immediateLoads whether viewport requests complete immediately
        private FakeAccountsModel(
                @Unmodifiable List<AccountListItem> items,
                AccountsSnapshot initialSnapshot,
                boolean immediateLoads) {
            this.items = List.copyOf(items);
            current = new AtomicReference<>(initialSnapshot);
            this.immediateLoads = immediateLoads;
        }

        /// Creates a source whose viewport loads complete immediately.
        ///
        /// @param items initial rows
        /// @param snapshot initial state
        /// @return immediate fake model
        private static FakeAccountsModel immediate(
                @Unmodifiable List<AccountListItem> items,
                AccountsSnapshot snapshot) {
            return new FakeAccountsModel(items, snapshot, true);
        }

        /// Creates a source whose viewport loads require explicit completion.
        ///
        /// @param items initial rows
        /// @param snapshot initial state
        /// @return controlled fake model
        private static FakeAccountsModel controlled(
                @Unmodifiable List<AccountListItem> items,
                AccountsSnapshot snapshot) {
            return new FakeAccountsModel(items, snapshot, false);
        }

        /// Returns the latest fake page state.
        @Override
        public AccountsSnapshot snapshot() {
            return current.get();
        }

        /// Registers a fake page-state listener.
        @Override
        public Subscription subscribe(ValueChangeListener<AccountsSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Returns the exact immutable source count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(items.size());
        }

        /// Captures and optionally completes a viewport request.
        @Override
        public synchronized CompletionStage<ChoicePage<AccountListItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            requestedRanges.add(desiredRange);
            @Unmodifiable List<AccountListItem> itemSnapshot = items;
            if (immediateLoads) {
                return CompletableFuture.completedFuture(page(desiredRange, itemSnapshot));
            }

            CompletableFuture<ChoicePage<AccountListItem>> future = new CompletableFuture<>();
            pendingLoads.add(new PendingLoad(desiredRange, itemSnapshot, future));
            return future;
        }

        /// Records one selected stable account identifier.
        @Override
        public synchronized void selectAccount(String accountId) {
            selectedIds.add(accountId);
        }

        /// Records one add command.
        @Override
        public void addAccount() {
            additions.incrementAndGet();
        }

        /// Records one permanent removal command.
        @Override
        public synchronized void removeAccount(String accountId, boolean allowReadOnlyOverwrite) {
            if (removalRequiresOverwrite && !allowReadOnlyOverwrite) {
                throw new AccountStorageOverwriteRequiredException(accountId, "read-only account storage");
            }
            removedIds.add(accountId);
        }

        /// Records one refresh command and returns its controllable completion.
        @Override
        public synchronized CompletionStage<Void> refreshAccount(String accountId) {
            refreshedIds.add(accountId);
            return refreshCompletion;
        }

        /// Returns this test source's optional configured-server persistence capability.
        @Override
        public Optional<AuthlibServerStore> authlibServerStore() {
            return Optional.ofNullable(authlibServerStore);
        }

        /// Returns this test source's optional offline-skin persistence capability.
        @Override
        public Optional<OfflineSkinStore> offlineSkinStore() {
            return Optional.ofNullable(offlineSkinStore);
        }

        /// Returns this test source's optional portable/global account bridge.
        @Override
        public Optional<AccountPortabilityStore> accountPortabilityStore() {
            return Optional.ofNullable(accountPortabilityStore);
        }

        /// Returns this test source's optional online skin-upload bridge.
        @Override
        public Optional<AccountSkinUploadStore> accountSkinUploadStore() {
            return Optional.ofNullable(accountSkinUploadStore);
        }

        /// Exposes configured-server management to the tested page.
        ///
        /// @param store configured-server persistence source
        private void setAuthlibServerStore(AuthlibServerStore store) {
            authlibServerStore = Objects.requireNonNull(store, "store");
        }

        /// Exposes offline-skin management to the tested page.
        ///
        /// @param store offline-skin persistence source
        private void setOfflineSkinStore(OfflineSkinStore store) {
            offlineSkinStore = Objects.requireNonNull(store, "store");
        }

        /// Exposes portable/global account movement to the tested page.
        ///
        /// @param store portable/global persistence source
        private void setAccountPortabilityStore(AccountPortabilityStore store) {
            accountPortabilityStore = Objects.requireNonNull(store, "store");
        }

        /// Exposes explicit online skin upload to the tested page.
        ///
        /// @param store online upload source
        private void setAccountSkinUploadStore(AccountSkinUploadStore store) {
            accountSkinUploadStore = Objects.requireNonNull(store, "store");
        }

        /// Replaces the completion returned by later refresh commands.
        ///
        /// @param completion controllable refresh completion
        private synchronized void setRefreshCompletion(CompletableFuture<Void> completion) {
            refreshCompletion = Objects.requireNonNull(completion, "completion");
        }

        /// Configures whether deletion requires confirmed storage recovery.
        ///
        /// @param required whether a normal removal attempt must signal read-only storage
        private synchronized void setRemovalRequiresOverwrite(boolean required) {
            removalRequiresOverwrite = required;
        }

        /// Returns the immutable removal-command history.
        ///
        /// @return removed stable account identifiers
        private synchronized @Unmodifiable List<String> removedIds() {
            return List.copyOf(removedIds);
        }

        /// Returns the immutable refresh-command history.
        ///
        /// @return refreshed stable account identifiers
        private synchronized @Unmodifiable List<String> refreshedIds() {
            return List.copyOf(refreshedIds);
        }

        /// Returns a snapshot of captured request ranges.
        ///
        /// @return immutable ranges in invocation order
        private synchronized @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(requestedRanges);
        }

        /// Returns a snapshot of selected account identifiers.
        ///
        /// @return immutable selected identifiers in command order
        private synchronized @Unmodifiable List<String> selectedIds() {
            return List.copyOf(selectedIds);
        }

        /// Completes all currently pending viewport loads from their captured source snapshots.
        private void completePendingLoads() {
            @Unmodifiable List<PendingLoad> loads;
            synchronized (this) {
                loads = List.copyOf(pendingLoads);
                pendingLoads.clear();
            }
            for (PendingLoad load : loads) {
                load.future().complete(page(load.range(), load.itemSnapshot()));
            }
        }

        /// Replaces indexed content before publishing its matching page state.
        ///
        /// @param replacement replacement immutable rows
        /// @param snapshot matching page state
        private void replaceItemsAndPublish(
                @Unmodifiable List<AccountListItem> replacement,
                AccountsSnapshot snapshot) {
            items = List.copyOf(replacement);
            AccountsSnapshot previous = current.getAndSet(snapshot);
            changes.fireChange(previous, snapshot);
        }

        /// Returns whether a panel listener remains registered.
        ///
        /// @return whether this fake has at least one subscriber
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }

        /// Creates one source-aligned exact page.
        ///
        /// @param desiredRange requested range
        /// @param itemSnapshot immutable source rows captured for the request
        /// @return exact choice page
        private static ChoicePage<AccountListItem> page(
                IndexRange desiredRange,
                @Unmodifiable List<AccountListItem> itemSnapshot) {
            IndexRange actualRange = desiredRange.clampToItemCount(itemSnapshot.size());
            List<AccountListItem> values = itemSnapshot.subList(
                    actualRange.startInclusive(),
                    actualRange.endExclusive());
            return new ChoicePage<>(
                    actualRange,
                    List.copyOf(values),
                    OptionalInt.of(itemSnapshot.size()),
                    actualRange.endExclusive() == itemSnapshot.size());
        }
    }
}
