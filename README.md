# KeyMapper 按键映射（自定义屏幕按键映射工具）

基于 Android 原生（Kotlin）实现的「物理键盘 → 屏幕坐标触摸」映射工具。通过无障碍服务全局监听物理键盘，
把按键映射为屏幕坐标上的点击 / 长按 / 滑动 / 双指缩放，并支持组合键、宏指令、多配置管理与测试模式。

- **包名**：`com.keymapper`
- **语言**：Kotlin
- **最低 / 目标 SDK**：minSdk 24（Android 7.0）/ targetSdk 34
- **技术栈**：ViewBinding + Material + Kotlin 协程 + kotlinx-serialization-json + Koin + 无障碍 `dispatchGesture`

---

## 一、功能一览

| 能力 | 说明 |
| --- | --- |
| 全局键盘监听 | `KeyMapperAccessibilityService.onKeyEvent` 捕获物理键（HOME/BACK 等系统保留键透传） |
| 手势注入 | 点击 / 长按 / 滑动 / 双指缩放，经 `dispatchGesture` 注入系统触摸 |
| 悬浮窗 | `TYPE_APPLICATION_OVERLAY` 挂载悬浮球 + 操作面板 + 映射标记层 |
| 映射模式 | 全屏半透明覆盖层 + 十字准星，点屏采集坐标 → 按键绑定 |
| 组合键 | `Ctrl/Shift/Alt + 主键`，归一化左右修饰键，状态机优先匹配组合 |
| 宏指令 | 50ms 粒度时序回放，支持 `holdMs` / `repeat` |
| 多配置 | 命名保存、载入（覆盖）、删除、下拉/自动按前台应用切换 |
| 测试模式 | 命中绑定只高亮不注入（P1-5） |
| 保活 | `KeepAliveService` 前台通知 + 自重启，与无障碍服务闭环 |
| 导入导出 | SAF 导出 / 导入 JSON 配置 |
| 低电量降频 | 电量低于 20% 时降低悬浮窗刷新率（P2-3） |

---

## 二、目录结构（节选）

```
KeyMapper/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/keymapper/
│       │   ├── MainApplication.kt            # Koin 初始化
│       │   ├── MainActivity.kt               # 入口 / 权限检查
│       │   ├── di/AppModule.kt               # Koin 模块
│       │   ├── domain/model/                 # KeyBinding / MappingConfig / Macro / MacroStep / GestureAction
│       │   ├── data/                         # JsonConfigStore / ConfigRepository
│       │   ├── engine/                        # MappingEngine / MacroPlayer / GestureInjector
│       │   ├── accessibility/                 # KeyMapperAccessibilityService
│       │   ├── service/                       # KeepAliveService / FloatingWindowService
│       │   ├── ui/
│       │   │   ├── mapping/                   # MappingOverlayActivity / CrosshairView
│       │   │   ├── config/                    # ConfigPanelActivity / ConfigListAdapter
│       │   │   ├── settings/                  # SettingsActivity
│       │   │   ├── floating/                  # FloatingButtonView / ControlPanelView / MarkerLayerView
│       │   │   └── debug/                     # DebugHighlightView
│       │   └── util/                          # Constants / KeyEventUtil / ResolutionScaler / PermissionHelper
│       └── res/                              # 布局 / 字符串 / 颜色 / 主题 / 图标 / 无障碍与保活配置
└── docs/                                     # ARCHITECTURE.md / PRD.md / 类图 / 时序图
```

---

## 三、构建与运行（Android Studio）

1. 用 **Android Studio Hedgehog 或更高** 打开 `KeyMapper/` 工程（Gradle 8.2 + AGP 8.2.2）。
2. 连接 Android 7.0+ 真机（**需要物理键盘**才能体验按键映射；无障碍手势注入在模拟器上受限）。
3. 点击 **Run**（Shift+F10）安装到设备。
4. 首次进入会显示权限状态：
   - **无障碍服务**：设置 → 无障碍 → KeyMapper 按键映射服务 → 开启。
   - **悬浮窗（SYSTEM_ALERT_WINDOW）**：在设置页点击「去开启」授权。
   - **通知权限**（Android 13+）：在设置页授权，用于保活通知。
5. 授权后「启动悬浮窗」出现悬浮球，单击展开面板：
   - **开始映射**：进入覆盖层，点屏选坐标 → 按键盘键完成绑定 → 命名保存。
   - **加载配置 / 标记**：切换或显隐映射标记。
   - **设置**：权限引导、低电量降频、自动切换开关。

> 注意：无障碍服务未开启时，按键映射不会触发；服务被系统回收后会经保活闭环自动重启（前提是开关仍开启）。

---

## 三-B、构建 APK（命令行 / 无 Android Studio）

适用场景：你已有 JDK 17+ 与 Android SDK（platform-34、build-tools、platform-tools），但不想走 Android Studio 图形界面。

**前置条件**
- JDK 17 及以上（本机自带 OpenJDK 21，满足要求）。
- Android SDK，且至少安装：
  - Android 14 (API 34) 的 **SDK Platform**
  - **Android SDK Build-Tools**（任意 34.x）
  - **Android SDK Platform-Tools**（含 `adb`）
