<div align="center">
    <img src="/XYML/src/main/resources/assets/img/icon@8x.png" alt="XYML Logo" width="64"/>
</div>

<h1 align="center">xOyz Minecraft Launcher</h1>

<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-repo-blue?style=flat-square&logo=github)](https://github.com/MinecraftSTL/XYML)

</div>

---

**简体中文（默认）** | [English](docs/README.md) | [繁體中文](docs/README_zh_Hant.md) |
[文言](docs/README_lzh.md) | [日本語](docs/README_ja.md) | [español](docs/README_es.md) |
[русский](docs/README_ru.md) | [українська](docs/README_uk.md)

## 简介

XYML（xOyz Minecraft Launcher）是由 MinecraftSTL 社区独立维护的开源、跨平台 Minecraft 启动器。当前桌面界面基于 Swing，围绕游戏安装与启动、实例维护和整合包管理提供完整工作流。

XYML 的主要功能包括：

- 安装与管理 Minecraft，以及 Forge、NeoForge、Cleanroom、Fabric、Legacy Fabric、Quilt、LiteLoader 和 OptiFine 等加载器；
- 管理账户、游戏实例、Java 与游戏设置；
- 管理模组、资源包、存档和整合包，并支持整合包的安装、更新、创建与导出；
- 自定义启动器主题、背景和界面外观。

XYML 支持多种操作系统与 CPU 架构，具体支持程度及限制请参阅[平台支持状态](docs/PLATFORM_zh.md)。启动器主体需要 Java 17 或更高版本，推荐使用 Java 21。

## 与 HMCL 的关系

XYML 源自 [HMCL（Hello Minecraft! Launcher）](https://github.com/HMCL-dev/HMCL)的代码与 Git 历史，HMCL 是本项目的上游仓库。HMCL 的维护者和社区贡献者奠定了 XYML 所继承的启动、下载、实例与账户管理、整合包、国际化和跨平台支持等基础；本仓库继续保留这些历史提交、作者信息和版权声明，并对此表示感谢。

XYML 是由 MinecraftSTL 独立维护的下游项目，并非 HMCL 的官方发行版。XYML 的版本号、发布渠道、构建产物和问题反馈均由本仓库独立管理；上游 HMCL 的发布状态与 XYML 不互相代表。仓库保留 `HMCL-dev/HMCL` 作为上游来源，以便追踪和评估适合 XYML 的上游变更。

## 下载与发布

请从 [GitHub Releases](https://github.com/MinecraftSTL/XYML/releases) 获取 XYML 的正式构建。XYML 采用稳定版、公测版、内测版和开发版四级发布渠道；各渠道的版本格式、测试范围、反馈方式和晋升顺序见[发布模型](docs/ReleaseSchedule_zh.md)。

## 参与贡献

欢迎通过以下方式参与 XYML：

- 在 [GitHub Issues](https://github.com/MinecraftSTL/XYML/issues/new/choose) 报告问题或提出功能建议；
- Fork 本仓库并[提交 Pull Request](https://github.com/MinecraftSTL/XYML/compare)；
- 按照[贡献指南](docs/Contributing_zh.md)从源码构建、运行和调试 XYML。

## 贡献者与致谢

感谢所有通过代码、翻译、测试、问题反馈和文档参与 XYML 的贡献者。

[![XYML Contributors](https://contrib.rocks/image?repo=MinecraftSTL/XYML)](https://github.com/MinecraftSTL/XYML/graphs/contributors)

同时感谢 [HMCL 的作者与历代贡献者](https://github.com/HMCL-dev/HMCL/graphs/contributors)。XYML 所继承的长期开发成果属于这段上游历史，不应被误写为仅由 XYML 项目产生。

## 开源协议

XYML 按照 [GNU 通用公共许可证第 3 版或更高版本](LICENSE)发布，并保留源自 HMCL 的 GPLv3 第 7 条附加要求：

1. 分发本程序的修改版本时，必须以合理方式修改程序名称或版本号，使其能够与原始版本区分（依据 [GPLv3 第 7(c) 项](LICENSE#L372-L374)）。程序名称和版本号定义在 [`Metadata.java`](XYML/src/main/java/space/minecraftstl/xyml/Metadata.java) 中。
2. 不得移除程序显示的版权声明（依据 [GPLv3 第 7(b) 项](LICENSE#L368-L370)）。

完整授权条件以 [LICENSE](LICENSE) 及各源文件中的版权和许可证声明为准。
