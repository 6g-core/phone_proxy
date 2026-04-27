# RTC Server

`rtc/server` 是手机后内部服务器侧的独立 WebRTC 服务端骨架。

当前阶段目标：

- 独立加载服务端配置
- 提供 HTTP health / offer / session-delete 路由骨架
- 保留后续 non-trickle answer 生成、固定 UDP 端口分配和 candidate 改写的实现位置

当前状态：

- `GET /health` 已可用
- `POST /api/v1/webrtc/offer` 已接入 non-trickle answer 生成基础流程
- `DELETE /webrtc/session/{sessionId}` 已具备最小会话回收骨架
- 当前服务端会返回单路占位视频的 answer，并把 candidate 改写成手机代理暴露地址
- 固定代理端口和 aiortc 内部真实本地端口的一致性仍是下一阶段要继续收敛的重点

## 目录

```text
server/
  README.md
  requirements.txt
  config.example.json
  rtc_server/
    __init__.py
    cli.py
    config.py
    sdp.py
    service.py
  scripts/
    run_server.sh
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

```bash
bash scripts/run_server.sh --config config.example.json
```

## 当前接口

- `GET /health`
- `POST /api/v1/webrtc/offer`
- `DELETE /webrtc/session/{sessionId}`

关键配置：

- `advertised_host`
  写入 answer SDP 的手机代理对外地址
- `udp_port_start` / `udp_port_end`
  手机代理与服务端共同约定的固定 UDP 端口段
- `ice_bind_host`
  服务端本地 ICE socket 实际绑定的地址

当前实现已经在 loopback 下验证过：

- 服务端分配 `40000`
- 内部候选端口实际绑定也是 `40000`
- client 可建立单路 `video` 协商连接
