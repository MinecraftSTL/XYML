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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Exercises explicit online-skin selection and upload boundaries through native Swing controls.
@NotNullByDefault
public final class SwingOnlineSkinUploadDialogTest {
    /// Maximum time allowed for validation work and its EDT publication.
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

    /// Isolated selected-image directory.
    @TempDir
    private Path temporaryDirectory;

    /// Dialogs created by the current test and disposed after every outcome.
    private final List<SwingOnlineSkinUploadDialog> dialogs = new CopyOnWriteArrayList<>();

    /// Releases native peers even when an assertion interrupts a test flow.
    @AfterEach
    public void disposeDialogs() {
        EdtDispatcher.executeAndWait(() -> dialogs.forEach(SwingOnlineSkinUploadDialog::dispose));
        dialogs.clear();
    }

    /// Construction and one modal open-close cycle do not validate a file or invoke upload.
    @Test
    public void opensWithoutStartingValidationOrUpload() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native JDialog requires a graphical environment");
        AtomicInteger workerCalls = new AtomicInteger();
        AtomicInteger uploadCalls = new AtomicInteger();
        Executor worker = command -> {
            workerCalls.incrementAndGet();
            command.run();
        };
        SwingOnlineSkinUploadDialog dialog = createDialog(
                (path, slim) -> {
                    uploadCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                worker);

        runOnEdt(() -> {
            AbstractButton cancel = findButton(dialog.getContentPane(), "onlineSkinCancel");
            SwingUtilities.invokeLater(cancel::doClick);
            dialog.open();
        });

        assertAll(
                () -> assertEquals(0, workerCalls.get()),
                () -> assertEquals(0, uploadCalls.get()));
    }

    /// Bundled wide and slim PNGs are detected off the EDT and upload only after button activation.
    @Test
    public void detectsBothModelsAndUploadsOnlyAfterExplicitClick() throws IOException {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native JDialog requires a graphical environment");
        Path wide = copyBundledSkin("/assets/img/skin/wide/steve.png", "wide.png");
        Path slim = copyBundledSkin("/assets/img/skin/slim/alex.png", "slim.png");
        ExecutorService workerPool = newValidationExecutor();
        AtomicInteger workerCalls = new AtomicInteger();
        AtomicBoolean workerTouchedEdt = new AtomicBoolean();
        Executor worker = command -> workerPool.execute(() -> {
            workerCalls.incrementAndGet();
            workerTouchedEdt.compareAndSet(false, SwingUtilities.isEventDispatchThread());
            command.run();
        });
        List<Path> uploadedFiles = new CopyOnWriteArrayList<>();
        List<Boolean> uploadedModels = new CopyOnWriteArrayList<>();

        try {
            validateAndUpload(wide, false, worker, uploadedFiles, uploadedModels);
            validateAndUpload(slim, true, worker, uploadedFiles, uploadedModels);

            assertAll(
                    () -> assertEquals(2, workerCalls.get()),
                    () -> assertFalse(workerTouchedEdt.get()),
                    () -> assertEquals(List.of(
                            wide.toAbsolutePath().normalize(),
                            slim.toAbsolutePath().normalize()), uploadedFiles),
                    () -> assertEquals(List.of(false, true), uploadedModels));
        } finally {
            workerPool.shutdownNow();
            awaitExecutorTermination(workerPool);
        }
    }

