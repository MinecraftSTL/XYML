# 上游合併指南

<!-- #BEGIN LANGUAGE_SWITCHER -->
**中文** ([简体](UpstreamMerge.md), **繁體**) | [English](UpstreamMerge_en.md)
<!-- #END LANGUAGE_SWITCHER -->

本文是 XYML 維護者合併 HMCL 上游變更時的檢查清單。簡體中文版本是本指南的基準；英文和繁體中文版本應與本文保持相同的章節、命令、路徑和約束。

## 適用範圍與目前差異

XYML 是從 HMCL 演進而來的獨立專案。`origin` 指向 XYML，`upstream` 指向 HMCL-dev/HMCL。兩棵樹已經發生架構和產品邊界分化，不能把上游檔案當作同構專案直接覆蓋到 XYML。

計畫勘察基準（2026-09-12）如下，數字只用於說明如何記錄一次同步，不是永久版本號：

| 項目 | 基準值 |
| --- | --- |
| 目前 `HEAD` | `9e48f059cf02c2ec96a071448356099840a69a9b` |
| `upstream/main` | `df52bc6e81e2e1116c131483dfb9996fdb7b2b10` |
| 共同祖先 | `b4549b6f68b99bb5fa6d69f94fffffcee0dc36c1` |
| `HEAD...upstream/main` 提交計數 | 本地獨有 `528`，上游獨有 `26` |
| 待引入差異（`git diff HEAD...upstream/main`） | 76 個檔案，新增 1878 行，刪除 1124 行 |

上游引用可能在勘察後前移；例如本次編寫時已觀察到新的上游提交。因此每次實際同步都必須重新記錄以下命令的輸出。三點語法查看的是從共同祖先到上游的待引入修補程式；直接比較兩棵最終樹會把歷史重新命名和 XYML 的獨立實作混在一起，結果不能直接用於合併決策。

## 合併前檢查

### 檢查工作樹和 ref

先在沒有未提交變更的整合分支上工作。目前 `dev` 可能已被另一個 linked worktree 佔用，不能為建立分支而切換或重置那個工作樹。

```shell
git status --short --branch
git worktree list --porcelain
git branch -vv
git remote -v
git show-ref
git rev-parse HEAD
git rev-parse upstream/main
git show -s --format="%H%n%ad%n%s" --date=iso upstream/main
git rev-parse -q --verify MERGE_HEAD
git diff --name-only --diff-filter=U
```

確認以下條件：

- 工作樹乾淨，沒有 `MERGE_HEAD`、rebase 或 cherry-pick 狀態。
- 目標分支沒有在另一個 linked worktree 中以未提交狀態佔用；若已佔用，改用獨立整合分支和工作樹。
- `origin`、`upstream` URL 和完整的 `upstream/main` SHA 已記錄。
- 不把本地 `dev`、發佈分支或使用者未提交變更直接作為合併目標。

如果使用快取的上游 ref，額外記錄來源和時間：

```shell
git show -s --format="%H %ci %s" upstream/main
git reflog show --date=iso upstream/main
```

沒有明確授權時，不要執行 `git fetch`、`git push` 或修改共用遠端 ref。

### 計算待引入範圍

確認遠端和 ref 後，再記錄差異和重複提交：

```shell
git merge-base HEAD upstream/main
git rev-list --left-right --count HEAD...upstream/main
git log --cherry-pick --left-right --oneline HEAD...upstream/main
git diff --shortstat HEAD...upstream/main
git diff --name-status --find-renames HEAD...upstream/main
git log --oneline --reverse HEAD..upstream/main
```

只有得到明確授權時才執行網路操作，例如 `git fetch upstream main`。若採用本地快取的 `upstream/main`，應在記錄中寫明 ref 的完整 SHA，並且本流程不隱式 fetch 或 push。先檢查 `git log --cherry-pick` 和修補程式內容，再決定是完整合併、選擇性移植還是跳過；不要僅按檔案名稱判斷重複。為每個上游提交標記 `direct review`、`manual adaptation`、`product decision` 或 `skip`，並記錄對應的 XYML 路徑、處理理由和測試。

