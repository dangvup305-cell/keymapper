# QA 验证报告 — KeyMapper（com.keymapper）

> 验证人：严过关（QA Engineer）｜ 日期：2026-08-18｜ 方式：静态 + 逻辑验证（本机无法真编译）

## 一、验证范围

- **静态一致性审查**：跨文件调用/签名自洽、广播 Action 收发一致、JSON 序列化模型与类图一致、`ignoreUnknownKeys` 设置、潜在编译级错误（未导入类 / 拼写 / 不存在的方法或属性 / 枚举不匹配）。
- **测试逻辑正确性审查**：5 个测试文件逐条断言核对，测试代码 Bug 自行修复（QA 职责）。
- **真编译/真运行尝试**：已检测本机构建能力（见下），结论为「无法真编译」。
- **重点文件**：`KeyMapperAccessibilityService.kt`、`MappingEngine.kt`、`GestureInjector.kt`、`FloatingWindowService.kt`、`ConfigRepository.kt`、`JsonConfigStore.kt`、`Constants.kt`、`di/AppModule.kt`、`MainActivity.kt`、全部 domain/model、util、5 个测试。

## 二、构建环境（关键前提）

本机环境检测结果（已实测）：

- `gradle` / `gradlew`：**均不存在**
- Android SDK：`ANDROID_HOME` / `ANDROID_SDK_ROOT` **均为空**，`local.properties` **不存在**
- 因此：**无法在本机做真编译（gradle sync / assembleDebug / 跑 instrumented 测试）**，也无法跑任何依赖 Android 运行时的单元/集成测试。

> 结论：本报告的判断均为**静态代码审查 + 纯逻辑推演**，不替代真机/真编译验证。所有「需真机验证」项见第八节。

## 三、源码静态一致性审查结论

### 3.1 已确认自洽（无问题）

1. **广播 Action 全链路一致**：`Constants.kt` 定义的 17 个 `ACTION_*` 与 `EXTRA_*` 常量，在被 `KeyMapperAccessibilityService` / `FloatingWindowService` / `KeepAliveService` / `MainActivity` 发送与接收处**引用一致**，无悬空（发送方写字符串字面量而常量缺失、或常量定义了却拼错引用）的情况。
2. **`ACTION_KEY_TRIGGERED` 复核**：仅在 `Constants.kt:45` 定义，**全仓库无任何发送方或消费方引用**（grep 确认）。符合工程师自陈「已定义但未发送」——属于预留/死常量，**无悬空引用，非缺陷**。
3. **引擎 ↔ 注入通道**：`MappingEngine` 与 `MacroPlayer` 均只依赖 `GestureInjector` 接口，`KeyMapperAccessibilityService` 实现该接口且签名完全匹配（click/longpress/swipe/pinch 及默认参数一致）。
4. **数据层**：`ConfigRepository` 调用的 `JsonConfigStore` 方法（`read/write/delete/listNames/readAll/exists/writeToUri/readFromUri`）全部存在；`JsonConfigStore` 调用的 `MappingConfig.fromJson` / `KeyMapperJson.instance` 均存在。
5. **JSON 模型与类图一致**：`MappingConfig/KeyBinding/GestureAction/Macro/MacroStep` 字段与 `ARCHITECTURE.md` 类图一致；`KeyMapperJson.instance` 已设置 `ignoreUnknownKeys = true`、`coerceInputValues = true`、`encodeDefaults = true`、`explicitNulls = false`（满足 P0-7「字段缺失给默认、老版本向前兼容」）。
6. **Koin 注入匹配**：`AppModule` 声明的 `SharedPreferences / JsonConfigStore / ConfigRepository / ResolutionScaler / MacroPlayer / MappingEngine` 与各被注入方（服务/Activity）构造与 `by inject()` 完全对应；`MappingEngine(scaler, macroPlayer)`、`MacroPlayer()`、`ResolutionScaler()` 构造参数匹配。
7. **UI 视图成员引用一致**：`FloatingWindowService` 引用的 `FloatingButtonView.onSingleTap/onLongPress/onDragDelta/onDragEnd/setIdleAlpha`、`ControlPanelView.onStartMapping/onLoadConfig/onToggleMarkers/onToggleMarkerManage/onOpenSettings/onClose/setActiveConfig`、`MarkerLayerView.onMarkerLongPress/manageMode/setMarkers`、`DebugHighlightView.highlight(x,y,colorHex,text)/clear()/frameIntervalMs` —— 在对应 UI 文件中**全部存在**。
8. **资源与 Manifest**：`strings.xml` 含 `FloatingWindowService`/`KeepAliveService`/`MainActivity` 引用的全部字符串；`drawable/ic_launcher.xml`、`drawable/ic_notification.xml`、`ic_crosshair` 等存在；`res/xml` 含 `accessibility_service_config` 与 `keep_alive_service_config`；`themes.xml` 用 `Theme.MaterialComponents`（依赖 `material:1.11.0` 已声明）；`AndroidManifest.xml` 中 4 个 Activity + 3 个 Service + 权限声明完整且一致。
9. **文件清单**：Kotlin 主源码 29 个 + 测试 5 个 = 34 个，与架构文档「34 个 Kotlin 文件」一致（工程师「缺失 14 文件已补齐」成立）。

