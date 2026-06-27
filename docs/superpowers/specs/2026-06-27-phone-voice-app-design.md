# Phone Voice App 设计文档

## 1. 文档说明

- 文档名称：Phone Voice App 设计文档
- 适用范围：`phone_voice/` Android 应用
- 文档日期：2026-06-27
- 依据来源：
  - 当前会话中关于中文 TTS、UTF-8 解码、弱网与保活问题的验证结论
  - 仓库 `/home/aicor/compute_netwok/phone_proxy`
  - 仓库 `/home/aicor/compute_network/sandbox-demo/phone_proxy`
  - `phone_voice` 历史提交：`af96387`、`5e94f83`、`e47f22c`、`760d45b`、`efc53b2`、`b8f4e6e`、`579a0a8`、`0b81306`、`89174d3`

本文不是 2026-05-14 初版方案的简单复制，而是基于当前实现和演进历史形成的“落地版设计文档”。

## 2. 背景

项目目标是在 Android 手机上提供一个本地 LAN HTTP 服务，让外部设备可以通过局域网直接触发手机播报文本。最初需求仅包含单一接口 `POST /api/v1/word2voice`，接收 JSON 中的 `text` 字段后调用系统 `TextToSpeech` 播放。

随着实际使用深入，需求与约束逐步显性化：

- 终端以 VIVO/OriginOS 为代表，后台存活和省电策略激进，单纯 Activity 内嵌服务不稳定。
- 中文播报依赖系统 TTS 引擎与语音包，不能只依赖默认英文 voice。
- 实际联调中发现 `application/json` 无 `charset` 时，部分链路会把中文正文解码成乱码。
- 业务侧不仅需要“播报”，还需要在部分请求中顺带把结构化 `arm_body` 转发给下游执行服务。
- 某些手机在灭屏、冷启动、切换中文 voice 时响应变慢，需要在设计中考虑保活与观测能力。

因此，`phone_voice` 从“单接口文本播报 MVP”逐步演化为“前台服务 + 本地 HTTP Server + 中文 TTS + 可选并发转发 + 可配置监听地址 + 保活能力”的端侧代理应用。

## 3. 设计目标

### 3.1 目标

- 在手机本机暴露稳定的 LAN HTTP 服务。
- 支持文本播报，并优先保证中文播报可用。
- 为不同业务模式提供两类入口：
  - 纯播报
  - 播报并转发结构化负载
- 在 Android 后台限制较强的环境下尽量保持可用。
- 提供足够的运行态展示、日志和错误反馈，便于联调与排障。

### 3.2 非目标

- 不做公网暴露、NAT 穿透和账号鉴权。
- 不做长音频文件上传与播放器编排。
- 不做离线 TTS 引擎内置，只复用系统 TTS。
- 不做消息持久化队列和断点恢复。

## 4. 方案对比与分析

### 4.1 方案一：手机端本地 HTTP + 系统 TTS 播报

方案描述：

- 手机运行前台服务。
- 内嵌 HTTP Server 监听局域网请求。
- 收到文本后直接调用系统 `TextToSpeech` 播放。

优点：

- 端到端链路短，适合局域网低延迟控制。
- 不依赖云端，不需要额外 TTS 服务。
- 业务闭环在手机本机，便于和音频输出、系统权限、通知状态打通。
- 即使网络不可达云端，只要 LAN 可用仍可工作。

缺点：

- 播报质量依赖手机系统 TTS 引擎和语音包。
- 厂商省电策略会影响可用性。
- 不同机型中文 voice、冷启动时延存在差异。

结论：

- 这是当前采用的主方案，也是最贴近原始需求的方案。

### 4.2 方案二：服务端合成音频，手机只负责播放音频流

方案描述：

- 外部服务先将文本转换为音频数据。
- 手机仅暴露“播放音频”接口，或接收音频 URL/字节流播放。

优点：

- 播放效果可统一，不依赖手机系统 TTS 的中英文能力。
- 音色、发音人、音频后处理更可控。
- 可以绕开部分机型中文 TTS 能力不足的问题。

缺点：

- 服务端必须额外具备 TTS 能力，增加系统复杂度。
- 手机端需要新增音频缓存、格式兼容、播放器状态管理。
- 接口变为大报文或文件流，网络与存储开销更高。
- 无法天然复用当前“纯文本触发”生态。

