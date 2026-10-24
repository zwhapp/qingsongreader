# 电纸书（墨水屏）适配说明 — 目标机型 Bigme B751C

本文说明轻松阅读对墨水屏电纸书设备的适配策略、按键事件处理逻辑、显示优化方案，
以及在现有架构中的落点。适用于 v0.5.7+。

## 目标机型参数（Bigme B751C）

| 项目 | 参数 | 对适配的影响 |
| --- | --- | --- |
| 屏幕 | 7″ E Ink Kaleido 3 彩色 | 彩色内容统一转灰度高对比；界面用纯黑白 |
| 分辨率 | 黑白 1264×1680（300 PPI）/ 彩色 632×840（150 PPI） | 正文走黑白 300 PPI 渲染，笔画清晰；图片按 150 PPI 彩色层显示，需加深对比 |
| 系统 | Android 11 | 标准 Android KeyEvent，无私有 SDK 依赖 |
| 实体键 | 右侧物理翻页键（上/下两键） | 上键 = 上一页、下键 = 下一页（中文阅读习惯） |
| 刷新 | 系统级 5 档刷新模式 + xRapid 快刷 + 自动去残影 | 应用侧配合：减少瞬态动画，让系统去残影机制工作在静态页面上 |

## 一、适配策略总纲

墨水屏的两大痛点是**残影（ghosting）**与**刷新慢**，根源都是「瞬态像素变化」。
因此适配原则只有一条：**静态页面 + 离散翻页**——

1. 内容层：纯白底 + 纯黑字，无阴影、无渐变、无彩色装饰；
2. 交互层：一切瞬态动画（水波纹、overscroll 光晕、窗口转场、平滑滚动）全部关闭；
3. 翻页层：物理键驱动离散翻页（一按一页），替代连续惯性滚动。

## 二、按键事件处理逻辑

### 架构落点

```
MainActivity.dispatchKeyEvent()          ← 唯一入口（音量键必须在此拦截，
        │                                   否则直接进系统音量面板）
        ▼
ReaderKeyDispatcher.dispatch(event)      ← 单例分发器（ui/reader/ReaderKeyDispatcher.kt）
        │  仅当 ReaderScreen 可见时注册了回调才消费事件
        ▼
ReaderScreen 注册的 turnPage(delta) 回调  ← 复用既有翻页核心
        ├── PAGED 模式  → WebView 多栏列滚动（controller.goToPage）
        ├── SCROLLED 模式 → 整屏滚动（scrollTop += clientHeight*0.9）
        └── PDF        → viewModel.goToPdfPage
```

- **注册/注销**：`ReaderScreen` 在 `DisposableEffect` 中调用
  `ReaderKeyDispatcher.register(turnPage, volume, pageKeys)`，离开阅读页自动 `unregister()`；
  回调经 `rememberUpdatedState` 包装，状态变化无需重新注册。
- **为什么放 MainActivity**：音量键不经过 Compose 层，Activity 不消费就会打开系统音量面板；
  `dispatchKeyEvent` 是唯一可靠的拦截点。

### 键值映射（上 = 上一页，下 = 下一页）

| 键值 | 动作 | 受哪个开关控制 |
| --- | --- | --- |
| `KEYCODE_VOLUME_UP` (24) | 上一页 | `volumeKeysTurnPages`（默认开） |
| `KEYCODE_VOLUME_DOWN` (25) | 下一页 | `volumeKeysTurnPages` |
| `KEYCODE_PAGE_UP` (92) | 上一页 | `pageKeysTurnPages`（默认开，Bigme 翻页键多映射为此） |
| `KEYCODE_PAGE_DOWN` (93) | 下一页 | `pageKeysTurnPages` |
| `KEYCODE_DPAD_UP` (19) / `LEFT` (21) | 上一页 | `pageKeysTurnPages` |
| `KEYCODE_DPAD_DOWN` (20) / `RIGHT` (22) | 下一页 | `pageKeysTurnPages` |

### 事件处理细节

1. **只在 ACTION_DOWN 且 `repeatCount == 0` 时翻页**：长按产生的重复事件被
   消费但不动作——墨水屏刷新慢，连翻会造成残影堆积，也让误触长按不跳页。