- SDK 路径（二选一）：
  - 设置环境变量 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）指向 SDK 根目录；或
  - 复制 `local.properties.example` 为 `local.properties`，填写 `sdk.dir=...`（Windows 路径反斜杠需写成双反斜杠 `\\`）。

**出包**
```bash
# 方式 A：本机已安装 Gradle（最直接，无需 wrapper jar）
gradle assembleDebug

# 方式 B：使用 Gradle Wrapper（需 gradle-wrapper.jar 存在）
#   本仓库在生成环境因无外网未附带 gradle-wrapper.jar，
#   首次用 Android Studio 打开工程会自动生成；或在有 Gradle 的机器执行：
#       gradle wrapper --gradle-version 8.2
#   之后再运行：
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows
```

**产物与安装**
- Debug APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`
- Debug 包由 Android Gradle Plugin **自动用调试签名（debug.keystore）签名**，无需手动配置 keystore。
- 安装到已连接设备：
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- 首次运行仍需按「三、构建与运行」完成无障碍服务 / 悬浮窗 / 通知三项授权。

> 提示：若 `gradle` 命令找不到，请先安装 Gradle 8.2+；也可直接用 Android Studio 打开工程 → Build → Build Bundle(s) / APK(s) → Build APK，由 AS 自带 Gradle 完成全部步骤（包括补齐 wrapper）。

---

## 四、坐标与分辨率约定

- 采集坐标以覆盖层 `MotionEvent` 的**原始绝对像素**为准（最可靠）。
- 保存时记录源分辨率 `screenWidth/screenHeight`；播放前经 `ResolutionScaler` 按
  `WindowManager.getDefaultDisplay().getRealSize()` 等比缩放，规避导航栏 / 刘海偏移。

---

## 五、测试

- **单元测试**（`app/src/test`，无需设备）：
  - `ResolutionScalerTest` 分辨率缩放公式
  - `KeyBindingSerializationTest` JSON 序列化与兼容字段
  - `MappingEngineTest` 单键 / 组合键状态机
  - `MacroPlayerTest` 宏步骤时序 / holdMs / repeat
- **仪器测试**（`app/src/androidTest`，需设备）：
  - `ConfigRepositoryInstrumentedTest` 配置保存 / 加载 / 删除 / 导入导出

在 Android Studio 中右键对应测试文件 → Run，或用 Gradle：`gradlew testDebugUnitTest` / `gradlew connectedDebugAndroidTest`。

---

## 六、已知限制

- 系统保留键（HOME / BACK / 音量等）无法拦截，按设计透传。
- 滚轮映射依赖鼠标设备（`InputDevice.SOURCE_MOUSE`），无鼠标设备时设置页隐藏该提示。
- 本仓库已补齐构建脚本（根 `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`，模块 `app/build.gradle.kts` / `proguard-rules.pro`，以及 `gradlew` / `gradlew.bat` 启动器与 `local.properties.example`）。工程已通过编译面静态自审：Manifest 对类/资源/权限的引用、theme 对 color 的引用、布局对 string/color/drawable 的引用均一致。
- **未经本机真编译**：生成环境无 Android SDK 且无外网，未能在本机跑 `assembleDebug`，故首次在你机器上构建仍可能暴露少量 Kotlin 编译问题（如个别未使用 import、类型推断）。遇到编译报错时按 Build 输出逐项修正即可，工程结构无需改动。
- `gradle-wrapper.jar` 因生成环境无外网未随仓库提供；用 Android Studio 打开会自动补齐，或在有 Gradle 的机器执行 `gradle wrapper --gradle-version 8.2`。

---

## 七、已适配机型（定向适配）

以下机型已做针对性适配，开箱即用，**无需额外代码改动**：

| 机型 | 系统 | 适配要点 |
| --- | --- | --- |
| **一加 13（OnePlus 13）** | ColorOS 15 / Android 15 | 开机/更新后自启 `BootReceiver`；设置页「ColorOS 保活设置」卡片一键关闭电池优化 + 自启动/后台运行/多任务锁定引导；通知权限运行时申请 |
| **一加 Pad 3（OnePlus Pad 3）** | ColorOS 15 / Android 15 | 与一加 13 同平台，**适配完全通用**：上述开机自启、保活引导、电池优化、通知权限均适用，无需额外修改 |

**通用前提（所有机型一致）**：
- 必须**外接物理键盘**（蓝牙 / OTG / 远程键鼠），纯触屏手机无键可映射。
- 首次运行需手动开启三项权限：无障碍服务、悬浮窗（`SYSTEM_ALERT_WINDOW`）、通知（Android 13+）。
- ColorOS / 国产 ROM 后台管理激进，请按设置页引导关闭电池优化并允许自启动，否则服务可能被回收导致按键暂时失效。
- 坐标基于录制时的真实屏幕像素 + `ResolutionScaler` 缩放；旋转屏幕、分屏、折叠屏会改变窗口尺寸，可能导致标记/注入点偏移，建议在目标窗口形态下重新录制。