结论：

- 适合作为后续扩展方案，不适合作为当前主线设计。
- 当前版本保留扩展空间，但不作为 MVP/现网主路径。

### 4.3 方案三：单一统一接口，同时承担播报与转发

方案描述：

- 所有请求都发到一个接口。
- 如果带 `arm_body` 就执行转发，否则只播报。

优点：

- 使用方只需记住一个入口。
- 对调用方更“简洁”。

缺点：

- 语义变得不清晰，调用方无法从路径层面区分请求意图。
- 失败语义复杂：纯播报失败、转发失败、二者同时失败都混在一条路径上。
- 历史演进中已经证明，业务更需要显式区分“只播报”和“播报+转发”。

结论：

- 当前实现没有采用统一路径，而是采用双接口设计：
  - `/api/v1/word2voice`
  - `/api/v1/word2voice_forward`

### 4.4 当前采用方案

当前实现采用：

- 本地 HTTP + 系统 TTS 的端侧方案
- 双接口区分纯播报与播报+转发
- 前台服务 + WakeLock + 省电优化提示强化存活
- 中文优先的 TTS 配置
- JSON UTF-8 兼容修正
- 并发发起转发与播报，降低整体等待时间

## 5. 总体架构

### 5.1 架构概述

系统由五类核心能力构成：

- UI 层：配置 URL、超时、查看运行状态、启动/停止服务。
- 服务层：承载前台通知、WakeLock、TTS 生命周期和 HTTP Server 生命周期。
- 接口层：接收 HTTP 请求、参数校验、模式分流、响应构建。
- 播报层：基于 Android `TextToSpeech` 的中文优先语音播报。
- 转发层：将 `arm_body` 以 HTTP POST 方式转发给下游执行服务。

### 5.2 关键设计决策

- 服务常驻主体是 `VoiceHttpForegroundService`，不是 `MainActivity`。
- HTTP Server 使用 `NanoHTTPD`，以最小依赖实现 LAN 监听。
- UI 与服务状态通过 `VoiceServiceStateStore` 解耦。
- 监听 URL 与转发 URL 存储在 `SharedPreferences` 中，方便重启恢复。
- 播报与转发在 `speech+forward` 模式下并发启动，但接口响应仍会等待转发结果汇总。

## 6. 4+1 视图

### 6.1 场景视图

#### 场景 1：用户启动服务

1. 用户打开 App。
2. `MainActivity` 读取本地配置并展示。
3. 用户点击 `Start Service`。
4. `VoiceHttpForegroundService` 进入前台，创建通知并申请 WakeLock。
5. `TtsManager` 初始化系统 TTS。
6. `VoiceRuntimeController` 根据配置创建 `WordToVoiceServer` 并启动监听。
7. UI 显示当前语音接口 URL、转发接口 URL、TTS 就绪状态。

#### 场景 2：纯播报请求

1. 客户端向 `/api/v1/word2voice` 发送 JSON。
2. `WordToVoiceServer` 修正 JSON charset，读取 body。
3. `WordToVoiceRequestHandler` 校验方法、路径、TTS 就绪状态和 `text`。
4. 调用 `TtsManager.speak(text)`。
5. 立即返回 `speech` 成功/失败响应，不执行转发。

#### 场景 3：播报并转发请求

1. 客户端向 `/api/v1/word2voice_forward` 发送 JSON。
2. Handler 校验 `text` 与 `arm_body`。
3. 异步线程池启动下游转发。
4. 主线程继续调用 TTS 接收播报请求。
5. 汇总 `speech` 与 `forward` 结果，返回统一 JSON 响应。

#### 场景 4：异常与恢复

1. 如果 TTS 未就绪，返回 `503`。
2. 如果 JSON 非法或缺少字段，返回 `400`。
3. 如果端口绑定失败，服务状态切换到 `ERROR` 并在 UI 暴露错误信息。
4. 如果下游转发失败，`/api/v1/word2voice_forward` 返回 `500`，但播报结果仍单独保留在响应体中。

### 6.2 逻辑视图

逻辑模块划分如下：

