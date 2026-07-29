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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.countly.CrashReport;

import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies crash headline selection without opening a desktop window.
@NotNullByDefault
class SwingCrashReportWindowTest {
    /// Gives an internal JVM failure precedence over the outdated-version hint.
    @Test
    void internalErrorTakesPrecedence() {
        CrashReport report = new CrashReport(Thread.currentThread(), new InternalError("broken VM"));

        assertEquals(
                i18n("launcher.crash.java_internal_error"),
                SwingCrashReportWindow.headline(report, true));
    }

    /// Uses the update hint for an ordinary failure when the launcher is outdated.
    @Test
    void outdatedLauncherUsesUpdateHint() {
        CrashReport report = new CrashReport(Thread.currentThread(), new IllegalStateException("failure"));

        assertEquals(
                i18n("launcher.crash.xyml_outdated"),
                SwingCrashReportWindow.headline(report, true));
    }

    /// Uses the general crash message when no more specific condition applies.
    @Test
    void ordinaryFailureUsesGeneralHeadline() {
        CrashReport report = new CrashReport(Thread.currentThread(), new IllegalStateException("failure"));

        assertEquals(
                i18n("launcher.crash"),
                SwingCrashReportWindow.headline(report, false));
    }

    /// Builds content from an injected native update result without consulting a global checker.
    @Test
    void contentUsesInjectedUpdateAvailability() throws Exception {
        CrashReport report = new CrashReport(Thread.currentThread(), new IllegalStateException("failure"));
        CompletableFuture<JPanel> content = new CompletableFuture<>();

        javax.swing.SwingUtilities.invokeAndWait(
                () -> content.complete(SwingCrashReportWindow.createContent(report, true)));

        JPanel rendered = content.join();
        JTextArea headline = (JTextArea) Objects.requireNonNull(
                ((BorderLayout) rendered.getLayout()).getLayoutComponent(BorderLayout.NORTH));
        assertEquals(i18n("launcher.crash.xyml_outdated"), headline.getText());
    }
}
