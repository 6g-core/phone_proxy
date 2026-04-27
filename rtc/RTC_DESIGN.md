# RTC Design

## 1. 目标

在当前手机代理基础上，新增一套可独立部署的 WebRTC 客户端和服务端实现，满足下面约束：

- 使用 `non-trickle ICE`
- 首次 SDP 请求路径为：
  外部请求者 -> 手机代理 TCP 入口 -> 手机后的内部服务器
- 不依赖公网 `STUN` 或 `TURN` 服务
- 直接在服务端返回的 SDP 中写入手机代理暴露出的固定地址和 UDP 端口段
- `rtc/client` 和 `rtc/server` 作为两个可分开部署、便于后续移植的独立产品目录

## 2. 设计结论

这次实现不走“自动发现公网地址”的思路，而走“预配置代理地址段 + 服务端答复时改写 candidate”的思路。

核心分两条链路：

- 信令面：走 `TCP`
- 媒体面 / ICE 检查 / DataChannel：走手机代理转发的 `UDP` 端口段

因此手机代理至少要配置两条规则：

1. 一条 TCP 信令规则
2. 一条 UDP 端口段规则

示例：

- TCP signaling:
  `192.168.1.83:18080/tcp -> 10.38.204.90:18080/tcp`
- UDP media:
  `192.168.1.83:40000-40031/udp -> 10.38.204.90:40000-40031/udp`

## 3. 部署拓扑

```text
rtc/client
  |
  | POST /offer over TCP
  v
192.168.1.83:18080
  |
  | 手机 TCP 代理
  v
10.38.204.90:18080
  |
  | rtc/server 生成 non-trickle answer
  | answer SDP 中写死 192.168.1.83:40000-40031 中选出的 candidate
  v
rtc/client
  |
  | ICE checks / DTLS / SCTP / RTP over UDP
  v
192.168.1.83:40000-40031
  |
  | 手机 UDP 端口段 1:1 转发
  v
10.38.204.90:40000-40031
```

## 4. 目录规划

设计确认后，在 `rtc/` 下生成两个目录：

```text
rtc/
  RTC_DESIGN.md
  client/
    README.md
    requirements.txt
    rtc_client/
    scripts/
  server/
    README.md
    requirements.txt
    rtc_server/
    scripts/
```

说明：

- `client/` 是外部请求者产品
- `server/` 是手机后内部服务器产品
- 两边各自有独立入口脚本、配置和依赖，互不要求共享运行环境

## 5. 技术路线

参考 `standalone_signaling.py` 的模式，第一版仍使用：

- Python
- `aiohttp`
- `aiortc`

但和参考实现相比，这里有两个关键差异：

1. 使用 `non-trickle`
2. 不依赖 `STUN/TURN`，而是对 answer SDP 做固定地址段改写

## 6. 协议范围

第一阶段继续贴近 `standalone_signaling.py` 的服务端模式：

- WebRTC signaling over HTTP
- 服务端一次性返回完整 answer SDP
- 不使用 `DataChannel`
- 至少保留一个媒体 `m=` 段，推荐先以单路 `video` 为基准

这里要特别说明：

- 不用 `DataChannel` 是可以的
- 但不能没有任何媒体或数据通道
- 如果既没有媒体轨道，也没有 DataChannel，就不会形成有意义的 WebRTC 协商对象

因此第一阶段建议：

- `client` 创建一个 `recvonly video transceiver`
- `server` 根据后续实现提供真实视频轨道，或先提供占位视频轨道

这样可以先验证：

- 信令路径是否打通
- UDP 端口段代理是否满足 ICE / DTLS / SRTP
- 固定 candidate 改写是否有效

## 7. 信令接口

服务端只暴露一个最小 HTTP API，风格继续贴近 `standalone_signaling.py`：

### 7.1 健康检查

- `GET /health`

返回内容示例：

```json
{
  "ok": true,
  "mode": "phone-proxy-webrtc-server",
  "nonTrickle": true,
  "stunEnabled": false,
  "turnEnabled": false,
  "advertisedHost": "192.168.1.83",
  "advertisedUdpRange": "40000-40031"
}
```

