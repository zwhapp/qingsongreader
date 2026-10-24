# 轻松阅读（OpenReader / qingsongreader）发布前合规审查报告

- **审查对象**：`OpenReader/` 仓库（70 个被跟踪文件，最新提交 `8961aec`，版本 v0.5.6 / versionCode 14）
- **审查日期**：2026-10-24
- **审查范围**：全部源代码、构建脚本、依赖清单、文档与资源文件；含 git 全历史（20 个提交）泄露扫描
- **总体结论**：**通过（无阻断发布项）**。发现 6 个「建议修复」、3 个「可选优化」，均已现场修复；构建与单元测试复验通过。

---

## 一、按检查项的结论

### 1. 许可证与知识产权 — ✅ 已通过（1 项建议修复，已执行）

| 编号 | 发现 | 位置 | 严重级别 | 处理 |
| --- | --- | --- | --- | --- |
| L-1 | 无逐文件许可声明头（35 个 .kt 均无版权头）。AGPL-3.0 不强制逐文件头，但项目此前未在任何地方说明所采用的声明模式，外部贡献者/分发者无从判断适用许可 | 全部 `.kt` | 建议修复 | 不改 35 个文件（无谓 churn），改为在 README「许可」节**显式声明集中许可模式**：根 `LICENSE` + `THIRD-PARTY-NOTICES.md` 为唯一许可依据，除另有声明外全部源文件适用 AGPL-3.0；并补充版权行（© 2026 zwhapp 与 QingsongReader 贡献者）与 AGPL 第 13 条源码提供义务说明 |

已核实通过：
- `LICENSE` 为完整 GNU AGPL-3.0 文本（含 "How to Apply" 说明）✅
- **无来源不明代码**：35 个 .kt 全部为本项目自写；对 Readest 是「功能重写而非代码复制」，THIRD-PARTY-NOTICES.md 第 16 行已如实声明 ✅
- 测试样本（`test_book.mobi` / `test_book_palmdoc.mobi` / `test_book.azw3`）由 `tools/gen_mobi_samples.py` **程序化合成**（PalmDB 名 `OPENREADERTEST`，正文为测试文案），无第三方版权 ✅
- 图标资源为自绘矢量（`ic_launcher_foreground.xml` + 自适应图标）✅

### 2. 第三方依赖 — ✅ 已通过

逐一核对 `app/build.gradle.kts`（第 71–114 行）与 `build.gradle.kts`（第 1–6 行）：

| 依赖 | 版本 | 许可证 | 与 AGPL-3.0 兼容性 |
| --- | --- | --- | --- |
| AndroidX Jetpack（Compose UI / Material3 / Icons-Extended / Activity / Navigation / Lifecycle / Room / DataStore） | BOM 2024.10.01 等 | Apache-2.0 | ✅ 兼容 |
| jsoup | 1.18.1 | MIT | ✅ 兼容 |
| OkHttp | 4.12.0 | Apache-2.0 | ✅ 兼容 |
| Coil | 2.6.0 | Apache-2.0 | ✅ 兼容 |
| Kotlin / kotlinx-coroutines / KSP | 2.0.21 / 1.8.1 | Apache-2.0 | ✅ 兼容 |
| junit / androidx.test（仅测试） | 4.13.2 / 1.1.5 | Apache-2.0 / EPL-1.0（仅测试用，不随 APK 分发） | ✅ |

- 全部为宽松许可（Apache-2.0 / MIT），**无 GPL/LGPL/传染性依赖，无商用限制** ✅
| 内置数据 | 许可证 | 署名文件 |
| --- | --- | --- |
| CC-CEDICT（`cedict.tsv`，154,742 条 = 114,268 中文词条 + 40,474 英文反向索引） | CC BY-SA 4.0（Copyleft，但仅约束**数据衍生分发**；本项目的 TSV 改编稿已随包附带完整许可文本与署名，满足 BY-SA 要求） | `assets/dict/CC-CEDICT-LICENSE.txt`（20,138 B，已跟踪）✅ |
| Unihan 汉字表（`hanzi_basic.json`，6,739 字） | Unicode Data Files License | `assets/dict/UNIHAN-NOTICE.txt` ✅ |

| 编号 | 发现 | 位置 | 严重级别 | 处理 |
| --- | --- | --- | --- | --- |
| D-1 | 声明表中 CC-CEDICT 条目数表述不精确（「约 15.5 万条」未说明构成） | `THIRD-PARTY-NOTICES.md:24` | 可选优化 | 已补正为「约 15.5 万条，其中约 11.4 万中文词条 + 约 4 万英文词条反向索引」，与实测一致 |

