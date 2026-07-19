/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.ui.account;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Tooltip;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorAccount;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.game.TexturesLoader;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.ui.FXUtils;
import space.minecraftstl.xyml.ui.construct.AdvancedListItem;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.javafx.BindingMapping;

import static javafx.beans.binding.Bindings.createStringBinding;
import static space.minecraftstl.xyml.setting.Accounts.getAccountFactory;
import static space.minecraftstl.xyml.setting.Accounts.getLocalizedLoginTypeName;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

public class AccountAdvancedListItem extends AdvancedListItem {
    private final Tooltip tooltip;
    private final Canvas canvas;
    private boolean tooltipInstalled;

    private final ObjectProperty<Account> account = new SimpleObjectProperty<Account>() {

        @Override
        protected void invalidated() {
            Account account = get();
            if (account == null) {
                titleProperty().unbind();
                subtitleProperty().unbind();
                setTitle(i18n("account.missing"));
                setSubtitle(i18n("account.missing.add"));
                tooltip.setText(i18n("account.create"));
                installTooltip();

                TexturesLoader.unbindAvatar(canvas);
                TexturesLoader.drawAvatar(canvas, TexturesLoader.getDefaultSkinImage());

            } else {
                titleProperty().bind(createStringBinding(() -> {
                    String profileName = account.getProfileName();
                    return StringUtils.isBlank(profileName) ? account.getProfileID().toString() : profileName;
                }, account));
                subtitleProperty().bind(accountSubtitle(account));
                uninstallTooltip();
                TexturesLoader.bindAvatar(canvas, account);
            }
        }
    };

    public AccountAdvancedListItem() {
        this(null);
    }

    public AccountAdvancedListItem(Account account) {
        tooltip = new Tooltip();

        canvas = new Canvas(32, 32);
        canvas.setMouseTransparent(true);
        AdvancedListItem.setAlignment(canvas, Pos.CENTER);

        setLeftGraphic(canvas);

        if (account != null) {
            this.accountProperty().set(account);
        } else {
            FXUtils.onScroll(this, Accounts.getAccounts(),
                    accounts -> accounts.indexOf(accountProperty().get()),
                    Accounts::setSelectedAccount);
        }
    }

    public ObjectProperty<Account> accountProperty() {
        return account;
    }

    private static ObservableValue<String> accountSubtitle(Account account) {
        if (account instanceof AuthlibInjectorAccount) {
            return BindingMapping.of(((AuthlibInjectorAccount) account).getServer(), AuthlibInjectorServer::getName);
        } else {
            return createStringBinding(() -> getLocalizedLoginTypeName(getAccountFactory(account)));
        }
    }

    private void installTooltip() {
        if (!tooltipInstalled) {
            FXUtils.installFastTooltip(this, tooltip);
            tooltipInstalled = true;
        }
    }

    private void uninstallTooltip() {
        if (tooltipInstalled) {
            Tooltip.uninstall(this, tooltip);
            tooltipInstalled = false;
        }
    }

}
