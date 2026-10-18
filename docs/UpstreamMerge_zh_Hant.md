# 上游合併指南

<!-- #BEGIN LANGUAGE_SWITCHER -->
**中文** ([简体](UpstreamMerge.md), **繁體**) | [English](UpstreamMerge_en.md)
<!-- #END LANGUAGE_SWITCHER -->

本文是 XYML 維護者合併 HMCL 上游變更時的檢查清單。簡體中文版本是本指南的基準；英文和繁體中文版本應與本文保持相同的章節、命令、路徑和約束。

## 適用範圍與目前差異

XYML 是從 HMCL 演進而來的獨立專案。`origin` 指向 XYML，`upstream` 指向 HMCL-dev/HMCL。兩棵樹已經發生架構和產品邊界分化，不能把上游檔案當作同構專案直接覆蓋到 XYML。

每次同步都要記錄本地 `dev`/`HEAD`、所用 `upstream/main` 的完整 SHA、共同祖先、雙方獨有提交數和待引入修補程式統計。使用快取引用時記錄其提交時間和來源，不把它稱為遠端最新狀態。

下文命令應在每次實際同步時重新執行。三點語法查看的是從共同祖先到上游的待引入修補程式；最終樹對比只用於發現架構、API 與產品邊界差異，不能代替待引入範圍分析，也不能作為整檔案覆蓋依據。

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

按行為而不是僅按檔案名稱分類；每次同步都將實際待引入的提交登記為 `direct review`、`manual adaptation`、`product decision` 或 `skip`，記錄上游 SHA、本地對應入口、適配/跳過理由和測試結果。

| 類別 | 檢查範圍 | XYML 的處理重點 |
| --- | --- | --- |
| 核心邏輯 | 認證、下載、完整性、載入器、Archive/ZIP、實例與模組包 | 比對目前呼叫方、資源宣告、錯誤處理和邊界測試。 |
| 資料模型/API | record、序列化、倉庫/配置/快照、設定與帳戶 | 核對公開契約、欄位名稱、schema、所有權與相容決策。 |
| 手動適配 | JavaFX 控制項/狀態、任務呈現、導覽、主題、更新提示 | 移植到本地 Swing、observable、任務及服務入口，禁止恢復 JavaFX 依賴。 |
| 產品決策 | 功能刪除、供應方能力、執行環境、發佈與品牌 | 預設保留本地功能和渠道；涉及外部行為先確認。 |
| 文件與本地化 | 文件語言、檔案名稱、巨集、連結、I18N 鍵 | 文件按本地語言規則映射，資源按鍵合併並保持三語同步。 |

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

### UI、可觀察狀態和執行緒遷移

- JavaFX 控制項、屬性、繫結和可觀察集合不能直接套用。將業務行為映射到 `XYML/src/main/java/space/minecraftstl/xyml/ui/swing`，Core 狀態沿用 `XYMLCore` 的 `observable` 契約；保留具體事件載荷、通知順序和訂閱釋放。
- 背景 I/O 沿用 `Schedulers.io()`，UI continuation 使用已安裝的 `Schedulers.ui()`，並核實 Swing EDT 歸屬；不要恢復 `Schedulers.javafx()`、`FXUtils` 或 JavaFX 型別。
- 核對頁面快取、導覽、對話框關閉和非同步結果失效邊界。上游刪除的 UI 檔案仍可能包含應移植的通用邏輯，不能僅因本地沒有同名檔案就跳過整個提交。
- 主題、圖片和色彩沿用現有工具包中立的 Core 資料與 Swing 邊界轉換。架構約束見 [Swing UI 遷移](architecture/swing-ui-migration.md)。

### Task 執行、資源和生命週期遷移

