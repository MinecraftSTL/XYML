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
package space.minecraftstl.xyml.ui.swing.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.UiDispatcher;
import space.minecraftstl.xyml.ui.launch.LaunchInteraction;
import space.minecraftstl.xyml.ui.launch.LaunchInteractionPrompt;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// Presents toolkit-neutral launch decisions using native Swing option dialogs.
///
/// Calls are safe from launch workers and from the Swing event-dispatch thread. Worker calls return an
/// incomplete stage immediately and queue presentation on the EDT; EDT calls present synchronously and
/// return an already-resolved stage. Headless environments deterministically use the prompt's close action.
@NotNullByDefault
public final class SwingLaunchInteraction implements LaunchInteraction {
    /// Preferred width for wrapped launch guidance.
    private static final int MESSAGE_WIDTH = 560;

    /// Preferred height cap before the message becomes scrollable.
    private static final int MESSAGE_HEIGHT = 260;

    /// Supplies the current application window, or null before one exists.
    private final Supplier<@Nullable Component> ownerSupplier;

    /// Dispatches all Swing access to the EDT.
    private final UiDispatcher uiDispatcher;

    /// Injectable native option-dialog boundary.
    private final DialogActions dialogActions;

    /// Reports whether no desktop UI can be presented.
    private final BooleanSupplier headless;

    /// Creates a production presenter whose owner may follow the application lifecycle.
    ///
    /// The supplier is evaluated only on the Swing EDT and may return null before the main window exists.
    ///
    /// @param ownerSupplier current native dialog owner supplier
    public SwingLaunchInteraction(Supplier<@Nullable Component> ownerSupplier) {
        this(
                ownerSupplier,
                SwingUiDispatcher.INSTANCE,
                new JOptionPaneDialogActions(),
                GraphicsEnvironment::isHeadless);
    }

    /// Creates a production presenter with one stable native owner.
    ///
    /// @param owner native dialog owner, or null before the main window exists
    /// @return presenter using the supplied stable owner
    public static SwingLaunchInteraction forOwner(@Nullable Component owner) {
        return new SwingLaunchInteraction(() -> owner);
    }

    /// Creates a presenter with deterministic threading, dialog, and headless boundaries.
    ///
    /// @param ownerSupplier current native dialog owner supplier
    /// @param uiDispatcher Swing-compatible UI dispatcher
    /// @param dialogActions native dialog boundary
    /// @param headless headless-environment probe
    SwingLaunchInteraction(
            Supplier<@Nullable Component> ownerSupplier,
            UiDispatcher uiDispatcher,
            DialogActions dialogActions,
            BooleanSupplier headless) {
        this.ownerSupplier = Objects.requireNonNull(ownerSupplier, "ownerSupplier");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.dialogActions = Objects.requireNonNull(dialogActions, "dialogActions");
        this.headless = Objects.requireNonNull(headless, "headless");
    }

