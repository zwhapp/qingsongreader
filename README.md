# 轻松阅读（qingsongreader）

安卓原生电子书阅读器。功能对标 Readest（开源 AGPL-3.0 项目），用 Kotlin + Jetpack Compose 重写，**本地不存在任何付费门禁**：主题、字体、排版、朗读、批注、统计、同步入口全部默认开放。

> 关于「收费功能全部开放」的说明见下文 [付费功能是怎么开放的](#付费功能是怎么开放的)，请先看这一节。

---

## 功能对照

| 能力 | Readest | 轻松阅读 | 备注 |
| --- | --- | --- | --- |
| EPUB（2/3） | ✅ | ✅ | 自研解析：container.xml → OPF → spine → nav/NCX 目录 |
| PDF | ✅ | ✅ | `android.graphics.pdf.PdfRenderer`（与 Readest 安卓端同一方案） |
| TXT / Markdown | ✅ | ✅ | Markdown 渲染标题、列表、引用、代码块、行内样式 |
| 分页 / 滚动两种阅读模式 | ✅ | ✅ | CSS column + JS 桥接，与 foliate-js 思路一致 |
| 高亮（多色）/ 笔记 / 书签 | ✅ | ✅ | 字符偏移定位，跨会话稳定 |
| 目录跳转 / 全文搜索 | ✅ | ✅ | 目录支持 EPUB3 nav 与 EPUB2 NCX |
| 字体、字号、行距、边距、对齐 | ✅ | ✅ | 实时生效 |
| 明亮 / 纸色 / 夜间主题 | ✅ | ✅ | |
| 朗读（TTS） | ✅（Plus 起不限时长） | ✅ 无限制 | 系统 TTS 引擎，无服务端、无配额 |
| AI 翻译 / 摘要 / 提问 | ✅（按档位限额） | ✅ 无应用侧限额 | 用户填自己的 API Key（OpenAI 兼容端点） |
| 云同步 | ✅（500MB / 5GB / 20GB 分档） | ✅ 无应用侧容量限制 | 用户自己的 WebDAV 服务器 |
| 阅读统计 | 🛠 建设中 | ✅ | 总时长、今日时长、按书目 |
| 批注导出 | ✅ | ✅ | Markdown 导出 / 剪贴板 |
| 词典查词 | ✅（内置源 + Yomitan 离线包） | ✅ | 内置汉英/英汉词典（11.4 万 + 4 万条）+ 汉字表，离线优先 |
| 网盘导入（百度 / 阿里） | ❌ | ✅ | 用自己的 AppKey 走官方开放平台授权，浏览网盘直接导入 |
| WebDAV 同步 | ❌ | ✅ | 自建，无容量限制 |
| 文件关联（用本应用打开） | ✅ | ✅ | epub/pdf/txt/md |
| MOBI / AZW3（KF8） | ✅ | ✅ | 自研 PDB/MOBI 解析，无压缩 + PalmDOC，按标题分章 |
| FB2 / CBZ | ✅ | ❌ | 未实现 |
| 平行阅读（双书对照） | ✅ | ❌ | 未实现 |
| OPDS / Calibre 接入 | ✅ | ❌ | 未实现 |
| KOReader 同步 | ✅ | ❌ | 未实现 |
| 词典离线包 / Yomitan | ✅ | ❌ | 未实现 |

---

## 付费功能是怎么开放的

Readest 的付费档位（`apps/readest-app/src/libs/payment/entitlements.ts`、`app/user/utils/plan.ts`）分两类：

**A. 客户端能力**（自定义主题/字体/排版、朗读入口、统计、导出等）
→ 轻松阅读 里这一类**完全没有门禁**。`features/FeatureGate.kt` 是唯一的开关来源，所有 `isEnabled()` 无条件返回 `true`，没有账号、没有订阅校验、没有 entitlement 请求。

**B. 服务端资源**（云存储 500MB/5GB/20GB、AI 翻译 10K/100K/500K 字符/天、AI 朗读时长、DeepL Pro 通道）
→ 这些不是软件功能，而是**官方服务器的付费资源**，用量由服务端按账号配额结算。轻松阅读 不会去伪造凭证或绕过它的订阅校验（那属于未经授权使用他人的付费服务）。取而代之的做法是「自带钥匙」：

* **同步**：填你自己的 WebDAV（Nextcloud / ownCloud / nginx-webdav…），容量就是你自己的盘，应用侧无任何限制；也可以一键导出 JSON 备份。
* **AI**：填你自己的 API Key 与 OpenAI 兼容端点（官方 API、OpenRouter、Ollama、自建网关都行），用量记在你自己的账号上。
* **朗读**：直接用系统 TTS 引擎，不需要任何服务器。

这样得到的效果是：Readest 里需要订阅才解锁的**功能**，在这里全部开放；而原本要按流量/容量付费的**服务**，换成由你自己提供、没有中间商。

---

## 架构

```
app/src/main/java/com/qingsongreader/
├─ reader/
│  ├─ epub/EpubParser.kt      EPUB 容器解压 + OPF/spine/目录解析（命名空间无关）
│  ├─ mobi/MobiParser.kt      MOBI / AZW3：PDB 记录、MOBI+EXTH 头、PalmDOC 解压、KF8 流重组
│  ├─ ReaderContent.kt        EPUB / 文本 / PDF 的统一内容模型
│  ├─ HtmlFactory.kt          章节 HTML 生成（排版 CSS、主题、Markdown 子集）
│  └─ ReaderScripts.kt        注入 WebView 的 JS：分页、进度、选区、高亮、搜索
├─ ui/
│  ├─ library/                书架：导入、封面网格、进度、删除
│  ├─ reader/                 阅读器：WebView 控制器、目录/排版/批注/搜索/AI 面板、PDF 视图
│  ├─ settings/               排版、AI 配置、WebDAV 同步、功能状态
│  └─ stats/                  阅读统计
├─ data/
│  ├─ db/Entities.kt          Room：books / highlights / bookmarks / sessions
│  ├─ prefs/ReaderSettings.kt DataStore：排版、TTS、AI、WebDAV 配置
│  └─ repo/BookRepository.kt  导入、解析、打开、全文搜索
├─ ai/AiClient.kt             OpenAI 兼容聊天接口（翻译/摘要/提问/释义）
├─ dict/
│  ├─ DictionaryRepository.kt 词典：内置字表 / 离线词库 / 在线英文词典 / AI 释义 / 缓存
│  └─ DictionaryPacks.kt      词库中心：第三方开源数据包下载（含许可标注）
├─ cloud/                     网盘：CloudManager（凭据/token）+ 百度网盘、阿里云盘客户端
├─ ui/cloud/                  授权页（WebView 拦截 code）与网盘文件浏览器
├─ sync/                      WebDavSync + SyncManager（JSON 全量同步）
├─ tts/TtsController.kt       系统 TTS 朗读
└─ features/FeatureGate.kt    功能开关：全部返回 true
```

阅读渲染要点：每章生成一份带 `<style>` 的 HTML，用 `loadDataWithBaseURL` 加载（图片/CSS 相对路径自动解析）；分页模式下 `body` 用 CSS 多列，一页等于一列，翻页即横向滚动一个「列宽 + 间距」；高亮按**字符偏移**寻址（包 `<span>` 不改变总字符数），因此重新渲染后可精确还原。

---

## 构建

```bash
# 方式一：命令行
cd 轻松阅读
./gradlew :app:assembleDebug      # 产出 app/build/outputs/apk/debug/app-debug.apk

# 方式二：Android Studio
# File → Open → 选择 轻松阅读 目录 → Run
```

要求：JDK 17、Android SDK 34（AGP 8.6.1 / Kotlin 2.0.21 / Gradle 8.9）。首次构建会联网下载依赖。

---

## 许可证

AGPL-3.0，与参考项目 Readest 的许可证保持一致。功能设计参照了 Readest 的公开源码与产品形态，实现为独立编写，未包含 Readest 的商标、图标或品牌素材。若你分发修改版，AGPL 要求同样开源你的改动。

第三方依赖：Jsoup（MIT）、OkHttp（Apache-2.0）、Coil（Apache-2.0）、AndroidX（Apache-2.0）。

**词典数据**（与应用代码分开）：

* 汉英/英汉词条改编自 **CC-CEDICT**（https://cc-cedict.org，MDBG），以 **CC BY-SA 4.0** 许可发布；本仓库以 TSV 形式随附同一许可的转换版本，来源与许可在应用「设置 → 词典」中同步标注。
* 汉字表由 **Unicode® Unihan Database** 生成，受 Unicode 许可约束。
* 可选数据包来自 pwxcoo/chinese-xinhua（MIT），不由本仓库分发，需用户自行下载。
* 版权状态不明的词典正文（如《新华字典》《现代汉语词典》的释义）不会内置，也不由本仓库分发。

重新生成内置词库（可复现）：

```bash
curl -O https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
python tools/build_hanzi_basic.py Unihan.zip
curl -o all.js https://raw.githubusercontent.com/edvardsr/cc-cedict/main/data/all.js
python tools/build_cedict.py all.js
```

---

## 词典

### 内置（离线可用，无需联网）

| 词库 | 内容 | 条目 | 许可 |
| --- | --- | --- | --- |
| `assets/dict/cedict.tsv` | **汉英**：中文词 + 拼音（声调符号）+ 英文释义；**英汉**：英文词 → 中文（含拼音） | 中文 114,268 / 英文 40,474 | CC BY-SA 4.0 · CC-CEDICT / MDBG |
| `assets/dict/hanzi_basic.json` | 汉字表：拼音、部首、笔画 | 6,739 | Unicode 许可 · Unicode® Unihan |

首次启动后台导入一次（设置里可「重新导入内置词库」）。中文词只收 ≤4 字的常用词，英文条目只从**整条释义就是英文词/短语**的条目反向生成（避免 "apple → 喉结" 这类噪声）。

### 可选下载（词库中心，按需，界面标注来源与许可）

| 数据包 | 内容 | 大小 | 许可 |
| --- | --- | --- | --- |
| 现代汉语词语 | 约 30 万条词语释义 | 18 MB | MIT · pwxcoo/chinese-xinhua |
| 成语词典 | 拼音、释义、出处、例句 | 10 MB | MIT · pwxcoo/chinese-xinhua |
| 歇后语 | 歇后语 | 1.2 MB | MIT · pwxcoo/chinese-xinhua |

> 说明：版权状态不明的词典正文（如《新华字典》《现代汉语词典》的释义）不会打包进本仓库，改由「词库中心」在你确认许可后自行下载。也支持导入自己的 JSON / CSV 词库。

**查询顺序**：离线词库（内置 + 导入 + 下载）→ 免费在线英文词典（dictionaryapi.dev，无需 Key）→ AI 释义（你自己的 Key）。在线结果写入 Room 缓存，下次断网也能查到。

## 网盘导入

设置 → 网盘导入，填入你自己在开放平台申请的凭据，点「连接」后在内置页面登录授权：

| 网盘 | 申请地址 | 需要填 | 授权方式 |
| --- | --- | --- | --- |
| 百度网盘 | https://pan.baidu.com/union | AppKey、SecretKey | 授权后页面给出授权码，粘贴或自动捕获 |
| 阿里云盘 | https://open.alipan.com | App ID、App Secret | 自动跳转捕获 `code` |

授权后在书架点☁或设置里点「浏览文件」，即可浏览网盘目录、选择 epub/pdf/mobi/azw3/txt/md 直接下载导入。凭据与 token 只存在本机 `SharedPreferences`，不经过任何第三方服务器；下载用的是你自己的 access token。

已知限制：百度网盘用 oob 模式，需要手动粘贴授权码（也支持自定义回调后自动捕获）；阿里云盘回调地址写死为 `https://openreader.app/oauth/aliyun`，需要在你的应用配置里填同一个地址。

选中文字后工具条点「查词」（或在设置里打开「选中即查词」，选中即弹释义），释义面板可朗读单词、复制释义；选择区还有「网页」按钮可跳外部词典。

自建词库支持这些格式，在设置里导入即可：

```json
[{"word": "apple", "phonetic": "ˈæpl", "definition": "苹果；苹果树"}]
```

CSV/TSV：`apple,ˈæpl,苹果` 或 `apple\t苹果`；也支持 `{"apple": "苹果"}` 映射格式，以及含 `pinyin` / `explanation` 字段的第三方数据包（HTML 会被清洗成纯文本）。

## MOBI / AZW3 支持

两者都是 PDB 容器，走同一套解析（`reader/mobi/MobiParser.kt`）：

1. 读 PDB 记录表，记录 0 取 PalmDOC + MOBI + EXTH 头（标题 EXTH 503、作者 EXTH 100、KF8 边界 EXTH 121）。
2. 按 `compression` 解压文本记录：**1 = 无压缩**、**2 = PalmDOC**（14 位「距离 11 位 + 长度 3 位」对）。
3. AZW3/KF8：从 KF8 边界之后的记录里取 XHTML 流，按「是否像标记文本」过滤掉夹杂的字体/图片资源，并去掉记录尾部的 extra data。
4. 解码（UTF-8 / windows-1252 / 1251 / Shift_JIS）后用 Jsoup 清洗，按 `<h1>/<h2>/<h3>` 切章并生成目录。
5. 解析结果缓存到 `filesDir/extracted/<id>/mobi/`，下次打开秒开。

**限制**：使用 **HUFF/CDIC 压缩（17480）** 的书暂不支持——这类文件会给出明确提示而不是吐乱码，可用 Calibre 转成 EPUB 再导入。KF8 的复杂排版（精确 FDST/DATP 还原、内嵌字体、图片）不保证还原，按文本流重建；FB2 / CBZ 未实现。

## 已知限制

* 只支持 EPUB / PDF / TXT / Markdown / MOBI / AZW3；FB2、CBZ 未实现。
* MOBI/AZW3 见上一节的压缩与排版限制。
* 内置中文词只收 ≤4 字的词（不含长句/专名），英文条目只收有精确直译的；生僻词、短语仍要靠在线或数据包补充。
* 在线英文词典依赖 dictionaryapi.dev 可用性。
* 网盘导入依赖你在开放平台申请的凭据，且受各家 API 配额限制。
* PDF 只提供逐页渲染与跳转，未解析 PDF 目录大纲。
* 未实现平行阅读、OPDS、KOReader 同步、离线词典包。
* 进度在单个 WebView 内按「章节 + 页」记录，跨设备的精确同步依赖 WebDAV 手动/自动上传。

## 许可证与源码 (License & Source)

- 轻松阅读 以 **GNU Affero General Public License v3.0 (AGPL-3.0)** 发布。
  完整许可证见 [`LICENSE`](./LICENSE)。
- 本项目是对 Readest（AGPL-3.0）功能的重写实现，遵循 AGPL-3.0 的「同源共享」要求。
- 第三方组件与内置数据的许可与署名见 [`THIRD-PARTY-NOTICES.md`](./THIRD-PARTY-NOTICES.md)。
- 隐私说明见 [`PRIVACY.md`](./PRIVACY.md)。

> **分发须知（AGPL-3.0 第 13 条）**：若你向他人提供本应用的二进制（APK），
> 必须同时向接收者提供**完整的对应源码**（可指向本项目的代码仓库，或随包提供源码归档）。
> 内置数据集 CC-CEDICT 以 CC BY-SA 4.0 提供，其改编亦须以相同许可共享。

### 合规要点
- 应用**不含任何硬编码密钥 / 凭证**；百度 / 阿里 / AI 的凭据由用户自行填写，仅存本机。
- 原 Readest 的付费能力在服务端，本应用以「用户自带 Key / 自建 WebDAV」等价提供，**未逆向或绕过任何服务端鉴权 / 数字版权管理**。
- 不打包受版权保护的词典正文（如《新华字典》《现代汉语词典》释义）；如需使用，由「词库中心」在你确认许可后自行下载（部分来自 MIT 许可的 pwxcoo/chinese-xinhua）。

---

## 项目地址
- 仓库：https://github.com/zwhapp/qingsongreader
- 问题反馈：https://github.com/zwhapp/qingsongreader/issues
- 首个发布版本：v1.0.0（APK 请到 Releases 页面下载）

## v1.1.0 更新

- **版本号显示**：书架与设置页展示当前版本（如 v1.1.0），便于确认已装版本。
- **自动更新**：设置页新增「检查更新 / 启动时自动检查」，基于 GitHub Releases 比对语义化版本号，发现新版本可一键下载 APK 并通过系统安装器升级（无需电脑）。
- **墨水屏 / Kindle 适配**：新增「墨水屏模式」——纯黑底白字、去除彩色与动画，减少电纸书的残影与刷新；可叠加「衬线字体」，适配 Kindle Fire、文石、掌阅等 Android 电纸书。

> 更新通道指向公开仓库 `zwhapp/qingsongreader` 的 Releases；APK 下载自 GitHub CDN，本应用不代理任何付费服务。
