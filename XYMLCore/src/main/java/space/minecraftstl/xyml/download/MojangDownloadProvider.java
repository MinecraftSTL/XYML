/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download;

import space.minecraftstl.xyml.download.cleanroom.CleanroomVersionList;
import space.minecraftstl.xyml.download.fabric.FabricAPIVersionList;
import space.minecraftstl.xyml.download.fabric.FabricVersionList;
import space.minecraftstl.xyml.download.forge.ForgeVersionList;
import space.minecraftstl.xyml.download.game.GameVersionList;
import space.minecraftstl.xyml.download.legacyfabric.LegacyFabricAPIVersionList;
import space.minecraftstl.xyml.download.legacyfabric.LegacyFabricVersionList;
import space.minecraftstl.xyml.download.liteloader.LiteLoaderVersionList;
import space.minecraftstl.xyml.download.neoforge.NeoForgeOfficialVersionList;
import space.minecraftstl.xyml.download.optifine.OptiFineBMCLVersionList;
import space.minecraftstl.xyml.download.quilt.QuiltAPIVersionList;
import space.minecraftstl.xyml.download.quilt.QuiltVersionList;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import java.net.URI;
import java.util.List;

/**
 * @author huangyuhui
 * @see <a href="http://wiki.vg">http://wiki.vg</a>
 */
public class MojangDownloadProvider implements DownloadProvider {
    private final GameVersionList game;
    private final FabricVersionList fabric;
    private final FabricAPIVersionList fabricApi;
    private final ForgeVersionList forge;
    private final NeoForgeOfficialVersionList neoforge;
    private final CleanroomVersionList cleanroom;
    private final LiteLoaderVersionList liteLoader;
    private final OptiFineBMCLVersionList optifine;
    private final QuiltVersionList quilt;
    private final QuiltAPIVersionList quiltApi;
    private final LegacyFabricVersionList legacyFabric;
    private final LegacyFabricAPIVersionList legacyFabricApi;

    public MojangDownloadProvider() {
        // If there is no official download channel available, fallback to BMCLAPI.
        String apiRoot = "https://bmclapi2.bangbang93.com";

        this.game = new GameVersionList(this);
        this.fabric = new FabricVersionList(this);
        this.fabricApi = new FabricAPIVersionList(this);
        this.forge = new ForgeVersionList(this);
        this.neoforge = new NeoForgeOfficialVersionList(this);
        this.cleanroom = new CleanroomVersionList(this);
        this.liteLoader = new LiteLoaderVersionList(this);
        this.optifine = new OptiFineBMCLVersionList(apiRoot);
        this.quilt = new QuiltVersionList(this);
        this.quiltApi = new QuiltAPIVersionList(this);
        this.legacyFabric = new LegacyFabricVersionList(this);
        this.legacyFabricApi = new LegacyFabricAPIVersionList(this);
    }

    @Override
    public List<URI> getVersionListURLs() {
        return List.of(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest.json"));
    }

    @Override
    public List<URI> getAssetObjectCandidates(String assetObjectLocation) {
        return List.of(NetworkUtils.toURI("https://resources.download.minecraft.net/" + assetObjectLocation));
    }

    @Override
    public VersionList<?> getVersionListById(String id) {
        return switch (id) {
            case "game" -> game;
            case "fabric" -> fabric;
            case "fabric-api" -> fabricApi;
            case "forge" -> forge;
            case "cleanroom" -> cleanroom;
            case "neoforge" -> neoforge;
            case "liteloader" -> liteLoader;
            case "optifine" -> optifine;
            case "quilt" -> quilt;
            case "quilt-api" -> quiltApi;
            case "legacyfabric" -> legacyFabric;
            case "legacyfabric-api" -> legacyFabricApi;
            default -> throw new IllegalArgumentException("Unrecognized version list id: " + id);
        };
    }

    @Override
    public String injectURL(String baseURL) {
        return baseURL;
    }

    @Override
    public int getConcurrency() {
        return 6;
    }
}
