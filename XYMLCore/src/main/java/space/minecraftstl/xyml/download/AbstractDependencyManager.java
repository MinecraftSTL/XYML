// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;

/// Supplies common download-provider and version-list behavior for dependency managers.
///
/// @author huangyuhui
@NotNullByDefault
public abstract class AbstractDependencyManager implements DependencyManager {

    /// Returns the provider used to resolve remote Minecraft and loader versions.
    public abstract DownloadProvider getDownloadProvider();

    /// Returns the cache repository used by this dependency manager.
    @Override
    public abstract DefaultCacheRepository getCacheRepository();

}
