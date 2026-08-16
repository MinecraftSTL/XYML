# 貢獻指南

<!-- #BEGIN LANGUAGE_SWITCHER -->
**中文** ([简体](Contributing_zh.md), **繁體**) | [English](Contributing.md)
<!-- #END LANGUAGE_SWITCHER -->

## 構建 XYML

### 環境需求

構建完整的 XYML 倉庫需要同時安裝 JDK 25 和 JDK 17。你可以從此處下載它們：[Download Liberica JDK](https://bell-sw.com/pages/downloads/#jdk-25-lts)。
請將 `JAVA_HOME` 指向 JDK 25，並確保 [Gradle 工具鏈](https://docs.gradle.org/current/userguide/toolchains.html)能夠發現 JDK 17。
只有 `lwjgl-unsafe-agent` 使用 Java 25 編譯。XYML、XYMLCore、buildSrc 和 HelloNBT 繼續相容 Java 17；
啟動模組和 Minecraft 輔助模組繼續以 Java 8 為目標，Mesa 載入器也繼續保留更低的位元組碼目標。

在 Windows 上構建原生 `XYMLL` 啟動器還需要 CMake 3.16 或更高版本、帶 MSVC x86/x64 C++ 工具的
Visual Studio 2022 Build Tools，以及 Windows SDK。不支援 MinGW 工具鏈。其他作業系統會驗證並使用倉庫中
由同一份原始碼構建的可執行檔。

安裝 JDK 後，請確保 `JAVA_HOME` 環境變數指向 JDK 25 目錄。
你可以這樣查看 `JAVA_HOME` 指向的 JDK 版本:

<details>
<summary>Windows</summary>

PowerShell:
```
PS > & "$env:JAVA_HOME/bin/java.exe" -version
openjdk version "25" 2025-09-16 LTS
OpenJDK Runtime Environment (build 25+37-LTS)
OpenJDK 64-Bit Server VM (build 25+37-LTS, mixed mode, sharing)
```

</details>

<details>
<summary>Linux/FreeBSD</summary>

```
> $JAVA_HOME/bin/java -version
openjdk version "25" 2025-09-16 LTS
OpenJDK Runtime Environment (build 25+37-LTS)
OpenJDK 64-Bit Server VM (build 25+37-LTS, mixed mode, sharing)
```

</details>

<details>
<summary>macOS</summary>

```
> /usr/libexec/java_home --exec java -version
openjdk version "25" 2025-09-16 LTS
OpenJDK Runtime Environment (build 25+37-LTS)
OpenJDK 64-Bit Server VM (build 25+37-LTS, mixed mode, sharing)
```

</details>

### 獲取 XYML 原始碼

- 透過 [Git](https://git-scm.com/downloads) 可以獲取最新原始碼:
  ```shell
  git clone https://github.com/MinecraftSTL/XYML.git
  cd XYML
  ```
- 從 [GitHub Release 頁面](https://github.com/MinecraftSTL/XYML/releases)可以手動下載特定版本的原始碼。

### 構建 XYML

想要構建 XYML，請切換到 XYML 專案的根目錄下，並執行以下指令:

```shell
./gradlew clean makeExecutables
```

構建出的 XYML 程式檔位於根目錄下的 `XYML/build/libs` 子目錄中。

### IDEA Gradle 建置流程

將倉庫作為 Gradle 專案匯入後，開啟 Gradle 工具視窗並展開 `XYML > Tasks > XYML workflows`。該分類包含以下入口：

| 任務 | 行為 |
| --- | --- |
| `buildMain` | 擷取並建置最新的 `origin/main` 提交。 |
| `buildBeta` | 擷取並建置最新的 `origin/beta` 提交。 |
| `buildAlpha` | 擷取並建置最新的 `origin/alpha` 提交。 |
| `buildDev` | 擷取並建置最新的 `origin/dev` 提交。 |
| `build` | 發佈分支呼叫上方對應任務；功能分支或游離提交直接建置目前工作樹。 |
| `clean` | 只清理目前工作樹，不檢查或擷取任何分支。 |
| `run` | 使用與 `build` 相同的分支路由，但改為執行 XYML。 |

四個渠道任務會同時重新整理 `main`、`beta`、`alpha` 和 `dev`，再於臨時的游離 worktree 中建置所選提交，
不會切換 IDEA 目前工作樹。在 Windows 上，GitHub 擷取會使用已啟用的 Windows 系統代理。成功的渠道建置產物會連同
`build-info.properties` 複製到 `build/channel-builds/<branch>`；功能分支產物仍位於 `XYML/build/libs`。

如需在不存取 GitHub 的情況下測試現有遠端追蹤引用，可明確關閉重新整理：

```powershell
.\gradlew.bat buildMain '-Pxyml.branchBuild.fetch=false'
```

Windows 系統代理無法使用時，也可以透過 `-Pxyml.branchBuild.gitProxy=<proxy-url>` 明確指定代理。

## 除錯選項

> [!WARNING]
> 本文介紹的是 XYML 的內部功能，我們不保證這些功能的穩定性，並且隨時可能修改或刪除這些功能。
>
> 使用這些功能時請務必小心，錯誤地使用這些功能可能會導致 XYML 行為異常甚至崩潰。

XYML 提供了一系列除錯選項，用於控制啟動器的行為。

這些選項可以透過環境變數或 JVM 參數設定。如果兩者同時存在，那麼 JVM 參數會覆蓋環境變數的設定。

| 環境變數                        | JVM 參數                                       | 功能                             | 預設值                                                                                                         | 額外說明         |
|-----------------------------|----------------------------------------------|--------------------------------|-------------------------------------------------------------------------------------------------------------|--------------|
| `XYML_JAVA_HOME`            |                                              | 設定用於開啟 XYML 的 Java             |                                                                                                             | 僅對 exe/sh 生效 |
| `XYML_JAVA_OPTS`            |                                              | 設定開啟 XYML 時的預設 JVM 參數          |                                                                                                             | 僅對 exe/sh 生效 |
| `XYML_FORCE_GPU`            |                                              | 設定是否強制使用 GPU 加速繪製              | `false`                                                                                                     |
| `XYML_ANIMATION_FRAME_RATE` |                                              | 設定 XYML 的動畫幀率                  | 自動符合 90 Hz 及以上的顯示器更新率，否則為 `60`                                                                    | 會被 `-Dxyml.swing.animationFrameDelayMillis` 覆蓋 |
| `XYML_LANGUAGE`             |                                              | 設定 XYML 的預設語言                  | 使用系統預設語言                                                                                                    |
| `XYML_UI_SCALE`             |                                              | 設定 XYML 的 UI 縮放比例               | 遵循系統目前的縮放比例                                                                                       | 支援倍數 (1.5)、百分比 (150%) 或 DPI (144dpi) |
| `XYML_SKIP_OFFLINE_USERNAME_CHECK` |                                      | 完全跳過非法離線使用者名稱檢查                 | `false`                                                                                                      | 設為 `true` 後啟用，會記錄警告，可能導致無法加入伺服器或遊戲崩潰。 |
|                             | `-Dxyml.dir=<path>`                          | 設定 XYML 的目前資料存放位置               | `./.xyml`                                                                                                   |              |
|                             | `-Dxyml.home=<path>`                         | 設定 XYML 的使用者資料存放位置               | Windows: `%APPDATA%\.xyml`<br>Linux/BSD: `$XDG_DATA_HOME/xyml`<br>macOS: `~Library/Application Support/xyml` |              |
|                             | `-Dxyml.swing.animationFrameDelayMillis=<milliseconds>` | 設定 Swing 動畫計時器的毫秒延遲            | 自動符合 90 Hz 及以上的顯示器更新率，否則為 `16`                                                                    | 必須為正整數 |
|                             | `-Dxyml.self_integrity_check.disable=true`   | 檢查更新時不檢查程式完整性                  |                                                                                                             |              |
|                             | `-Dxyml.bmclapi.override=<url>`              | 設定 BMCLAPI 的 API Root          | `https://bmclapi2.bangbang93.com`                                                                           |              |
|                             | `-Dxyml.discoapi.override=<url>`             | 設定 foojay Disco API 的 API Root | `https://api.foojay.io/disco/v3.0`                                                                          |
|                             | `-Dxyml.update_source.override=<url>`        | 設定 XYML 分渠道更新來源範本            | `https://github.com/MinecraftSTL/XYML/releases/download/release-channels/xyml-update-{channel}.json`                                            | 請求前會取代 `{channel}` |
|                             | `-Dxyml.authlibinjector.location=<path>`     | 設定 authlib-injector JAR 檔的位置  | 使用 XYML 內置的 authlib-injector                                                                                |              |
|                             | `-Dxyml.native.encoding=<encoding>`          | 設定原生編碼                         | 使用系統的本機編碼                                                                                                   |              |
|                             | `-Dxyml.microsoft.auth.id=<App ID>`          | 設定 Microsoft OAuth App ID      | 使用 XYML 內建的 Microsoft OAuth App ID                                                                          |              |
|                             | `-Dxyml.curseforge.apikey=<Api Key>`         | 設定 CurseForge API 金鑰           | 使用 XYML 內建的 CurseForge API 金鑰                                                                               |              |
|                             | `-Dxyml.native.backend=<auto/jna/none>`      | 設定 XYML 使用的本機後端                  | `auto`                                                                                                      |
|                             | `-Dxyml.hardware.fastfetch=<true/false>`     | 設定是否使用 fastfetch 檢測硬體資訊        | `true`                                                                                                      |