### 7.2 Offer 接口

- `POST /api/v1/webrtc/offer`

请求：

```json
{
  "sessionId": "optional-client-session-id",
  "type": "offer",
  "sdp": "..."
}
```

响应：

```json
{
  "sessionId": "server-generated-or-client-supplied",
  "type": "answer",
  "sdp": "...",
  "iceGatheringState": "complete",
  "iceGatheringMode": "non-trickle",
  "advertisedHost": "192.168.1.83",
  "advertisedUdpRange": "40000-40031"
}
```

### 7.3 会话关闭

- `DELETE /webrtc/session/{sessionId}`

## 8. non-trickle 模式

两端都必须在发送 SDP 前等本地 ICE gathering 结束。

### 8.1 client 行为

`rtc/client`：

1. 创建 `RTCPeerConnection`
2. 添加至少一个 `recvonly` 媒体 transceiver，默认用 `video`
3. `createOffer`
4. `setLocalDescription`
5. 等待本地 ICE gathering 完成
6. 将完整 offer SDP 通过 HTTP 发到手机对外地址
7. 收到 answer 后 `setRemoteDescription`
8. 等待连接建立

### 8.2 server 行为

`rtc/server`：

1. 接收 offer
2. `setRemoteDescription`
3. `createAnswer`
4. `setLocalDescription`
5. 等待本地 ICE gathering 完成
6. 对 answer SDP 中 candidate 做固定地址段改写
7. 返回完整 answer

整个过程不传增量 ICE candidate，不再提供 trickle endpoint。

## 9. candidate 策略

这是本方案最关键的部分。

### 9.1 不使用 STUN/TURN

这里不向外部 STUN 服务器请求 `srflx` 地址，也不走 TURN 中继。

因此服务端不能依赖“自动发现手机代理地址”，而必须显式配置：

- `advertised_host`
- `advertised_udp_port_start`
- `advertised_udp_port_end`

示例：

- `advertised_host=192.168.1.83`
- `advertised_udp_port_start=40000`
- `advertised_udp_port_end=40031`

### 9.2 answer SDP 改写

参考 `standalone_signaling.py` / `sandbox/main.py` 里的 candidate 处理思路，服务端在 answer 生成后做两件事：

1. 过滤掉不需要暴露的 candidate
2. 把保留 candidate 的 `ip/port` 改成手机代理对外地址段

改写目标：

- `m=` 行端口
- `c=` 行地址
- `a=candidate` 行中的 `ip/port`

### 9.3 为什么只改 IP 不够

只改 IP 不够，端口也必须来自手机代理已经转发的 UDP 端口段。

也就是说，服务端内部实际用于 ICE/DTLS/SCTP 的 UDP socket，必须落在一个可预测的固定端口段内。否则就会出现：

- answer SDP 暴露了 `192.168.1.83:40012`
- 但服务端内部真实绑定的是随机端口 `51243`
- 手机代理并没有 `40012 -> 51243` 的映射
- 最终 ICE 检查失败

因此本设计要求服务端实现“固定 UDP 端口段分配”。

### 9.4 端口分配模型

服务端维护一个 UDP 端口池，例如：

- `40000-40031`

每个 WebRTC 会话从池中占用一个或一组端口。

第一版的设计目标是：

- 每会话优先占用一个固定 UDP 端口
- 该端口必须同时满足手机代理和服务端内部的 1:1 转发关系

如果 `aiortc` 默认收集逻辑不能直接绑定固定端口段，则实现上需要在 `rtc/server` 内增加一个端口分配层，并把 candidate 改写和端口占用绑定在一起。

这里先把它作为明确设计要求，而不是假设底层库会自动满足。

## 10. client / server 的职责划分

### 10.1 rtc/client

职责：

- 生成 non-trickle offer
- 发起 HTTP signaling
- 接收 answer
- 创建媒体接收端 transceiver
- 提供一个简单 CLI 验证命令

建议入口：

- `python -m rtc_client.cli`

建议功能：

- `connect`
- `close`
- `dump-remote-sdp`