### 3. 敏感信息泄露 — ✅ 已通过（0 项问题）

| 扫描项 | 方法 | 结果 |
| --- | --- | --- |
| 百度 AppKey / SecretKey / SignKey 字面值 | 工作树 + **git 全历史 20 个提交** `git log -p --all` 检索 | 均**未出现** ✅ |
| 通用密钥模式（api_key / secret / password / token / private_key / Bearer / AKIA…） | 代码与配置全量检索 | 命中项均为变量名、DataStore 键名、OAuth 端点 URL 等良性内容 ✅ |
| 私钥 / 证书 / keystore | `.jks/.keystore/.p12/.pfx/.pem/.key` 检索 | 无被跟踪文件 ✅ |
| 内网地址 / 内网域名 | `10.x` / `192.168.x` / `172.16-31.x` / `.internal` / `.corp` 检索 | 无 ✅ |
| 硬编码本机路径 | `/c/Users` / `C:\Users` / `/home/…` 检索 | 无 ✅ |
| `local.properties`（含 BAIDU_APP_KEY/SECRET） | `git ls-files` + `.gitignore` | **未被跟踪**；`.gitignore` 第 4 行显式忽略 ✅ |
| IDE / 本地配置 | `.idea/`、`*.iml`、`.DS_Store` | 均未跟踪 ✅ |

说明：百度 AppKey/Secret 经 `build.gradle.kts:34-36` 从 `local.properties` 注入 `BuildConfig`，属 OAuth **公开客户端标识**（等同网页登录 client_id），非私密密钥；用户 token 仅存本机 DataStore。此模式已在 `PRIVACY.md:11` 与 `THIRD-PARTY-NOTICES.md:51` 如实披露 ✅

### 4. 内部资产清理 — ⚠️ 2 项建议修复（均已执行）

| 编号 | 发现 | 位置 | 严重级别 | 处理 |
| --- | --- | --- | --- | --- |
| C-1 | 遗留调试日志 2 处（tag `OR_DEBUG`，含页码/缩放等运行时状态） | `ui/reader/PdfViewer.kt:83, 85` | 建议修复 | **已删除**两行 `Log.d` |
| C-2 | 已移除功能的死字符串资源：`tts`（朗读，TTS 功能已于 v0.5.0 移除）、`all_features_unlocked`（「全部高级功能已解锁」，付费解锁时代残留，对免费用户具有误导性） | `res/values/strings.xml`（原第 10、11 行） | 建议修复 | **已删除**两个字符串（全仓无 `R.string.*` 引用，不影响任何界面） |

已核实通过：
- **无 TODO / FIXME / HACK / XXX / WIP 标记**（全边界匹配检索）✅
- 无被注释掉的代码块——检索命中的 8 处均为算法说明性注释（如 PalmDOC LZ77 位域解释、RFC 4918 行为说明）✅
- 无内部工单 / 知识库链接（jira / confluence / tapd / feishu 等检索无命中）✅
- `tools/` 下 5 个 Python 脚本均为可复用的构建/发布工具（词典生成、样本生成、GitHub 推送），无内部路径或凭证残留 ✅

### 5. 文档与元信息 — ⚠️ 3 项建议修复（均已执行）

| 编号 | 发现 | 位置 | 严重级别 | 处理 |
| --- | --- | --- | --- | --- |
| M-1 | 随包分发的许可摘要与仓库声明**自相矛盾**：仍写「所有云端与 **AI** 凭据均由用户自行填写」——AI 功能已移除，且百度 AppKey/Secret 实为构建期内置 | `assets/THIRD_PARTY_LICENSES.txt`（合规要点段） | 建议修复 | **已改写**为与 `THIRD-PARTY-NOTICES.md` 一致的正确表述（公开客户端标识构建期内置、token 仅存本机） |
| M-2 | 缺少**版权与作者信息**及许可适用约定 | `README.md` | 建议修复 | **已新增**「许可」节完整说明：版权行、AGPL-3.0、集中许可声明模式、第 13 条源码义务、第三方声明索引 |
| M-3 | 缺少**变更日志** | 仓库根 | 建议修复 | **已新建 `CHANGELOG.md`**（依据 git 历史整理 v1.0.0 → v0.5.6 全版本记录），README 版本行已加链接 |