```text
MainActivity
  ├─ VoiceSettingsRepository
  ├─ VoiceHttpForegroundService.start/stop
  └─ VoiceServiceStateStore (observe)

VoiceHttpForegroundService
  ├─ TtsManager
  ├─ VoiceRuntimeController
  ├─ VoiceSettingsRepository
  └─ WakeLock / Notification / Battery Optimization

VoiceRuntimeController
  ├─ LocalIpResolver
  ├─ WordToVoiceServer
  └─ VoiceServiceStateStore

WordToVoiceServer
  └─ WordToVoiceRequestHandler
       ├─ SpeechDispatcher(TtsManager)
       ├─ Forwarder(ForwardPostClient)
       └─ VoiceRuntimeSettings
```

各模块职责：

- `MainActivity`：配置编辑、状态展示、权限与保活引导。
- `VoiceHttpForegroundService`：系统能力托管与运行时入口。
- `VoiceRuntimeController`：服务启动停止编排。
- `WordToVoiceServer`：HTTP 接入、charset 兼容。
- `WordToVoiceRequestHandler`：业务规则、模式分流、响应构建。
- `TtsManager`：语言与 voice 选择、语音调度、播放日志。
- `ForwardPostClient`：下游 HTTP POST 转发。
- `VoiceSettingsRepository`：配置持久化。
- `VoiceServiceStateStore`：UI/Service 状态同步。

### 6.3 开发视图

当前代码组织如下：

```text
phone_voice/
  app/src/main/java/com/example/phonevoice/
    MainActivity.kt
    config/
      VoiceEndpointConfig.kt
      VoiceRuntimeSettings.kt
      VoiceSettingsRepository.kt
    http/
      ForwardPostClient.kt
      Forwarder.kt
      HttpResult.kt
      SpeechDispatcher.kt
      WordToVoiceRequestHandler.kt
      WordToVoiceServer.kt
    net/
      LocalIpResolver.kt
    runtime/
      EmbeddedServer.kt
      VoiceRuntimeController.kt
    service/
      VoiceHttpForegroundService.kt
    state/
      VoiceServiceStateStore.kt
      VoiceServiceStatus.kt
      VoiceUiState.kt
    tts/
      TtsManager.kt
```

这种分层把“Android 生命周期”“接口处理”“业务模型”“状态同步”分开，便于单元测试覆盖：

- 配置类测试
- 请求处理测试
- Server 行为测试
- 转发行为测试
- 状态机测试

### 6.4 进程视图

运行态并发关系如下：

```text
UI 主线程
  └─ MainActivity 读写配置、观察状态

Android Service 线程/回调
  └─ VoiceHttpForegroundService 启停服务、管理系统资源

NanoHTTPD 工作线程
  └─ 处理 HTTP 请求，调用 WordToVoiceRequestHandler

Forward Executor 线程池
  └─ 对 /word2voice_forward 的 arm_body 发起异步 POST

Android TTS 内部线程
  └─ 处理 speak 请求、回调播放开始/完成/错误
```

关键并发策略：

- `speak+forward` 模式下，转发先异步提交，再触发播报。
- `speak()` 只确认 TTS 接受请求，不等待播放完成。
- 最终 HTTP 响应会等待转发任务结束，以便向调用方报告完整结果。

### 6.5 物理视图

物理部署拓扑如下：

```text
LAN Client
  └─ HTTP POST
       ↓
Android Phone
  ├─ Foreground Service
  ├─ Embedded HTTP Server :8080
  ├─ Android TextToSpeech
  └─ Optional Forward Client
       ↓
Downstream Service
  └─ e.g. http://10.45.0.2:18891/api/v1/exec
```

部署特征：

- 手机监听 `0.0.0.0:<port>`，对局域网开放。
- UI 展示的是解析出的具体 LAN IP。
- 转发目标通常位于同一局域网或可达内网。

## 7. 详细设计

### 7.1 UI 与配置管理

UI 由 `MainActivity` 负责，当前支持以下配置项：

- `listenUrl`
- `speechForwardUrl`
- `forwardUrl`
- `forwardTimeoutMillis`

设计要点：

- 每次修改配置即调用 `VoiceSettingsRepository.save()` 持久化。
- 启动服务前再次保存，避免 UI 内存态与服务读取值不一致。
- 首屏展示服务状态、TTS 状态、纯播报 URL、播报+转发 URL、最近一次播报文本。
- 提供“忽略电池优化”引导，降低被系统回收概率。

### 7.2 前台服务与保活设计