## 上游變更分類

把 `git log --reverse HEAD..upstream/main` 得到的每個提交登記到下表的一類，並在登記中寫出 XYML 對應路徑、保留或跳過的理由以及測試。

| 類別 | 目前上游變化 | XYML 的處理重點 |
| --- | --- | --- |
| 核心邏輯，可優先評估 | Yggdrasil token 每次登入校驗（`9d9fc7838`）；CurseForge 重試、雜湊和完整性（`e287f672f`）；客戶端 JAR 同步（`24702dc5a`） | 映射到現有 `XYMLCore` 任務、儲存庫和匯出器。 |
|  | Forge/Cleanroom 啟動修復（`513f53bc0`、`0ad180c08`）；預設建置取消與封存/ZIP 處理（`b2d4d3685`、`9c031c5ac`、`e2d3ac847`） | 保留任務資源鎖、離線行為、取消/重試和安全邊界。 |
|  | 實例父級清理（`1a258a255`）；mrpack 停用檔案和依賴更新（`1da8c8d6a`、`0f4152406`）；`.gitignore` 的 `*.jfr`（`3742be0aa`） | 為每個行為補充或執行針對性測試。 |
| 資料模型/API | `OptiFineVersion`（`0e3455434`）和 `CurseMetaMod`（`1acfc5500`）改為 record | 檢查 Gson 註解、舊 JSON/清單和建構器。 |
|  |  | 確認序列化欄位名稱、呼叫方和向後相容，避免機械替換 getter。 |
| 需要手動適配的 UI | 資源包圖示快取、主題、搜尋排序、載入器標籤、長名稱（`886a25a93`、`450504ced`、`8767cc0e9`、`7331f619c`、`7a8f93164`） | 只移植行為到現有 Swing 控件或 toolkit-neutral Core。 |
|  | 載入器重置、樹單元格、圖示對話框、工具列和進度控制項（`ce51dc4dd`、`b97e7fbd5`、`7f578be08`、`cbc6daf3d`、`ea232b097`） | 禁止恢復 JavaFX、JFoenix、MonetFX、OpenJFX 下載器、module flags 或執行期修補程式。 |
| 必須作產品決策 | 上游刪除 MultiMC 匯出（`df52bc6e8`） | XYML 目前仍公開支援 MultiMC，預設保留。 |
|  |  | 若決定刪除，必須同時評審 Core 任務、工廠、UI、I18N、資源和測試，不能只接受上游刪除。 |
| 品牌和本地化 | 上游將 Hello Minecraft Launcher 縮寫為 HMCL（`1428183e3`） | 不得覆蓋 XYML 品牌、`.xyml`、`xyml.*` 或應用程式 ID。 |
|  |  | 語言鍵逐項合併並同步英文、簡體中文、繁體中文，保留占位符和轉義。 |

## 不能直接接受的檔案組

上游路徑使用 `HMCL`、`HMCLCore` 和 `HMCLBoot`，本地路徑使用 `XYML`、`XYMLCore` 和 `XYMLBoot`。上游提交中的路徑必須先映射到本地模組、套件和測試後才能套用。先建立映射，再逐檔案移植；不要透過選擇整個目錄的 `ours` 或 `theirs` 來隱藏差異。

## XYML 專屬適配規則

### 命名空間、資源和建置

逐項建立映射表，而不是全域替換：

| 上游 | XYML |
| --- | --- |
| `HMCL` | `XYML` |
| `HMCLCore` | `XYMLCore` |
| `HMCLBoot` | `XYMLBoot` |
| `org.jackhuang.hmcl` | `space.minecraftstl.xyml` |