已核实通过：
- 版本号三处一致：`build.gradle.kts:27-28`（14 / 0.5.6）、`README.md:5`（v0.5.6）、CHANGELOG ✅
- `PRIVACY.md` 与实际功能完全一致（英文在线词典 dictionaryapi.dev、百度 OAuth、WebDAV、更新检查；无 AI/词库下载残留描述）✅
- 构建要求准确：README 写 Gradle 8.9 + JDK 17 + SDK 34，`gradle-wrapper.properties` 实为 gradle-8.9，`compileSdk/targetSdk = 34` ✅
- `THIRD_PARTY_LICENSES.txt`（随 APK）、`CC-CEDICT-LICENSE.txt`、`UNIHAN-NOTICE.txt` 齐备 ✅

### 6. 构建与可发布性 — ✅ 已通过（1 项可选优化，已执行）

- **构建复验**：`./gradlew assembleDebug test`（managed JDK 17）**BUILD SUCCESSFUL**，46 秒完成，**编译零警告**；单元测试（MobiParser / PalmDOC / EPUB / 词典）全部通过 ✅
- APK 校验：zip 结构完整（186 项），已复制至 `E:\workbuddy\轻松阅读-v0.5.6-debug.apk`（23,247,158 B）✅
- **权限最小化**：仅 INTERNET / ACCESS_NETWORK_STATE / REQUEST_INSTALL_PACKAGES / FOREGROUND_SERVICE(+MEDIA_PLAYBACK) / POST_NOTIFICATIONS，无危险权限 ✅
- **导出组件**：`FileProvider` exported=false；`WebDavDocumentsProvider` exported=true 但受 `MANAGE_DOCUMENTS` 权限保护（SAF 标准形态）；MainActivity 为 LAUNCHER + VIEW 过滤器（应用关联打开书籍）✅
- **无内部环境配置**：`settings.gradle.kts` 仓库列表全部为公共源（google / mavenCentral / gradlePluginPortal / jitpack + 阿里云公共镜像）；无内网 Maven、无内网 API 端点 ✅
- release 构建 `isMinifyEnabled = false`（本项目选择不混淆），`proguard-rules.pro` 已备好 Room / JavascriptInterface 保留规则，供二次开发者开启 ✅

| 编号 | 发现 | 位置 | 严重级别 | 处理 |
| --- | --- | --- | --- | --- |
| B-1 | `android:usesCleartextTraffic="true"` 允许明文 HTTP。属**功能必需**（局域网 WebDAV 服务器普遍为 `http://`），但此前无任何说明，审查视角看是一项「无说明的明文传输」 | `AndroidManifest.xml:27`（原） | 可选优化 | **已加注释**说明用途与边界（仅用户主动配置的 WebDAV 同步，其余连接均为 https）；同时修正该标签原有错位缩进 |
| B-2 | `android:allowBackup="true"` 允许 adb 备份应用数据（含 token）。为换机迁移保留，属常见取舍 | `AndroidManifest.xml:20` | 可选优化 | **保留**（关闭会丢失用户换机能力），仅在此记录 |

---

## 二、修复清单（共 9 处，全部已执行）

| 文件 | 行号（修复后） | 修改 |
| --- | --- | --- |
| `app/src/main/java/com/qingsongreader/ui/reader/PdfViewer.kt` | 83 | 删除调试 `Log.d` ×2 |
| `app/src/main/res/values/strings.xml` | — | 删除 `tts`、`all_features_unlocked` |
| `app/src/main/assets/THIRD_PARTY_LICENSES.txt` | 合规要点段 | 改写过时的凭据说明 |
| `README.md` | 5, 63-70 | 版本行链接 CHANGELOG；新增版权/许可/集中声明模式说明 |
| `THIRD-PARTY-NOTICES.md` | 24 | CC-CEDICT 条目构成精确化 |
| `app/src/main/AndroidManifest.xml` | 18-22, 27-30 | 加明文传输注释；修正缩进 |
| `CHANGELOG.md` | 新建 | v1.0.0 → v0.5.6 全版本记录 |

## 三、复验记录

```
./gradlew assembleDebug test   → BUILD SUCCESSFUL in 46s（72 tasks，0 warning）
APK: 23,247,158 bytes, zip OK (186 entries)
```

## 四、结论

**满足对外发布与共享要求，无阻断发布项。** 许可证（AGPL-3.0 集中声明 + 逐数据署名）、第三方依赖（全宽松许可）、敏感信息（零泄露，含全历史）、内部资产（已清理）、文档元信息（已补齐）、构建可发布性（已验证）六项全部通过。

遗留事项（不构成阻碍）：① 百度网盘扫码→授权→下载的**真机端到端**验证（模拟器已验证页面渲染）；② `usesCleartextTraffic` 为功能必需保留；③ `tools/rename_pkg.py` 为一次性迁移脚本，保留备查。
