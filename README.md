# Phone Proxy

当前仓库包含两部分：

- 一个可直接在当前机器运行和测试的 Python MVP 内核
- 一个与设计文档对齐的 Android App 源码骨架

## 目录

```text
phone_proxy/
  README.md
  EXECUTION_PLAN.md
  MVP_DESIGN.md
  configs/
  laptop_demo/
  phone_proxy_core/
  android/
  scripts/
  tests/
```

## Python MVP

Python MVP 只使用标准库，适合在当前环境下做联调与验证。

### 组件

- `phone_proxy_core/laptop_demo.py`
  监听同一个端口上的 TCP 和 UDP，用于验证代理链路
- `phone_proxy_core/proxy.py`
  读取多规则配置，同时启动每条规则对应的 TCP 和 UDP 转发
- `configs/default_rules.json`
  对应设计文档中的默认规则
- `configs/local_test_rules.json`
  当前机器的本地联调规则

### 运行 Demo 服务

```bash
python3 scripts/run_laptop_demo.py --host 127.0.0.1 --port 10001
```

### 运行代理

```bash
python3 scripts/run_proxy.py --config configs/local_test_rules.json
```

### 本地验证

```bash
python3 -m unittest discover -s tests -v
python3 scripts/deployment_smoke_test.py
```

## Android App

`android/` 目录里已经放入了手机代理 App 的 Gradle 工程、规则模型、持久化仓储、配置页面，以及真实可运行的 TCP/UDP 代理前台服务代码。

当前 Android 规则能力：

- 每条规则保留一组 `TCP` 单端口映射
- 每条规则额外支持一组可选的 `UDP` 端口段 1:1 映射
- 当 `UDP` 端口段留空时，UDP 仍回退到和 TCP 相同的单端口转发

App 页面里的 UDP 端口段字段含义：

- `UDP listen port start`
- `UDP listen port end`
- `UDP target port start`
- `UDP target port end`

配置要求：

- 四个 UDP 端口段字段要么都填，要么都留空
- `listen` 和 `target` 两段长度必须一致
- 手机侧会按偏移做 1:1 映射

示例：

- `UDP listen 40000-40031`
- `UDP target 40000-40031`

表示手机会监听 `40000..40031/udp`，并分别转发到目标的 `40000..40031/udp`

当前仓库已经可以在项目内本地工具链下完成 Android 编译：

```bash
bash scripts/build_android_app.sh
```

生成产物：

- `android/app/build/outputs/apk/debug/app-debug.apk`

安装命令：

```bash
bash scripts/install_android_app.sh
```

说明：

- `build_android_app.sh` 默认直连 Maven/Google 仓库，不强制走系统代理
- 如果当前网络环境必须走代理，可显式开启：

```bash
PHONE_PROXY_USE_GRADLE_PROXY=1 bash scripts/build_android_app.sh
```

- `build_android_app.sh` 会把 `GRADLE_USER_HOME` 固定到项目内，避免受只读的宿主 `~/.gradle` 影响
- 如果当前网络环境需要代理拉取 Maven/Google 依赖，使用上面的 `PHONE_PROXY_USE_GRADLE_PROXY=1`
- 当前仓库这版 APK 已在真机上完成一次安装更新验证

## Android Toolchain Scripts

我已经补了项目内可复用的 Android 工具链脚本：

- `scripts/install_android_toolchain.sh`
- `scripts/build_android_app.sh`
- `scripts/install_android_app.sh`

目标安装位置：

- `android/.local/`

说明：

- 脚本会优先在项目内准备本地 Java、Gradle 和 Android SDK 组件
- 首次准备工具链或首次 Gradle 编译会下载较多依赖，实际执行可能会持续较久
- `build_android_app.sh` 会显式传入项目内 JDK 的 `cacerts`，避免本地解压 JDK 时出现 HTTPS 证书信任问题
- 设备安装是否可用仍取决于你当前终端能否看到 `adb devices`

## RTC Scaffold

`rtc/` 目录下已经开始搭建独立的 WebRTC client/server 骨架：

- `rtc/RTC_DESIGN.md`
  当前基于手机代理的 WebRTC 设计文档
- `rtc/client/`
  外部请求者侧独立产品骨架
- `rtc/server/`
  手机后内部服务器侧独立产品骨架

当前骨架状态：

- `rtc/client` 已具备独立配置、CLI 和健康检查入口
- `rtc/server` 已具备独立配置，以及 `health` / `offer` / `delete session` 路由
- `rtc/client` / `rtc/server` 已接入基础 non-trickle offer/answer 流程
- `rtc/server` 已通过自定义 ICE gatherer 绑定固定本地 UDP 端口
- `rtc/scripts/loopback_smoke_test.sh` 可做本机 loopback 联调