還要檢查反射字串、服務載入器檔案、主類別、資源目錄、系統屬性、序列化名稱和清單。可用 `git log --follow` 追蹤歷史路徑，但不要按路徑批量移動檔案。

### 必須保留的本地邊界

- `XYML` 是 Swing-only；`XYMLCore` 保持 toolkit-neutral，介面行為應落在現有 `XYML/ui/swing` 邊界。上游 JavaFX/JFoenix 程式碼只能作為行為參考。
- 保留 `xoyz-nbt`、`xoyz-mcp`、`XYMLL`、`lwjgl-unsafe-agent`、`mesa-loader-windows` 等本地庫，以及每個庫的 `SOURCE.md`、許可證、上游 SHA 和身分測試。
- 檢查 `XYMLTransformerDiscoveryService`、`XYMLMultiMCBootstrap`、服務描述檔、系統屬性、製品名和 `HMCLMultiMCBootstrap-1.0.jar` 資源引用。
- 保留 XYML 的版本解析、四渠道發佈流程、離線製品校驗、MCP 鑑權與脫敏、stdio 邊界、NBT 有界/事務性編輯、任務資源所有權和現有目錄篩選。
- 建置腳本、工作流程、Jenkins、版本屬性和發佈設定不能整檔案替換；特別檢查 Java 17/25/8 工具鏈和 `stableVersion` 契約。

### 本地行為契約

解決上游衝突時不得刪除或弱化以下 XYML 契約：

- MCP bearer 鑑權、敏感資料脫敏和本地 stdio 傳輸邊界。
- 有界、嚴格、事務性的 NBT 解析與編輯。
- 任務資源鎖、所有權、取消、重試和並行更新規則。
- 崩潰分析、診斷輸出和離線行為。
- Swing 目錄、模組和版本篩選，以及去重和排序行為。

### 本地化和資料相容

合併前後都要用舊設定、舊實例清單、舊模組包和舊快取做 round-trip 檢查。record 化或欄位改名時檢查 Gson 欄位名稱、舊資料、匯入的實例/模組包和反射呼叫方，並在需要時保留相容存取器或遷移路徑；改變類別路徑順序、壓縮讀取選項或下載完整性檢查時，驗證錯誤處理、重複項目、父目錄和邊界路徑。

I18N 必須按鍵合併，並同步 `I18N.properties`、`I18N_zh_CN.properties` 和 `I18N_zh.properties`：保留格式化占位符、Properties 轉義、換行和唯一鍵，單獨審查品牌文案，不用上游 HMCL 文案覆蓋本地品牌。還要檢查 classpath 順序、服務描述檔、清單、嵌入式 JAR 和離線快取，使打包名稱仍符合 XYML 啟動流程。文件巨集、相對連結和三語檔案也屬於本地化契約。

## 衝突解決流程

1. 從目標快照建立並進入整合分支，並確認目標分支沒有被其他工作樹佔用，例如：

   ```shell
   git switch -c <integration-branch>
   ```

2. 記錄已評估的上游提交。需要保留拓撲時，在確認分類結果後使用：

   ```shell
   git merge --no-ff upstream/main -m "merge(upstream): integrate <range>"
   ```

   對不相容的 UI 或產品變更，採用有記錄的選擇性移植或跳過；不要盲目 cherry-pick，也不要對整個目錄使用 `ours`/`theirs`。

3. 按本地模組和職責邊界逐檔案解決衝突，必要時同步改寫路徑、套件名稱、資源和測試。不要用整個目錄的 `ours` 或 `theirs` 隱藏差異。

4. 每次解決衝突後檢查：

   ```shell
   rg -n -uu "<<<<<<<|=======|>>>>>>>" .
   rg -n -uu "org\.jackhuang\.hmcl|HMCLCore|HMCLBoot|/HMCL|\\HMCL|JavaFX|javafx|JFoenix|MonetFX|OpenJFX|hmcl\.|\.hmcl" XYML XYMLCore XYMLBoot libraries minecraft
   git diff --check
   git diff --name-only --diff-filter=U
   ```

   然後逐項檢查服務描述檔、`META-INF`、資源索引、清單中的主類別、系統屬性、製品名及序列化欄位。不要用一次全域搜尋替代逐檔案審查。