### 3.2 源码问题（需转工程师修复）

- **【源码编译缺陷-1】`app/src/main/java/com/keymapper/accessibility/KeyMapperAccessibilityService.kt`**
  - **现象**：第 277 行 `private val gestureCallback = object : GestureResultCallback() {` 使用了 `GestureResultCallback`，但本文件仅 `import android.accessibilityservice.GestureDescription`（第 4 行），**未导入其嵌套类** `android.accessibilityservice.GestureDescription.GestureResultCallback`。Kotlin 导入外层类不会带入嵌套类，因此该符号为 Unresolved reference，**将导致整个模块编译失败**。
  - **影响范围**：该 `gestureCallback` 同时作为第 270 行 `dispatchGesture(builder.build(), gestureCallback, mainHandler)` 的回调参数，错误会阻断无障碍服务的手势注入编译。
  - **修复建议（一行 import，不改动业务语义）**：在文件顶部补 `import android.accessibilityservice.GestureDescription.GestureResultCallback`。
  - **路由**：业务源码，QA 不擅自修改，转主理人交工程师修复。

> 除上述 1 处外，未在其余源码中发现其它确定的编译级错误（未导入类、拼写、不存在的方法/属性、枚举不匹配）。

## 四、测试逻辑正确性审查结论

### 4.1 逻辑正确、无需修改的测试

- `ResolutionScalerTest.kt`：6 条断言（scaleValue 基础/四舍五入/同尺寸/非法源、scalePoint、needsScaling）与 `ResolutionScaler` 纯函数实现完全一致。✅
- `KeyBindingSerializationTest.kt`：`ignoreUnknownKeys`、`encodeDefaults`、`explicitNulls=false` 行为下，单绑定往返、未知字段忽略、actionType 默认 CLICK、整配置往返断言全部成立。✅
- `MacroPlayerTest.kt`：`runTest` 虚拟时间下 `playSuspend` 的时序/holdMs/repeat 断言正确——`KEY_DOWN(holdMs=500)` 走长按 1 次、`click`+`click×3` 共 4 次点击、`WAIT/KEY_UP` 无手势注入；`shortKeyDown_usesClick` 中 `holdMs=0` 因 `40 > 40` 不成立走点击而非长按，断言成立。✅
- `ConfigRepositoryInstrumentedTest.kt`：保存/加载/删除/列表/导出导入逻辑自洽（依赖真机文件 IO，无法本机跑）。✅

### 4.2 测试代码 Bug（已由 QA 自行修复，见第五节）

- `MappingEngineTest.kt` 第 72 行、第 115 行两处断言与 `MappingEngine` 的**既定消费语义**矛盾（详见第五节）。

## 五、QA 已自行修复的测试问题（明确清单）

文件：`app/src/test/java/com/keymapper/MappingEngineTest.kt`