- 上游任務和取消回呼必須適配本地 `Task`、`TaskExecutor`、`TaskExecutionRegistry` 和 `TaskResource`，不能只解決簽章衝突或恢復舊任務對話框。
- 核對讀寫路徑、實例、遊戲目錄、快取與共用資源宣告；沿用資源仲裁和清理 API，不用全域資源掩蓋缺失的精確宣告，也不把未知資源擅自標成無資源的編排階段。
- `start()` 不代表工作已完成，也不保證立即進入執行態。保留 `WAITING`、執行、取消請求和最終狀態的區別，核對重試、進度、日誌、訂閱及清理失敗的可見性。
- 使用者可見任務透過現有 `TaskLaunchController` 接入；自動開啟任務頁選取「進行中」，手動進入則保留既有分頁。關閉發起頁面不能取消已交由任務系統擁有的安裝任務。
- 補充資源衝突/並行、等待中取消、異常及中斷清理、頁面關閉後繼續執行、狀態與進度發佈測試；不要以任務已提交或 UI 已關閉作為執行成功的依據。

### 倉庫、安裝和遠端附加內容入口

- 上游倉庫、實例、配置和快照 API 先與 `XYMLGameRepository`、`GameRepository`、`GameRepositorySnapshot` 及實際呼叫方逐項比對；同名快照不代表欄位、所有權和重新整理事件完全相容。
- `GameRepositoryDraft` 只涵蓋明確加入草稿的檔案和參與者；既有安裝、重新命名、刪除、模組包與修復入口不會自動獲得整操作回復或跨重啟復原。約束見 [Repository draft API](architecture/repository-drafts.md)。
- 遊戲安裝映射到本地 `game/install` 服務和任務工廠，遠端內容映射到現有 `addon` 倉庫及 Swing 下載/更新入口；保留倉庫實例、目標實例、執行目錄、載入器和資源型別的明確上下文。
- 核對 CurseForge/Modrinth 單例、`RemoteAddon.Type`、分頁、推薦/測試版本、依賴選擇、更新和檔案操作失敗回饋。不要因上游重用元件而擴大供應方能力，或遺失本地排序、篩選、去重和並行安裝隔離。

### 設定、帳戶和持久化邊界

- 雙方都使用分離的設定/狀態和 JSON schema，但不能據此判定格式與載入策略一致。逐項核對 `SettingsManager`、`GameSettings`、`docs/schemas`、檔案位置、schema 版本、未知欄位和不可寫狀態。
- 本地帳戶中繼資料與私密資料分開儲存；檢查本地/使用者作用域、帳戶 ID、認證工廠、權杖重新整理和遊戲檔案快取。不得將私密欄位寫入普通設定、日誌、任務快照或匯出資料。
- 本地實例設定遷移策略位於 `InstanceConfigMigrationPolicy` 並由設定預設持有；它不是上游 `LegacyConfigMigrator`、`LegacyGameSettingsMigrator` 或 `MigrationReceipt` 的等價實作，不能直接引入 HMCL 舊檔案掃描和自動轉換。
- 如果確需改變持久化格式、公共 API 或外部呼叫契約，先列明獨立轉換、主程式邊界適配、明確不相容三種方案及影響，取得確認後再實施。未經確認不刪除舊讀取、不新增遷移器，也不預設 HMCL 檔案就是 XYML 的舊資料。

### 主題、啓動和自我更新

- 主題變更映射到本地 `theme` 模型、主題包倉庫及 Swing 外觀設定，保留主題引用、亮度偏好、背景和包讀取邊界；不要恢復 JavaFX CSS、MonetFX 或上游主題管理器。
- 啓動、錯誤提示與更新變更分別檢查 Swing 啓動入口、`UpdateApplier`、`UpdateStartupResult`、`UpdateChannel` 和 `XYMLDownloadTask`；上游 JavaFX 失敗時回退 Swing 的實作不能整段搬入 Swing-only 啓動流程。
- 保留離線啓動、更新製品完整性、替換/重啓參數以及 XYML 下載來源、應用程式 ID、`.xyml` 和 `xyml.*`；上游版本號、更新端點與發佈渠道不是本地預設值。

### 本地功能和安全邊界

- 核對存檔、資源包、光影包、原理圖、模組、模組包匯出和實例設定遷移的既有入口。上游新增、重新命名或刪除頁面時，先判斷本地是否已有不同實作；MultiMC 匯出仍是本地功能，不能因上游刪除而一併移除。
- 崩潰分析、日誌、修復任務和 Java 執行環境管理有本地服務及 UI；通用修復應落到既有入口，不恢復上游頁面或假設 GUI 與 MCP 修復共用所有狀態和回復範圍。
- `XYMLCore`/`XYML` 的 `mcp` 與應用程式 `nbt`、`libraries/xoyz-mcp`、`libraries/xoyz-nbt` 屬於本地邊界；保留鑑權、脫敏、刪除確認、路徑限制、有界解析和事務性寫入，不用上游缺少這些模組作為刪除理由。

