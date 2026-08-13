<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=TITLE -->
<div align="center">
    <img src="/XYML/src/main/resources/assets/img/icon@8x.png" alt="XYML Logo" width="64"/>
</div>

<h1 align="center">xOyz Minecraft Launcher</h1>
<!-- #END BLOCK -->

<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=BADGES -->
<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-repo-blue?style=flat-square&logo=github)](https://github.com/MinecraftSTL/XYML)

[![QQ Group](https://img.shields.io/badge/QQ-gray?style=flat-square&logo=qq&logoColor=ffffff)](https://qm.qq.com/cgi-bin/qm/qr?k=wz9sCQuIj4TiQBHUpeuBGM-pZ83f5ini&jump_from=webapi&authKey=VKucBpojFUOiDWF7OCbmvDI6Vfkjr+S1m4e7+unOBAuEfW/j1yXYTnf50c+z/NWs)
[![Bilibili](https://img.shields.io/badge/Bilibili-gray?style=flat-square&logo=bilibili)](https://space.bilibili.com/2059457567)


</div>
<!-- #END BLOCK -->

---

<!-- #BEGIN LANGUAGE_SWITCHER -->
中文 ([简体](README_zh.md), [繁體](README_zh_Hant.md), [文言](README_lzh.md)) | **English** (**Standard**, [uʍoᗡ ǝpᴉsd∩](README_en_Qabs.md)) | [日本語](README_ja.md) | [español](README_es.md) | [русский](README_ru.md) | [українська](README_uk.md)
<!-- #END LANGUAGE_SWITCHER -->

## Introduction

XYML (xOyz Minecraft Launcher) is an open-source, cross-platform Minecraft launcher independently maintained by the
MinecraftSTL community. Its current desktop interface is built with Swing and provides complete workflows for
installing and launching the game, maintaining instances, and managing modpacks.

Its main features include:

- Installing and managing Minecraft together with loaders such as Forge, NeoForge, Cleanroom, Fabric, Legacy Fabric,
  Quilt, LiteLoader, and OptiFine;
- Managing accounts, game instances, Java runtimes, and game settings;
- Managing mods, resource packs, worlds, and modpacks, including modpack installation, updates, creation, and export;
- Customizing launcher themes, backgrounds, and appearance.

XYML supports multiple operating systems and CPU architectures. See the [platform support table](PLATFORM.md) for exact
support levels and limitations. The launcher requires Java 17 or later, and Java 21 is recommended.

## Relationship with HMCL

XYML originates from the code and Git history of
[HMCL (Hello Minecraft! Launcher)](https://github.com/HMCL-dev/HMCL), which is this project's upstream repository.
HMCL's maintainers and community contributors established the foundations that XYML inherits, including game
launching and downloads, instance and account management, modpacks, localization, and cross-platform support. This
repository preserves those historical commits, authorship, and copyright notices with gratitude.

XYML is a downstream project independently maintained by MinecraftSTL and is not an official HMCL release. XYML
manages its own versions, release channels, artifacts, and issue tracking; the release status of HMCL and XYML does not
represent the other project. This repository retains `HMCL-dev/HMCL` as its upstream source so that suitable upstream
changes can be tracked and evaluated for XYML.

## Downloads and Releases

Download XYML Stable builds from [Github Release](https://github.com/MinecraftSTL/XYML/releases). The official website
publishes Stable and Beta builds. XYML uses four release channels: stable, beta, alpha, and dev. Their version formats,
test audiences, feedback policies, and promotion order are documented in the [release model](ReleaseSchedule.md).

## Contributing

You can participate in XYML through the following channels:

- Report problems or propose features through [GitHub Issues](https://github.com/MinecraftSTL/XYML/issues/new/choose);
- Fork this repository and [submit a pull request](https://github.com/MinecraftSTL/XYML/compare);
- Follow the [contributing guide](Contributing.md) to build, run, and debug XYML from source.

## Contributors and Acknowledgments

Thank you to everyone who contributes code, translations, testing, issue reports, and documentation to XYML.

[![XYML Contributors](https://contrib.rocks/image?repo=MinecraftSTL/XYML)](https://github.com/MinecraftSTL/XYML/graphs/contributors)

We also thank
[HMCL's authors and contributors throughout its history](https://github.com/HMCL-dev/HMCL/graphs/contributors). The
long-running development inherited by XYML belongs to that upstream history and should not be represented as work
produced solely by the XYML project.

## License

XYML is distributed under the [GNU General Public License, version 3 or later](../LICENSE), with the GPLv3 Section 7
additional requirements inherited from HMCL:

1. When distributing a modified version of this program, you must reasonably change its name or version number so that
   it can be distinguished from the original version (under
   [GPLv3 Section 7(c)](../LICENSE#L372-L374)). The program name and version are defined in
   [`Metadata.java`](../XYML/src/main/java/space/minecraftstl/xyml/Metadata.java).
2. You must not remove copyright notices displayed by the program (under [GPLv3 Section 7(b)](../LICENSE#L368-L370)).

The complete licensing terms are provided in [LICENSE](../LICENSE) and in the copyright and license notices of individual source files.