2. **DOWN 与 UP 都消费**（返回 true）：确保音量键完全不漏到系统。
3. **开关关闭时返回 false**：事件走默认路径，音量键恢复系统音量功能。
4. **阅读页外不拦截**：书架/设置里按键行为与系统一致。
5. **章节边界续翻**：`turnPage` 已有逻辑——章末翻下一页自动进入下一章第一章末页，
   回翻落在上一章最后一页（`jumpToChapterEnd`）。
6. **设置入口**：主设置「电纸书适配」分区 + 阅读内「排版」面板（墨水屏 / 翻页键 / 音量键三个 chip）。

## 三、显示优化方案

### 3.1 内容层（reader/HtmlFactory.kt，`body.eink` 作用域）

| 措施 | CSS | 目的 |
| --- | --- | --- |
| 纯黑白 | `background:#FFF; color:#000` | 电泳屏灰阶过渡是残影主因，二值化最稳 |
| 衬线体 + 字重 500 | `font-family: serif; font-weight:500` | 300 PPI 下笔画更实，小字号不发虚 |
| 去装饰 | `* { box-shadow:none; text-shadow:none }` | 阴影是典型残影源 |
| 图片处理 | `img,svg { filter:grayscale(100%) contrast(1.12) }` | 彩色图在 150 PPI 彩色层发灰，转灰 + 提对比更清晰 |
| 代码块 | 去圆角、浅灰底 + 1px 边框 | 灰底改边框表达，减少大面积灰块 |
| 搜索高亮 | `background:#000; color:#FFF`（反色） | 彩色高亮在黑白屏上是浅灰，反色对比最强 |
| 即时滚动 | `html { scroll-behavior:auto }` + `overscroll-behavior:none` | 平滑滚动 = 连续重绘 = 残影 |

### 3.2 应用层（Compose）

| 措施 | 落点 |
| --- | --- |
| 纯黑白主题 | `ui/theme/Theme.kt` 的 `EinkColors` 覆盖 M3 全部颜色角色（surface/primary/error/outline 全系灰阶） |
| 禁水波纹 | `LocalRippleConfiguration provides null`（material3 1.3 API，全局生效） |
| 禁 overscroll | `LocalOverscrollConfiguration provides null`（含 WebView 侧 `OVER_SCROLL_NEVER`） |
| 关窗口动画 | `MainActivity` 中 `window.attributes.windowAnimations = 0`（退出墨水屏恢复） |
| 工具栏去投影 | ReaderScreen 底栏 `tonalElevation = 0` |

### 3.3 不做的事（及原因）

- **不调用厂商刷新 SDK**：Bigme 未公开统一 SDK，且系统级刷新模式（清晰/快速/极速）
  由用户在系统设置选择更稳妥；应用侧只需保证页面静态。
- **不强制分页**：滚动模式仍可用（物理键已映射为整屏滚动），尊重用户习惯。
- **不改反色/夜间**：墨水屏白底黑字即为最佳对比，不做暗色变体。

## 四、设备识别与引导

`ui/reader/EinkSupport.kt`：按 `Build.MANUFACTURER/BRAND/MODEL/DEVICE` 匹配已知电纸书厂商
（bigme/boox/onyx/ireader/kindle/hisense/meebook/moan/hyread/kobo/pocketbook）。
识别到设备时，设置页显示提示（建议开启墨水屏模式 + 系统刷新选「高清/清晰」档）。
**识别只用于提示，不自动改任何设置**——所有功能在所有设备上都可手动开启。

## 五、验证记录（模拟器，2026-10-24）

- `PAGE_DOWN`(93)：第 1 页 → 第 2 页 ✅
- `VOLUME_DOWN`(25)：第 2 页 → 跨章进入第二章，且**未弹出系统音量面板** ✅
- `PAGE_UP`(92)：第二章 → 回退至第一章最后一页（进度续接）✅
- 墨水屏模式：排版面板开启后即时切换纯黑白 + 衬线，重启应用后保持 ✅
- 墨水屏下物理键翻页正常 ✅