`VoiceHttpForegroundService` 是系统运行主体，职责包括：

- 创建通知渠道并进入前台服务。
- 初始化 `TtsManager`。
- 构造 `VoiceRuntimeController`。
- 获取 `PARTIAL_WAKE_LOCK`。
- 记录当前电源与通知权限状态日志。

对应权限：

- `INTERNET`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `WAKE_LOCK`

设计原因：

- 仅靠普通后台 Service 难以在 VIVO 等设备上稳定运行。
- WakeLock 不能保证永不回收，但能减少灭屏后 CPU 被过早挂起带来的服务不可用问题。

### 7.3 监听地址与 URL 归一化

监听配置由 `VoiceRuntimeSettings` 负责解析：

- `listenUrl` 用于纯播报入口的监听主机、端口和路径。
- `speechForwardUrl` 用于播报+转发入口的路径，也可在 `listenUrl` 为空时补齐主机和端口。
- `forwardUrl` 为下游转发目标。

归一化规则：

- 仅接受 `http` scheme。
- 端口缺省时默认 `8080`。
- path 缺省时分别补为：
  - `/api/v1/word2voice`
  - `/api/v1/word2voice_forward`
- 若用户只配置了旧版 `listen_host`，通过兼容逻辑自动转换为 `listenUrl`。

### 7.4 HTTP Server 设计

`WordToVoiceServer` 继承 `NanoHTTPD`，职责较窄：

- 在指定 host/port 上监听。
- 解析请求 body。
- 将请求交给 `WordToVoiceRequestHandler`。
- 将 `HttpResult` 映射为 HTTP Response。

关键兼容设计：

- 对 JSON 请求头进行 charset 补齐：
  - `application/json` 自动归一化为 `application/json; charset=UTF-8`
- 这是为了解决部分客户端未显式传 `charset` 时中文正文被错误按 ASCII 解码的问题。

### 7.5 请求处理设计

`WordToVoiceRequestHandler` 是核心业务入口。

支持两种模式：

- `SPEECH_ONLY`
- `SPEECH_AND_FORWARD`

处理流程：

1. 校验路径是否命中两个合法 endpoint。
2. 校验方法必须为 `POST`。
3. 校验 `dispatcher.isReady()`。
4. 解析 JSON。
5. 校验 `text` 非空且长度不超过 `1000`。
6. 若为 `speech+forward` 模式，校验 `arm_body` 是 JSON Object。
7. 先启动异步转发任务。
8. 立即调用 `dispatcher.speak(text)`。
9. 汇总 `speech` 与 `forward` 结果，构造统一响应。

这样设计的原因：

- 纯播报接口不受下游服务可用性影响。
- 转发接口能明确暴露下游成功或失败。
- 异步先发转发，再执行 TTS，可减少整体链路总时长。

### 7.6 TTS 设计

`TtsManager` 封装 Android `TextToSpeech`。

关键能力：

- 初始化系统 TTS 引擎。
- 记录默认 locale/voice。
- 优先选择中文 locale 和本地已安装中文 voice。
- 根据文本是否包含汉字，动态切换中文或默认 voice。
- 使用 `QUEUE_FLUSH`，新请求覆盖当前播报。
- 通过 `UtteranceProgressListener` 输出开始、完成、错误日志。

重要经验结论：

- 端侧“能否播报中文”不仅取决于代码，还取决于系统 TTS 引擎是否安装中文语音包。
- 某些机型第一次切到中文 voice 明显更慢，因此日志中区分“请求被接受”和“真正开始播放”非常重要。

### 7.7 转发设计

`ForwardPostClient` 通过 `HttpURLConnection` 向下游发起 POST。

当前设计要点：

- 使用独立超时配置，默认 `3000ms`。
- 请求体直接转发 `arm_body.toString()`。
- `2xx` 视为成功，其他状态码视为失败。
- IOException 和参数异常统一封装为失败结果。

当前实现约束：

- `Content-Type` 目前设置为 `application`，这是现网兼容行为，不是标准 JSON 写法。
- 若后续下游统一规范为 JSON，建议升级为 `application/json; charset=utf-8`。

### 7.8 状态同步设计

`VoiceServiceStateStore` 用 `MutableStateFlow<VoiceUiState>` 管理运行状态。

状态包括：

