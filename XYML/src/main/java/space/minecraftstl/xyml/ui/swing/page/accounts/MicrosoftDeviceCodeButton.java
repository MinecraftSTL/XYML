// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;

import javax.swing.JButton;
import java.awt.Font;
import java.awt.Insets;
import java.util.Objects;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents a Microsoft device code as a large, keyboard-accessible copy action.
///
/// The family follows the configured game-log font while weight and size retain the old UI's fixed bold 22-pixel
/// treatment. Launcher-wide font changes must not replace this independently configured family.
@NotNullByDefault
final class MicrosoftDeviceCodeButton extends JButton {
    /// Device-code size retained from the old UI.
    private static final float DEVICE_CODE_FONT_SIZE = 22.0F;

    /// Clipboard boundary invoked by button activation.
    private final Consumer<String> copyAction;

    /// Currently displayed device code, or `null` while hidden.
    private @Nullable String code;

    /// Creates an initially hidden device-code copy action.
    ///
    /// @param logFont configured game-log font whose family is reused
    /// @param copyAction clipboard boundary
    MicrosoftDeviceCodeButton(Font logFont, Consumer<String> copyAction) {
        this.copyAction = Objects.requireNonNull(copyAction, "copyAction");
        Font validatedFont = Objects.requireNonNull(logFont, "logFont");
        setName("microsoftDeviceCode");
        setFont(validatedFont.deriveFont(Font.BOLD, DEVICE_CODE_FONT_SIZE));
        setMargin(new Insets(6, 14, 6, 14));
        setToolTipText(i18n("menu.copy"));
        getAccessibleContext().setAccessibleDescription(i18n("menu.copy"));
        SwingThemeManager.preserveExplicitFontFamily(this);
        addActionListener(event -> copyCurrentCode());
        clearCode();
    }

    /// Shows one non-blank device code.
    ///
    /// @param code device code to display and copy
    void showCode(String code) {
        EdtDispatcher.requireEventDispatchThread();
        String validatedCode = Objects.requireNonNull(code, "code").trim();
        if (validatedCode.isEmpty()) {
            throw new IllegalArgumentException("Device code must not be blank");
        }
        this.code = validatedCode;
        setText(validatedCode);
        getAccessibleContext().setAccessibleName(
                i18n("account.methods.microsoft.methods.device") + ": " + validatedCode);
        setVisible(true);
    }

    /// Clears and hides any previous device code.
    void clearCode() {
        EdtDispatcher.requireEventDispatchThread();
        code = null;
        setText("");
        getAccessibleContext().setAccessibleName(i18n("account.methods.microsoft.methods.device"));
        setVisible(false);
    }

    /// Returns the currently displayed device code for focused tests.
    ///
    /// @return current code, or `null` while hidden
    @Nullable String code() {
        EdtDispatcher.requireEventDispatchThread();
        return code;
    }

    /// Copies the current code when the button remains active.
    private void copyCurrentCode() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable String currentCode = code;
        if (currentCode != null) {
            copyAction.accept(currentCode);
        }
    }
}