**修复 1 — `singleKey_dispatchesClick`（原第 72 行）**
- 原代码：`assertFalse(engine.handleKey(KeyEvent.KEYCODE_A, false))` // 注释「抬起不应再次注入」
- 改为：
  ```kotlin
  // 抬起事件同样被消费（MappingEngine 设计：被消费 DOWN 的键，其 UP 也一并消费，
  // 避免前台应用收到孤立的 UP），但不再注入手势
  assertTrue(engine.handleKey(KeyEvent.KEYCODE_A, false))
  assertEquals(1, injector.clicks.size)
  ```
- 依据：`MappingEngine.handleKey` 在 `!isDown` 分支返回 `consumedKeys.remove(normalized)`；而文件第 56–57 行注释明确「已被引擎消费掉 DOWN 的键码，对应的 UP 也要一并消费，避免漏传给前台应用」。映射键的 UP 应被消费（返回 true），仅「不再注入」。原断言 `assertFalse` 把「不再注入」误当成了「返回 false」。

**修复 2 — `repeatCount_doesNotRetrigger`（原第 115 行）**
- 原代码：`assertFalse(engine.handleKey(KeyEvent.KEYCODE_A, true, repeatCount = 1))` // 注释「长按自动重复不应重新派发」
- 改为：
  ```kotlin
  // 长按自动重复（repeatCount > 0）被消费但不重新派发，避免前台应用收到按键刷屏
  assertTrue(engine.handleKey(KeyEvent.KEYCODE_A, true, repeatCount = 1))
  ```
- 依据：`handleKey` 在 `repeatCount > 0` 分支返回 `consumedKeys.contains(normalized)`。首次按下已把该键加入 `consumedKeys`，故长按重复事件返回 true（被消费、不传给前台），但不重新 `dispatch`。键映射器必须消费重复事件，否则前台应用会被物理键刷屏。原断言 `assertFalse` 与实现语义不符（实现本身正确）。

> 说明：这两处修复假设的预期行为是「映射键的 UP 与长按重复均被消费（返回 true）」。该语义与 `MappingEngine` 的 `consumedKeys` 设计及注释一致，对键映射器是正确的。若团队最终决议希望物理键**透传**给前台应用，则应反过来修改 `MappingEngine.handleKey`（而非测试），届时会重新路由给工程师。当前按源码既定设计修正测试。

## 六、智能路由判定

- **源码缺陷 → 路由：Engineer（经主理人）**：1 项（`KeyMapperAccessibilityService.kt` 缺 `GestureResultCallback` 导入，编译阻断）。
- **测试缺陷 → 路由：QA（已自行修复）**：2 处断言（`MappingEngineTest` 第 72、115 行）。
- **总体判定：Engineer**（存在需工程师修复的源码编译缺陷）。测试侧问题已由 QA 闭环。

## 七、本机无法编译前提下的结论

- **已确认正确（静态/逻辑）**：跨文件签名与 Koin 注入自洽；广播 Action 收发一致；JSON 模型与类图一致且 `ignoreUnknownKeys=true`；UI 视图成员引用一致；strings/drawable/xml/manifest 资源完整；4 个纯函数/序列化/宏时序/配置逻辑测试断言在给定实现下成立（除已修的 2 处）。
- **仍需真机/真编译验证（本机无法覆盖）**：
  1. 真编译通过性（`GestureResultCallback` 修复后能否 assembleDebug 成功，及 AGP/Kotlin 1.9.22 版本协同）；
  2. `KeyMapperAccessibilityService.dispatchGesture` 真实手势注入效果（点击/长按/滑动/双指缩放）；
  3. `FloatingWindowService` 悬浮球拖拽、面板展开/收起、标记层长按菜单的运行时行为；
  4. `ConfigRepositoryInstrumentedTest` 真机文件 IO 往返；
  5. `KeepAliveService` 自重启闭环在真实 ROM 上的可靠性；
  6. P1-1 前台包名自动切换、P2-3 低电量降频的真实触发；
  7. `ACTION_KEY_TRIGGERED` 如后续要做测试模式高亮广播，需补发送方。