- `status`
- `lanUrl`
- `forwardLanUrl`
- `ttsReady`
- `lastText`
- `lastError`

设计价值：

- Service 与 Activity 不直接强耦合。
- UI 能实时观察服务运行态和 TTS 就绪态。
- 出错时能给使用者明确反馈，而不是静默失败。

### 7.9 观测与日志设计

当前日志关注以下维度：

- HTTP 请求入口日志
- 解析完成日志
- speech 开始与结果日志
- forward 开始与结果日志
- TTS 播放开始、成功、失败日志
- 电源优化、通知权限、WakeLock 状态日志

重点指标：

- `elapsedMs`
- `durationMs`
- `textChars`
- `bodyBytes`
- `timeoutMs`
- `statusCode`

这样设计是为了支撑以下排障场景：

- 为什么某些手机响应快、某些慢
- 是 HTTP 接收慢，还是 TTS 接受慢，还是下游转发慢
- 中文切 voice 是否造成冷启动抖动

## 8. 接口说明

### 8.1 纯播报接口

- 方法：`POST`
- 路径：`/api/v1/word2voice`
- Content-Type：`application/json`

请求示例：

```json
{
  "text": "你好，请到门口取快递"
}
```

成功响应示例：

```json
{
  "success": true,
  "message": "accepted",
  "text": "你好，请到门口取快递",
  "speech": {
    "success": true,
    "message": "accepted",
    "elapsedMs": 3
  },
  "forward": {
    "success": true,
    "message": "speech-only endpoint",
    "skipped": true
  }
}
```

说明：

- 该接口不会触发下游转发。
- 返回成功表示 TTS 请求已被接受，不代表音频已播放完成。

### 8.2 播报并转发接口

- 方法：`POST`
- 路径：`/api/v1/word2voice_forward`
- Content-Type：`application/json`

请求示例：

```json
{
  "text": "今天天气真好啊",
  "arm_body": {
    "target": "yellow bottle"
  }
}
```

成功响应示例：

```json
{
  "success": true,
  "message": "accepted",
  "text": "今天天气真好啊",
  "speech": {
    "success": true,
    "message": "accepted",
    "elapsedMs": 4
  },
  "forward": {
    "success": true,
    "message": "HTTP 204",
    "statusCode": 204,
    "skipped": false
  }
}
```

失败响应示例：

```json
{
  "success": false,
  "message": "forward failed",
  "text": "今天天气真好啊",
  "speech": {
    "success": true,
    "message": "accepted",
    "elapsedMs": 4
  },
  "forward": {
    "success": false,
    "message": "HTTP 500 from forwardUrl",
    "statusCode": 500,
    "skipped": false
  }
}
```

### 8.3 下游转发接口

这是 `phone_voice` 作为客户端调用的外部接口，不是本应用对外暴露的接口。

- 方法：`POST`
- URL：来自配置 `forwardUrl`
- Body：`arm_body` 的 JSON 字符串
- 超时：来自配置 `forwardTimeoutMillis`

请求示例：

```json
{
  "target": "yellow bottle"
}
```

### 8.4 错误码

| 状态码 | 含义 | 触发条件 |
| --- | --- | --- |
| 200 | 成功 | 请求被接受，且如有转发则转发成功 |
| 400 | 请求错误 | JSON 非法、`text` 为空、`text` 超长、`arm_body` 非对象 |
| 404 | 路径不存在 | 未命中两个支持的 endpoint |
| 405 | 方法错误 | 非 `POST` 请求 |
| 500 | 处理失败 | `speech+forward` 模式下转发失败或播报失败 |
| 503 | 服务不可用 | TTS 尚未就绪 |

## 9. 数据模型

### 9.1 运行时配置

`VoiceRuntimeSettings`

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `listenUrl` | `String` | 纯播报入口监听 URL |
| `speechForwardUrl` | `String` | 播报+转发入口 URL |
| `forwardUrl` | `String` | 下游服务 URL |
| `forwardTimeoutMillis` | `Int` | 下游转发超时 |

### 9.2 UI 状态

`VoiceUiState`

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `status` | `VoiceServiceStatus` | `STOPPED/STARTING/RUNNING/ERROR` |
| `lanUrl` | `String` | 纯播报 URL 展示值 |
| `forwardLanUrl` | `String` | 播报+转发 URL 展示值 |
| `ttsReady` | `Boolean` | TTS 是否就绪 |
| `lastText` | `String` | 最近一次成功接受的文本 |
| `lastError` | `String` | 最近一次错误 |

