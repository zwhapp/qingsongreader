# OpenReader — 第三方组件与数据许可声明 (THIRD-PARTY NOTICES)

OpenReader 是一个自由软件，基于 **GNU Affero General Public License v3.0 (AGPL-3.0)**
发布。完整许可证文本见同目录 `LICENSE`。

本项目是对 Readest（开源、AGPL-3.0）功能的重写实现。OpenReader 的源码同样以
AGPL-3.0 提供；如你分发本应用的二进制（APK），须按 AGPL-3.0 第 13 条向接收者提供
**完整的对应源码**（可通过本项目的代码仓库获取）。

---

## 一、直接衍生的上游项目

| 项目 | 许可证 | 说明 |
| --- | --- | --- |
| [Readest](https://github.com/readest/readest) | AGPL-3.0 | 功能对标对象；本应用为其功能重写，非直接复制其代码 |

---

## 二、随 APK 分发的 bundled 数据

| 数据 | 许可证 | 来源 / 署名 |
| --- | --- | --- |
| CC-CEDICT 汉英/英汉词典 (`assets/dict/cedict.tsv`，约 15.5 万条，其中约 11.4 万中文词条 + 约 4 万英文词条反向索引) | CC BY-SA 4.0 | CC-CEDICT 项目（维护者：various，原站 mdbg.net）。完整许可见 `assets/dict/CC-CEDICT-LICENSE.txt` |
| Unihan 汉字表 (`assets/dict/hanzi_basic.json`, 6739 字) | Unicode Data Files License | 派生自 Unicode® Unihan 数据库（© Unicode, Inc.）。仅含部首/笔画等公开信息，不含任何版权词典（如《新华字典》）的释义内容 |

**CC-CEDICT 署名要求（CC BY-SA 4.0）**：
- 本应用内置 CC-CEDICT 词典数据，署名 "CC-CEDICT"。
- 本应用本身基于 CC-CEDICT 数据所做的改编，以相同方式共享（CC BY-SA 4.0）提供。

---

## 三、第三方开源库（随 APK 分发）

| 库 | 版本 | 许可证 | 版权方 |
| --- | --- | --- | --- |
| AndroidX Jetpack (Compose UI / Material3 / Activity / Navigation / Lifecycle / Room / DataStore) | BOM 2024.10.01 | Apache-2.0 | Google LLC |
| Material Icons Extended | — | Apache-2.0 | Google LLC |
| jsoup | 1.18.1 | MIT | Jonathan Hedley |
| OkHttp | 4.12.0 | Apache-2.0 | Square, Inc. |
| Coil | 2.6.0 | Apache-2.0 | Coil 贡献者 |
| Kotlin / kotlinx-coroutines | 1.8.1 / 2.0.21 | Apache-2.0 | JetBrains s.r.o. |

以上 Apache-2.0 / MIT 组件均与 AGPL-3.0 兼容，其原始许可证文本可在各自上游仓库获取。
Apache-2.0 要求保留 NOTICE；本文件即为其聚合 NOTICE。

---

## 四、合规要点说明

- **无硬编码私密密钥**：百度网盘开放平台的 AppKey / Secret 作为公开客户端标识在构建时内置于安装包（OAuth 公开客户端的常规做法，等同于网页登录的 client_id），并非私密密钥；用户的 access / refresh token 仅存储于本机 DataStore。应用不含任何用户私密密钥或后端服务。
- **无付费门禁绕过 / 无 DRM 破解**：原 Readest 的付费能力在服务端，本应用以
  「用户自带 Key / 自建 WebDAV」方式提供等价能力，未逆向或绕过任何服务端鉴权或数字版权管理。
- **商标**：应用名称 OpenReader（发行名「轻松阅读」），未使用 Readest 商标或标识。
- **隐私**：应用声明 `INTERNET` 权限用于英文在线词典、百度网盘授权、你主动配置的
  WebDAV 同步与应用更新检查；不收集、不上传用户书库内容。详见 `PRIVACY.md`。