5. 如果真實合併正在進行且無法繼續，只能在該合併狀態下使用 `git merge --abort`；不要用破壞性重置或整棵樹回滾來掩蓋未解決的衝突。

## 驗證門檻

### 文件與本地化

修改本指南或任一 `Contributing` 文件後，從儲存庫根目錄執行：

```shell
.\gradlew.bat updateDocuments
```

檢查三份指南和三份 `Contributing` 文件中的語言切換器、相對連結、標題和產生差異；不要手動改寫產生的 `LANGUAGE_SWITCHER` 內容。

### 程式碼與資源

按變更類別執行最小測試集：認證和帳戶登入、CurseForge 下載與完整性、遊戲依賴/JAR 同步、Archive/ZIP 路徑安全、實例父級和循環引用、啟動器建置、modpack 匯出，以及受影響的 Swing/EDT 行為。具體檢查 Archive/ZIP 的重複項目、父目錄、編碼和大小邊界；實例缺失父級與循環引用；record 序列化和舊資料 round-trip；本地庫身分測試；以及受影響 Swing 控制項在 EDT 上的行為和佈局。修改本地庫或服務元件時執行對應身分測試。

### 儲存庫檢查

文件或原始碼變更完成後執行：

```shell
.\gradlew.bat checkstyle checkTranslations --no-daemon --parallel --stacktrace
.\gradlew.bat build --no-daemon --parallel
.\gradlew.bat :test --no-daemon --parallel
```

使用儲存庫 Gradle Wrapper 和模組要求的 JDK 17/JDK 25，並優先使用已記錄的本地快取。若完整建置受 JDK、原生工具、網路、權限、離線快取或平台限制，執行最強的可行替代檢查，並在交付記錄中把程式碼失敗、環境略過和未執行項目分開說明。UI 或建置設定有改動時，還要執行 `verifyOfflineUiArtifact` 或同等離線製品檢查，確認沒有重新引入 JavaFX/JFoenix 項目。

修改 `docs/**/*.md` 後，必須審查 `updateDocuments` 重寫的全部差異，確認語言切換器和相對連結只指向存在的語言檔案。由於 `.github/workflows/gradle.yml` 使用 `paths-ignore: '**.md'`，文件-only PR 可能不會觸發 Java CI，不能把它當作本地驗證的替代品。

### 最終 Git 驗證

```shell
git diff --check
git status --short --branch
git show -s --format="%H %P %ci %s" HEAD
git merge-base --is-ancestor upstream/main HEAD
```

## 最終交付檢查

- `git diff --check` 通過，搜尋不到衝突標記或不應出現的 HMCL/JavaFX 殘留。
- 若採用完整合併，`git merge-base --is-ancestor upstream/main HEAD` 成功，且 `git show -s --format=%P HEAD` 顯示兩個父節點；若選擇性移植，保留未成為祖先的原因和提交映射。
- `git status --short` 乾淨，分支、上游 SHA、產生文件和測試結果已記錄。
- 不提交 `build/`、`.gradle*`、IDE 設定、執行資料或本地設定；不隱式 push。

## 完成標準

- 只包含已審查的上游提交、必要的 XYML 適配和對應測試或文件。
- 不存在衝突標記、過時 HMCL 路徑、JavaFX/JFoenix 依賴或錯誤的 `.hmcl`/`hmcl.*` 設定。
- 已執行並記錄 `updateDocuments`、`git diff --check`、本地化檢查、相關測試和最強可行的完整建置。
- 合併或選擇性移植記錄保留上游 SHA、處理結論和跳過原因；發佈分支仍遵循 `dev -> alpha -> beta -> main` 的 `--no-ff` 拓撲。