### 內建庫、建置和程式碼契約

- 本地 `settings.gradle.kts` 還包含 `xoyz-nbt`、`xoyz-mcp`、`XYMLL`、`lwjgl-unsafe-agent`、`mesa-loader-windows`。按 `libraries/README.md` 和匯入庫的 `SOURCE.md` 審查原始碼、資源、許可證、固定 SHA 與身分測試；不能改回上游依賴座標或覆蓋本地修改。
- 應用程式/Core/buildSrc 使用 Java 17；Boot 和 Minecraft 引導庫的生產程式碼保持 Java 8，`lwjgl-unsafe-agent` 使用 Java 25 工具鏈。核對實際 `build.gradle.kts`、Wrapper、版本目錄、fat JAR 與 `verifyOfflineUiArtifact`，不以建置 JVM 版本替代目標位元組碼檢查。
- 開發變更基於目前 `dev`，驗證後以 `--no-ff` 整合回 `dev`；發佈遵循 `dev -> alpha -> beta -> main`。保留 `config/project.properties` 的 `stableVersion`、版本解析和發佈任務；合併上游不是發佈授權。
- 移植的首方 Java 宣告遵循目前 `AGENTS.md`：完整許可證標頭、`@NotNullByDefault`、精確 `@Nullable`、集合所有權註解和 `///` 文件。保留嵌入式第三方的許可證和局部風格，不關閉品質檢查。

### 文件預設語言和巨集遷移

- 上游無語言後綴的文件是英文，簡體中文使用 `_zh_Hans.md`；XYML 無後綴文件是簡體中文，英文使用 `_en.md`，繁體中文使用 `_zh_Hant.md`。應按內容語言映射，不能把上游無後綴檔案直接覆蓋到本地同名檔案。
- 同時映射相對連結、錨點、`BLOCK`、`COPY` 來源與 `LANGUAGE_SWITCHER`；不能只改檔案名稱。檢查 `buildSrc` 的 `DocumentLocale`、文件樹和產生器，保留簡體中文優先的語言順序及缺失譯文回退。
- 無後綴的 `I18N.properties` 仍是英文預設資源，與文件規則不同；資源鍵、占位符和轉義逐項合併。詳細規則見 [本地化指南](Localization_zh_Hant.md#文件與在地化資源)。
- 修改任一 `docs/**/*.md` 後執行 `updateDocuments`，檢查全部重寫差異、各語言連結和複製區塊；不能手動修補產生輸出來繞過來源巨集或語言映射錯誤。

### 必須保留的本地邊界

- `XYML` 是 Swing-only；`XYMLCore` 保持 toolkit-neutral，介面行為應落在現有 `XYML/ui/swing` 邊界。上游 JavaFX/JFoenix 程式碼只能作為行為參考。
- 保留 `xoyz-nbt`、`xoyz-mcp`、`XYMLL`、`lwjgl-unsafe-agent`、`mesa-loader-windows` 等本地庫，以及匯入庫的 `SOURCE.md`、許可證、上游 SHA 和身分測試。
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
   git switch -c <integration-branch> dev
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

還應按實際觸及的差異增加以下驗證，不以編譯通過替代行為測試：

- Task 資源等待/取消、清理失敗、執行登錄表發佈，以及自動/手動導覽和頁面關閉後的所有權。
- 倉庫快照隔離、草稿衝突/失敗復原及未加入草稿的操作邊界；安裝和遠端內容的實例/供應方/型別上下文。
- 設定與帳戶 schema、未知欄位、不可寫狀態、私密資料分離和舊資料 round-trip；未經授權不執行真實使用者資料遷移。
- 主題包讀取、離線啓動、更新完整性與替換參數，以及受影響的本地功能、MCP/NBT 和內建庫身分檢查。

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