## 10. 关键非功能设计

### 10.1 可用性

- 前台服务常驻
- WakeLock 保活
- UI 提示关闭电池优化
- 端口绑定异常显式可见

### 10.2 兼容性

- 中文优先 TTS 选择
- UTF-8 请求头自动补齐
- 旧配置 `listen_host` 自动兼容迁移

### 10.3 性能

- 纯播报接口路径短、依赖少
- 转发与播报并行启动
- 但 `speech+forward` 的响应时间仍受下游转发耗时影响

### 10.4 安全性

- 当前仅适用于可信局域网
- 明文 HTTP，未做鉴权
- `usesCleartextTraffic=true`
- 若未来进入更复杂网络环境，需补充鉴权、ACL 和审计

## 11. 测试策略

现有单元测试已覆盖以下关键行为：

- URL 归一化与默认值
- 纯播报与播报+转发路径分流
- 非法 JSON、空文本、缺失 `arm_body`
- 转发成功、失败、超时
- UTF-8 `charset` 补齐逻辑
- 并发时“转发早于播报完成启动”的行为
- 状态存储与运行时控制器状态切换

这使得设计不仅停留在文档层，而是已经被编码约束为可回归验证的系统行为。

## 12. 提交历史驱动的演进分析

| 提交 | 主题 | 设计增量 |
| --- | --- | --- |
| `af96387` | `phone voice` | 建立初版独立 Android App，完成前台服务、本地 HTTP、TTS、UI、单元测试脚手架 |
| `5e94f83` | `forward logic` | 引入转发能力、`ForwardPostClient`、`Forwarder`、运行时配置与第二类业务流程 |
| `e47f22c` | `add timeout config` | 为下游转发增加超时可配置能力，避免单次请求长时间挂死 |
| `760d45b` | `config ip refix` | 增强监听 URL/路径归一化与配置兼容，支持自定义监听地址 |
| `efc53b2` | `add proxy detect` | 强化联调适配与测试，降低代理环境或网络环境带来的误判 |
| `b8f4e6e` | `forward body` | 明确转发体为 `arm_body` 原始 JSON，而不是重新封装后的模型 |
| `579a0a8` | `并发处理` | 引入播报与转发并行启动、分阶段日志与耗时观测，改善链路时延分析能力 |
| `0b81306` | `config strength` | 增加电池优化与保活强化，包括 WakeLock 与系统状态提示 |
| `89174d3` | `restore voice` | 恢复纯播报主路径，形成“纯播报 + 播报转发”双接口并存的最终形态 |

这条演进线说明，`phone_voice` 并不是一次性静态设计，而是在真实机型、真实网络和真实业务联调中逐步沉淀出来的工程方案。

## 13. 已知限制与后续建议

### 13.1 已知限制

- 中文播报质量仍依赖系统 TTS 引擎和语音包。
- 未做鉴权，不适合直接暴露到不可信网络。
- 转发接口当前仍等待下游结果，若下游很慢会拖长 HTTP 响应。
- 下游转发请求头仍使用 `application`，存在协议标准化空间。

### 13.2 后续建议

- 增加可选“异步确认模式”，让 `/word2voice_forward` 先返回 `202 Accepted`。
- 将下游 `Content-Type` 升级为 `application/json; charset=utf-8`，并与调用方统一。
- 增加启动自检页，展示 TTS 引擎、中文语音包、网络地址和保活状态。
- 增加运行日志导出能力，便于远程排障。
- 若后续进入公网或半公网环境，补充签名鉴权与来源控制。

## 14. 结论

`phone_voice` 当前已经从“手机播报文本的小工具”演进为一个面向局域网控制场景的端侧语音代理：

- 纯播报路径保持简单、稳定、低依赖。
- 播报+转发路径满足复合业务需求。
- 前台服务、WakeLock、中文 TTS、UTF-8 兼容和并发转发共同构成当前版本的核心设计闭环。

如果后续继续扩展，建议优先围绕三件事推进：

- 标准化下游协议
- 进一步降低 `speech+forward` 的响应时延
- 补齐鉴权与运维观测能力
