# 隐私说明 (PRIVACY)

OpenReader 在**本地**处理你的书库，不向任何第三方上传你的书籍内容。

## INTERNET 权限用途
- 英文单词在线释义（dictionaryapi.dev，免费、无需 Key；仅在你查英文词时发起，结果会缓存到本机，之后离线也能看）
- 百度网盘开放平台授权（OAuth 登录后导入你自己的网盘书籍）
- 你主动配置的 WebDAV 同步，以及 GitHub 应用更新检查

## 凭据存储
- 百度网盘开放平台的 **AppKey / Secret 是公开客户端标识**，在构建时内置于安装包（等同于网页登录所用的 client_id，并非私密密钥），仅用于发起 OAuth 授权流程，不会外发到任何中间服务器。
- 你的百度网盘 **access_token / refresh_token**，以及 WebDAV 账号与密码，**仅存储于本机** DataStore，不经过任何中间服务器；网盘文件下载使用你自己的 access token。

## 我们不收集
- 不收集、不出售任何个人信息；
- 不含任何 analytics / 广告 / 崩溃上报 SDK；
- 不上传你的书架、笔记、阅读进度。

## 你的控制
可在系统「设置 → 应用 → OpenReader」中撤销存储权限、清除应用数据，
或删除 `filesDir` 下的书籍与缓存。
