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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.platform.CommandBuilder;

import java.util.*;
import java.util.stream.Collectors;

/// Mutates launch arguments and libraries while producing a new immutable instance manifest.
@NotNullByDefault
public final class GameInstanceLibraryBuilder {
    /// Original manifest used as the immutable build base.
    private final GameInstanceManifest manifest;

    /// Legacy tokenized Minecraft arguments, or null when the modern argument model is active.
    private final @Nullable List<String> mcArgs;

    /// Mutable modern game arguments.
    private final List<Argument> game;

    /// Mutable modern JVM arguments.
    private final List<Argument> jvm;

    /// Mutable manifest libraries.
    private final List<Library> libraries;

    /// Whether the legacy Minecraft argument model is active.
    private final boolean useMcArgs;

    /// Whether callers modified JVM arguments.
    private boolean jvmChanged = false;

    /// Creates a builder initialized from one manifest.
    ///
    /// @param manifest immutable source manifest
    public GameInstanceLibraryBuilder(GameInstanceManifest manifest) {
        this.manifest = manifest;
        this.libraries = new ArrayList<>(manifest.getLibraries());
        this.mcArgs = Optional.ofNullable(manifest.minecraftArguments()).map(StringUtils::tokenize).map(ArrayList::new).orElse(null);
        this.game = Optional.ofNullable(manifest.arguments()).map(Arguments::game).map(ArrayList::new).orElseGet(ArrayList::new);
        this.jvm = new ArrayList<>(Optional.ofNullable(manifest.arguments()).map(Arguments::jvm).orElse(Arguments.DEFAULT_JVM_ARGUMENTS));
        this.useMcArgs = mcArgs != null;
    }

    /// Builds a manifest copy containing the current arguments and libraries.
    public GameInstanceManifest build() {
        GameInstanceManifest ret = manifest;
        if (useMcArgs) {
            // The official launcher will not parse the "arguments" property when it detects the presence of "mcArgs".
            // The "arguments" property with the "rule" is simply ignored here.
            this.mcArgs.addAll(this.game.stream().map(arg -> arg.toString(new HashMap<>(), new HashMap<>())).flatMap(Collection::stream).collect(Collectors.toList()));
            ret = ret.withArguments(null);

            // Since $ will be escaped in linux, and our maintain of minecraftArgument will not cause escaping,
            // so we regenerate the minecraftArgument without escaping.
            ret = ret.withMinecraftArguments(new CommandBuilder().addAllWithoutParsing(mcArgs).toString());
        } else {
            ret = ret.withArguments(Optional.ofNullable(ret.arguments())
                    .map(args -> args.withGame(game))
                    .map(args -> jvmChanged ? args.withJvm(jvm) : args).orElse(new Arguments(game, jvmChanged ? jvm : null)));
        }
        return ret.withLibraries(libraries);
    }

    /// Returns whether the selected argument model contains a tweak class.
    ///
    /// @param tweakClass fully qualified tweak class name
    public boolean hasTweakClass(String tweakClass) {
        return useMcArgs && mcArgs.contains(tweakClass) || game.stream().anyMatch(arg -> arg.toString().equals(tweakClass));
    }

    /// Removes every occurrence of a tweak class.
    ///
    /// @param target fully qualified tweak class name
    public void removeTweakClass(String target) {
        replaceTweakClass(target, null, false);
    }

    /// Replaces a tweak class in place without reordering its first occurrence.
    ///
    /// If the target is absent, the replacement is appended. Duplicate target entries are removed.
    ///
    /// @param target tweak class to replace
    /// @param replacement replacement tweak class
    public void replaceTweakClass(String target, String replacement) {
        replaceTweakClass(target, replacement, true);
    }

    /// Removes a tweak class and appends its replacement to the argument list.
    ///
    /// @param target tweak class to replace
    /// @param replacement replacement tweak class
    public void addTweakClass(String target, String replacement) {
        replaceTweakClass(target, replacement, false);
    }

    /// Replaces or removes a tweak class and eliminates duplicate target entries.
    ///
    /// @param target tweak class to replace
    /// @param replacement replacement tweak class, or null to remove it
    /// @param inPlace whether to preserve the first target position
    public void replaceTweakClass(String target, @Nullable String replacement, boolean inPlace) {
        replaceTweakClass(target, replacement, inPlace, false);
    }

    /// Replaces or removes a tweak class with explicit insertion placement.
    ///
    /// @param target tweak class to replace
    /// @param replacement replacement tweak class, or null to remove it
    /// @param inPlace whether to preserve the first target position
    /// @param reserve whether an appended replacement is inserted at the beginning
    public void replaceTweakClass(String target, @Nullable String replacement, boolean inPlace, boolean reserve) {
        if (replacement == null && inPlace)
            throw new IllegalArgumentException("Replacement cannot be null in replace mode");

        boolean replaced = false;
        if (useMcArgs) {
            for (int i = 0; i + 1 < mcArgs.size(); ++i) {
                String arg0Str = mcArgs.get(i);
                String arg1Str = mcArgs.get(i + 1);
                if (arg0Str.equals("--tweakClass") && arg1Str.equals(target)) {
                    if (!replaced && inPlace) {
                        // for the first one, we replace the tweak class only.
                        mcArgs.set(i + 1, replacement);
                        replaced = true;
                    } else {
                        // otherwise, we remove the duplicate tweak classes.
                        mcArgs.remove(i);
                        mcArgs.remove(i);
                        --i;
                    }
                }
            }
        }

        for (int i = 0; i + 1 < game.size(); ++i) {
            Argument arg0 = game.get(i);
            Argument arg1 = game.get(i + 1);
            if (arg0 instanceof StringArgument && arg1 instanceof StringArgument) {
                // We need to preserve the tokens
                String arg0Str = arg0.toString();
                String arg1Str = arg1.toString();
                if (arg0Str.equals("--tweakClass") && arg1Str.equals(target)) {
                    if (!replaced && inPlace) {
                        // for the first one, we replace the tweak class only.
                        game.set(i + 1, new StringArgument(replacement));
                        replaced = true;
                    } else {
                        // otherwise, we remove the duplicate tweak classes.
                        game.remove(i);
                        game.remove(i);
                        --i;
                    }
                }
            }
        }

        // if the tweak class does not exist, add a new one to the end.
        if (!replaced && replacement != null) {
            if (reserve) {
                if (useMcArgs) {
                    mcArgs.add(0, replacement);
                    mcArgs.add(0, "--tweakClass");
                } else {
                    game.add(0, new StringArgument(replacement));
                    game.add(0, new StringArgument("--tweakClass"));
                }
            } else {
                game.add(new StringArgument("--tweakClass"));
                game.add(new StringArgument(replacement));
            }
        }
    }

    /// Returns the mutable JVM argument list and marks JVM arguments as changed.
    public List<Argument> getMutableJvmArguments() {
        jvmChanged = true;
        return jvm;
    }

    /// Appends literal modern game arguments.
    ///
    /// @param args literal arguments
    public void addGameArgument(String... args) {
        for (String arg : args)
            game.add(new StringArgument(arg));
    }

    /// Appends literal modern JVM arguments.
    ///
    /// @param args literal arguments
    public void addJvmArgument(String... args) {
        jvmChanged = true;
        for (String arg : args)
            jvm.add(new StringArgument(arg));
    }

    /// Appends a library to the manifest being built.
    ///
    /// @param library library descriptor
    public void addLibrary(Library library) {
        libraries.add(library);
    }
}
