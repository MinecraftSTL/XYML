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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.io.Serial;
import java.util.Objects;

/// Shared construction helpers for the Swing NBT editor's compact command surface.
@NotNullByDefault
final class NBTEditorSwingSupport {
    /// Prevents utility-class construction.
    private NBTEditorSwingSupport() {
    }

    /// Configures one familiar-symbol icon command.
    static void configureIconButton(
            JButton button,
            String name,
            String iconResource,
            String tooltip,
            Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(Objects.requireNonNull(name, "name"));
        target.setIcon(themeIcon(iconResource));
        configureToolButton(target, tooltip, action);
    }

    /// Configures one fixed-size text-symbol tool button.
    static void configureSymbolButton(
            JButton button,
            String name,
            String symbol,
            String tooltip,
            Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(Objects.requireNonNull(name, "name"));
        target.setText(Objects.requireNonNull(symbol, "symbol"));
        configureToolButton(target, tooltip, action);
    }

    /// Creates one context-menu command.
    static JMenuItem menuItem(String text, @Nullable String iconResource, Runnable action) {
        JMenuItem item = new JMenuItem(Objects.requireNonNull(text, "text"));
        if (iconResource != null) {
            item.setIcon(themeIcon(iconResource));
        }
        item.addActionListener(event -> Objects.requireNonNull(action, "action").run());
        return item;
    }

    /// Installs one keyboard command on a component's focused ancestry.
    static void bind(JComponent component, String key, KeyStroke stroke, Runnable action) {
        JComponent target = Objects.requireNonNull(component, "component");
        String actionKey = Objects.requireNonNull(key, "key");
        target.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(Objects.requireNonNull(stroke, "stroke"), actionKey);
        target.getActionMap().put(actionKey, new RunnableAction(action));
    }

    /// Creates one detail label associated with its editor component.
    static JLabel detailLabel(String text, JComponent component) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
        label.setLabelFor(Objects.requireNonNull(component, "component"));
        return label;
    }

    /// Creates one stable read-only detail field.
    static JTextField readOnlyField(String name) {
        JTextField field = new JTextField();
        field.setName(Objects.requireNonNull(name, "name"));
        field.setEditable(false);
        return field;
    }

    /// Creates one document listener that routes every change kind to the same EDT callback.
    static DocumentListener documentChanges(Runnable callback) {
        return new SharedDocumentListener(Objects.requireNonNull(callback, "callback"));
    }

    /// Applies accessible behavior shared by toolbar buttons.
    private static void configureToolButton(JButton button, String tooltip, Runnable action) {
        String text = Objects.requireNonNull(tooltip, "tooltip");
        button.setToolTipText(text);
        button.getAccessibleContext().setAccessibleName(text);
        button.getAccessibleContext().setAccessibleDescription(text);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Creates one bundled SVG icon that follows component foreground.
    private static FlatSVGIcon themeIcon(String iconResource) {
        FlatSVGIcon icon = new FlatSVGIcon(Objects.requireNonNull(iconResource, "iconResource"), 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(NBTEditorSwingSupport::resolveIconColor));
        return icon;
    }

    /// Resolves icon color from its owner and current theme.
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color authored = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        if (foreground != null) {
            return foreground;
        }
        @Nullable Color themeForeground = UIManager.getColor("Button.foreground");
        return themeForeground == null ? authored : themeForeground;
    }

    /// Document listener which collapses Swing's three change methods into one callback.
    @NotNullByDefault
    private static final class SharedDocumentListener implements DocumentListener {
        /// Callback executed for every document change.
        private final Runnable callback;

        /// Creates one shared callback listener.
        private SharedDocumentListener(Runnable callback) {
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        /// Handles inserted text.
        @Override
        public void insertUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            callback.run();
        }

        /// Handles removed text.
        @Override
        public void removeUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            callback.run();
        }

        /// Handles styled-attribute changes.
        @Override
        public void changedUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            callback.run();
        }
    }

    /// Swing action that delegates to one prevalidated command.
    @NotNullByDefault
    private static final class RunnableAction extends AbstractAction {
        /// Serialization identifier for the Swing action superclass.
        @Serial
        private static final long serialVersionUID = 1L;

        /// Command executed on the EDT.
        private final Runnable command;

        /// Creates one action.
        private RunnableAction(Runnable command) {
            this.command = Objects.requireNonNull(command, "command");
        }

        /// Runs the command.
        @Override
        public void actionPerformed(ActionEvent event) {
            Objects.requireNonNull(event, "event");
            command.run();
        }
    }
}
