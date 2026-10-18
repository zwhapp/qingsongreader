# 隐私说明 (PRIVACY)

OpenReader 在**本地**处理你的书库，不向任何第三方上传你的书籍内容。

## INTERNET 权限用途
- 词典在线补全（dictionaryapi.dev，免费、无需 Key）
- 词库中心按需下载可选词包
- AI 翻译 / 摘要 / 提问（使用**你自己的** API Key 与端点）
- 百度网盘 / 阿里云盘开放平台授权

## 凭据存储
百度 / 阿里网盘的 AppKey、Secret、token，以及 AI API Key、WebDAV 账号密码，
**仅存储于本机** DataStore / SharedPreferences，不经过任何中间服务器；
网盘文件下载使用你自己的 access token。

## 我们不收集
- 不收集、不出售任何个人信息；
- 不含任何 analytics / 广告 / 崩溃上报 SDK；
- 不上传你的书架、笔记、阅读进度。

## 你的控制
可在系统「设置 → 应用 → OpenReader」中撤销存储权限、清除应用数据，
或删除 `filesDir` 下的书籍与缓存。
