# RTC Client

`rtc/client` 是外部请求者侧的独立 WebRTC 客户端骨架。

当前阶段目标：

- 独立加载客户端配置
- 提供可单独部署的 CLI 入口
- 保留后续 non-trickle offer 生成和 HTTP signaling 的实现位置

当前状态：

- `show-config` 已可用
- `health` 已可用，用于检查手机代理后的服务端健康接口
- `connect` 已接入 non-trickle offer + HTTP signaling + answer 设置的基础流程
- `connect --record out.mp4` 已可将远端视频保存为本地文件
- `connect` 输出会打印客户端本地 ICE candidates 和最终选中的 candidate pair
- 当前还没有做实时媒体渲染，录制模式会把远端视频写入文件

## 目录

```text
client/
  README.md
  requirements.txt
  config.example.json
  rtc_client/
    __init__.py
    cli.py
    client.py
    config.py
  scripts/
    run_client.sh
```

## 依赖

```bash
python3 -m pip install -r requirements.txt
```

## 配置

复制并修改：

```bash
cp config.example.json config.json
```

## 运行

先安装运行依赖：

```bash
bash ../scripts/install_runtime_deps.sh
```

显示配置：

```bash
bash scripts/run_client.sh show-config --config config.example.json
```

检查服务端健康状态：

```bash
bash scripts/run_client.sh health --config config.example.json
```

后续连接入口：

```bash
bash scripts/run_client.sh connect --config config.example.json
```

录制远端视频：

```bash
bash scripts/run_client.sh connect --config config.example.json --record out.mp4
```

调整录制时长：

```bash
bash scripts/run_client.sh connect --config config.example.json --record out.mp4 --record-seconds 15
```

说明：

- `scripts/run_client.sh` 会优先使用 `rtc/.venv/bin/python`
- `health` 默认不读取系统 HTTP 代理，避免访问手机或本地服务时被代理劫持
- `connect` 输出里的 `localCandidates` 是客户端收集到的本地 host candidates
- `selectedCandidatePairs` 是 ICE 连通后真正被选中的本地/远端地址对
- 录制完成后，命令输出里的 `recording.path` 就是生成文件的绝对路径