### 10.2 rtc/server

职责：

- 提供 HTTP signaling API
- 管理 WebRTC session
- 分配和回收固定 UDP 端口
- 生成 non-trickle answer
- 改写 answer SDP candidate
- 提供至少一个媒体轨道

建议入口：

- `python -m rtc_server.cli`

## 11. 配置模型

### 11.1 server 配置

建议使用独立配置文件，例如：

```json
{
  "signal_listen_host": "0.0.0.0",
  "signal_listen_port": 18080,
  "advertised_host": "192.168.1.83",
  "udp_port_start": 40000,
  "udp_port_end": 40031,
  "session_ttl_seconds": 300,
  "ice_gather_timeout_ms": 8000
}
```

含义：

- `signal_listen_*` 是内部服务器监听地址
- `advertised_host` 是写入 answer SDP 的手机代理对外地址
- `udp_port_*` 是要求手机代理同步配置的 1:1 UDP 端口段

### 11.2 client 配置

示例：

```json
{
  "offer_url": "http://192.168.1.83:18080/api/v1/webrtc/offer",
  "ice_gather_timeout_ms": 8000,
  "data_channel_label": "control"
}
```

## 12. 会话生命周期

1. `client` 创建会话并发 offer
2. `server` 分配会话 ID 和 UDP 端口资源
3. `server` 生成并改写 answer
4. 双方建立 ICE / DTLS / SCTP
5. DataChannel 可收发
6. 任一侧关闭，或 TTL 超时，服务端回收会话和 UDP 端口

## 13. 验证方式

第一阶段验收只要求 WebRTC 媒体协商打通。

### 13.1 启动顺序

1. 手机代理配置：
   - TCP `18080 -> 18080`
   - UDP `40000-40031 -> 40000-40031`
2. 在内部服务器启动 `rtc/server`
3. 在外部机器启动 `rtc/client`

### 13.2 成功标准

- `client` 能通过 `http://192.168.1.83:18080/api/v1/webrtc/offer` 获得 answer
- answer SDP 中不包含 STUN/TURN 派生地址
- answer SDP 中 candidate 地址为手机代理地址段
- `client` 侧 `pc.connectionState` 进入 `connected` 或 `completed`
- `client` 能收到服务端的远端视频轨道，或至少进入可消费远端媒体的状态

## 14. 风险与约束

### 14.1 最大风险

最大风险不是 HTTP signaling，而是“服务端如何稳定使用固定 UDP 端口段”。

这件事必须在实现阶段优先验证。因为：

- 如果底层库只会随机选本地 UDP 端口
- 而我们又不能把这些随机端口同步映射到手机代理
- 那么仅靠 SDP 改写无法保证 ICE 成功

所以实现阶段的第一优先级不是 UI，也不是完整业务协议，而是：

- 验证 `aiortc` 是否可被约束到固定 UDP 端口段
- 如果不能，及时在 `rtc/server` 内收敛到可控的端口分配实现

### 14.2 第一阶段主动不做

- 不做 trickle ICE
- 不做 STUN/TURN 配置
- 不做浏览器兼容性优化
- 不做音视频轨道
- 不做多租户复杂鉴权

## 15. 实现建议顺序

确认设计后，按这个顺序开发：

1. 生成 `rtc/client` 和 `rtc/server` 两个独立目录骨架
2. 先完成 `server` 的 HTTP signaling 和会话管理
3. 先用单路 `video` 做最小 WebRTC 闭环
4. 做 answer SDP candidate 改写
5. 做固定 UDP 端口段分配
6. 联调手机代理 UDP 端口段
7. 最后再决定是否叠加音视频

## 16. 当前推荐默认值

- 手机信令入口：`192.168.1.83:18080/tcp`
- 内部服务器信令监听：`10.38.204.90:18080/tcp`
- 手机 UDP 暴露段：`192.168.1.83:40000-40031/udp`
- 内部服务器 UDP 段：`10.38.204.90:40000-40031/udp`
- 信令模式：`non-trickle`
- STUN/TURN：`disabled`
- 首阶段验证对象：单路 `video` 协商