    /// A decodable-looking filename with invalid bytes never enables or invokes Upload.
    @Test
    public void rejectsInvalidPngBeforeUpload() throws IOException {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native JDialog requires a graphical environment");
        Path invalid = temporaryDirectory.resolve("invalid.png");
        Files.writeString(invalid, "not a PNG image");
        ExecutorService workerPool = newValidationExecutor();
        AtomicInteger uploadCalls = new AtomicInteger();
        AtomicBoolean workerTouchedEdt = new AtomicBoolean();
        Executor worker = command -> workerPool.execute(() -> {
            workerTouchedEdt.set(SwingUtilities.isEventDispatchThread());
            command.run();
        });
        SwingOnlineSkinUploadDialog dialog = createDialog(
                (path, slim) -> {
                    uploadCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                worker);
        AbstractButton upload = findButtonOnEdt(dialog, "onlineSkinUpload");
        JLabel status = findLabelOnEdt(dialog, "onlineSkinStatus");

        try {
            runOnEdt(() -> dialog.validateSelection(invalid));
            awaitEdt(() -> Objects.equals(status.getText(), i18n("account.skin.invalid_skin")));

            runOnEdt(upload::doClick);

            assertAll(
                    () -> assertFalse(onEdt(upload::isEnabled)),
                    () -> assertEquals(i18n("account.skin.invalid_skin"), onEdt(status::getText)),
                    () -> assertEquals(0, uploadCalls.get()),
                    () -> assertFalse(workerTouchedEdt.get()));
        } finally {
            runOnEdt(dialog::close);
            workerPool.shutdownNow();
            awaitExecutorTermination(workerPool);
        }
    }

    /// Validates one fixture, verifies its model, and completes one explicit asynchronous upload.
    ///
    /// @param skinFile selected PNG fixture
    /// @param expectedSlim expected detected model
    /// @param worker validation executor
    /// @param uploadedFiles shared upload-path observations
    /// @param uploadedModels shared upload-model observations
    private void validateAndUpload(
            Path skinFile,
            boolean expectedSlim,
            Executor worker,
            List<Path> uploadedFiles,
            List<Boolean> uploadedModels) {
        int uploadsBeforeSelection = uploadedFiles.size();
        CompletableFuture<Void> uploadCompletion = new CompletableFuture<>();
        SwingOnlineSkinUploadDialog dialog = createDialog(
                (path, slim) -> {
                    uploadedFiles.add(path);
                    uploadedModels.add(slim);
                    return uploadCompletion;
                },
                worker);
        AbstractButton upload = findButtonOnEdt(dialog, "onlineSkinUpload");
        AbstractButton cancel = findButtonOnEdt(dialog, "onlineSkinCancel");
        JLabel model = findLabelOnEdt(dialog, "onlineSkinModel");
        JLabel status = findLabelOnEdt(dialog, "onlineSkinStatus");

        runOnEdt(() -> dialog.validateSelection(skinFile));
        assertAll(
                () -> assertFalse(onEdt(upload::isEnabled)),
                () -> assertEquals(uploadsBeforeSelection, uploadedFiles.size()));
        awaitEdt(upload::isEnabled);

        assertAll(
                () -> assertEquals(i18n(expectedSlim
                        ? "account.skin.model.slim"
                        : "account.skin.model.default"), onEdt(model::getText)),
                () -> assertEquals(i18n("account.skin.upload.ready"), onEdt(status::getText)),
                () -> assertEquals(uploadsBeforeSelection, uploadedFiles.size()));

        runOnEdt(upload::doClick);
        assertAll(
                () -> assertEquals(uploadsBeforeSelection + 1, uploadedFiles.size()),
                () -> assertFalse(onEdt(upload::isEnabled)),
                () -> assertFalse(onEdt(cancel::isEnabled)),
                () -> assertEquals(i18n("account.skin.upload.uploading"), onEdt(status::getText)));

        AtomicBoolean completionPublishedOnEdt = new AtomicBoolean();
        PropertyChangeListener listener = event -> {
            if ("text".equals(event.getPropertyName())
                    && Objects.equals(event.getNewValue(), i18n("account.skin.upload.success"))) {
                completionPublishedOnEdt.set(SwingUtilities.isEventDispatchThread());
            }
        };
        runOnEdt(() -> status.addPropertyChangeListener(listener));
        uploadCompletion.complete(null);
        awaitEdt(() -> Objects.equals(status.getText(), i18n("account.skin.upload.success")));
        assertTrue(completionPublishedOnEdt.get());
    }

    /// Creates and tracks one dialog on the EDT.
    ///
    /// @param uploadCommand recording upload command
    /// @param worker injected local-image worker
    /// @return initialized native dialog
    private SwingOnlineSkinUploadDialog createDialog(
            AccountSkinUploadCommand uploadCommand,
            Executor worker) {
        SwingOnlineSkinUploadDialog dialog = onEdt(() -> new SwingOnlineSkinUploadDialog(
                null,
                "Test Player",
                uploadCommand,
                worker));
        dialogs.add(dialog);
        return dialog;
    }

    /// Copies one real bundled skin to an explicitly selected temporary PNG.
    ///
    /// @param resourcePath absolute classpath resource
    /// @param fileName temporary filename
    /// @return copied image path
    /// @throws IOException when resource copying fails
    private Path copyBundledSkin(String resourcePath, String fileName) throws IOException {
        @Nullable InputStream resource = getClass().getResourceAsStream(resourcePath);
        try (InputStream input = Objects.requireNonNull(resource, "Missing skin resource " + resourcePath)) {
            Path destination = temporaryDirectory.resolve(fileName);
            Files.copy(input, destination);
            return destination;
        }
    }

    /// Creates a daemon validation worker so a failed assertion cannot retain the test JVM.
    ///
    /// @return single-threaded validation executor
    private static ExecutorService newValidationExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "online-skin-test-worker");
            worker.setDaemon(true);
            return worker;
        });
    }

    /// Waits briefly for one already-stopped executor to release its worker.
    ///
    /// @param executor executor being shut down
    private static void awaitExecutorTermination(ExecutorService executor) {
        try {
            assertTrue(executor.awaitTermination(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while stopping validation worker", interrupted);
        }
    }

    /// Polls an EDT-owned condition until it succeeds or the asynchronous timeout elapses.
    ///
    /// @param condition condition evaluated only on the EDT
    private static void awaitEdt(BooleanSupplier condition) {
        long deadline = System.nanoTime() + ASYNC_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean)) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Swing state", interrupted);
            }
        }
        assertTrue(onEdt(condition::getAsBoolean), "Timed out waiting for EDT state");
    }

    /// Finds a named dialog button on the EDT.
    ///
    /// @param dialog hierarchy root
    /// @param name stable component name
    /// @return matching button
    private static AbstractButton findButtonOnEdt(SwingOnlineSkinUploadDialog dialog, String name) {
        return onEdt(() -> findButton(dialog.getContentPane(), name));
    }

    /// Finds a named dialog label on the EDT.
    ///
    /// @param dialog hierarchy root
    /// @param name stable component name
    /// @return matching label
    private static JLabel findLabelOnEdt(SwingOnlineSkinUploadDialog dialog, String name) {
        return onEdt(() -> {
            Component component = findComponent(dialog.getContentPane(), name);
            if (component instanceof JLabel label) {
                return label;
            }
            throw new IllegalArgumentException("Named component is not a label: " + name);
        });
    }

    /// Finds a named button in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching button
    private static AbstractButton findButton(Container root, String name) {
        Component component = findComponent(root, name);
        if (component instanceof AbstractButton button) {
            return button;
        }
        throw new IllegalArgumentException("Named component is not a button: " + name);
    }

    /// Finds one named component recursively without reflecting into implementation fields.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching component
    private static Component findComponent(Container root, String name) {
        List<Container> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Container current = pending.remove(pending.size() - 1);
            for (Component child : current.getComponents()) {
                if (Objects.equals(name, child.getName())) {
                    return child;
                }
                if (child instanceof Container nested) {
                    pending.add(nested);
                }
            }
        }
        throw new IllegalArgumentException("Missing component: " + name);
    }

    /// Runs an action synchronously on the EDT.
    ///
    /// @param action UI action
    private static void runOnEdt(Runnable action) {
        EdtDispatcher.executeAndWait(action);
    }

    /// Runs a value-producing operation synchronously on the EDT.
    ///
    /// @param operation UI operation
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEdt(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        EdtDispatcher.executeAndWait(() -> result.complete(operation.get()));
        return result.join();
    }
}