    /// Presents one prompt on the EDT and maps native selection or close to a semantic action.
    ///
    /// @param prompt immutable localized prompt
    /// @return completion resolved with the selected action or failed with a presentation error
    @Override
    public CompletionStage<LaunchInteractionPrompt.Action> present(LaunchInteractionPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        CompletableFuture<LaunchInteractionPrompt.Action> completion = new CompletableFuture<>();
        try {
            if (headless.getAsBoolean()) {
                completion.complete(prompt.closeAction());
                return completion;
            }
            uiDispatcher.dispatchOrRun(() -> presentOnUiThread(prompt, completion));
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    /// Performs one native presentation after dispatch.
    ///
    /// @param prompt immutable localized prompt
    /// @param completion semantic result completion
    private void presentOnUiThread(
            LaunchInteractionPrompt prompt,
            CompletableFuture<LaunchInteractionPrompt.Action> completion) {
        if (completion.isDone()) {
            return;
        }
        try {
            List<LaunchInteractionPrompt.Option> options = prompt.options();
            Object @Unmodifiable [] labels = options.stream()
                    .map(LaunchInteractionPrompt.Option::label)
                    .toArray(Object[]::new);
            int defaultIndex = findOptionIndex(options, prompt.defaultAction());
            int selection = dialogActions.showOptionDialog(
                    ownerSupplier.get(),
                    createMessage(prompt.message()),
                    prompt.title(),
                    messageType(prompt.severity()),
                    labels,
                    labels[defaultIndex]);
            LaunchInteractionPrompt.Action action = selection >= 0 && selection < options.size()
                    ? options.get(selection).action()
                    : prompt.closeAction();
            completion.complete(action);
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
        }
    }

    /// Finds the stable ordered index for one validated action.
    ///
    /// @param options immutable prompt options
    /// @param action validated action present in the options
    /// @return zero-based option index
    private static int findOptionIndex(
            @Unmodifiable List<LaunchInteractionPrompt.Option> options,
            LaunchInteractionPrompt.Action action) {
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).action() == action) {
                return index;
            }
        }
        throw new IllegalArgumentException("Action is absent from validated prompt: " + action);
    }

    /// Creates a bounded, wrapped, selectable message component for long compatibility guidance.
    ///
    /// @param message localized prompt detail
    /// @return native message component suitable for a JOptionPane
    private static Component createMessage(String message) {
        JTextArea text = new JTextArea(message);
        text.setEditable(false);
        text.setFocusable(true);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder());
        text.setFont(UIManager.getFont("Label.font"));
        text.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(MESSAGE_WIDTH, preferredMessageHeight(message)));
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        return scroll;
    }

    /// Estimates a stable bounded height from explicit lines and wrapped text length.
    ///
    /// @param message localized prompt detail
    /// @return preferred height between a compact minimum and the scroll cap
    private static int preferredMessageHeight(String message) {
        int explicitLines = Math.max(1, message.split("\\R", -1).length);
        int wrappedLines = Math.max(explicitLines, (message.length() + 69) / 70);
        return Math.min(MESSAGE_HEIGHT, Math.max(72, wrappedLines * 20 + 12));
    }

    /// Maps toolkit-neutral prompt severity to a Swing message constant.
    ///
    /// @param severity toolkit-neutral severity
    /// @return one JOptionPane message constant
    static int messageType(LaunchInteractionPrompt.Severity severity) {
        return switch (Objects.requireNonNull(severity, "severity")) {
            case INFO -> JOptionPane.INFORMATION_MESSAGE;
            case WARNING -> JOptionPane.WARNING_MESSAGE;
            case ERROR -> JOptionPane.ERROR_MESSAGE;
            case QUESTION -> JOptionPane.QUESTION_MESSAGE;
        };
    }

    /// Injectable boundary around a native Swing option dialog.
    @NotNullByDefault
    interface DialogActions {
        /// Displays one modal decision.
        ///
        /// @param owner native owner, or null before the application window exists
        /// @param message bounded native message component
        /// @param title localized title
        /// @param messageType one JOptionPane message constant
        /// @param options immutable ordered labels
        /// @param initialValue initial focused label
        /// @return selected zero-based index or `JOptionPane.CLOSED_OPTION`
        int showOptionDialog(
                @Nullable Component owner,
                Component message,
                String title,
                int messageType,
                Object @Unmodifiable [] options,
                Object initialValue);
    }

    /// Production JOptionPane adapter.
    @NotNullByDefault
    private static final class JOptionPaneDialogActions implements DialogActions {
        /// Creates the stateless production adapter.
        private JOptionPaneDialogActions() {
        }

        /// Displays one native modal option dialog.
        ///
        /// @param owner native owner, or null before the application window exists
        /// @param message bounded native message component
        /// @param title localized title
        /// @param messageType one JOptionPane message constant
        /// @param options immutable ordered labels
        /// @param initialValue initial focused label
        /// @return selected zero-based index or `JOptionPane.CLOSED_OPTION`
        @Override
        public int showOptionDialog(
                @Nullable Component owner,
                Component message,
                String title,
                int messageType,
                Object @Unmodifiable [] options,
                Object initialValue) {
            return JOptionPane.showOptionDialog(
                    owner,
                    message,
                    title,
                    JOptionPane.DEFAULT_OPTION,
                    messageType,
                    null,
                    options,
                    initialValue);
        }
    }
}
