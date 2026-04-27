# Android Scaffold

这个目录包含手机代理 App 的源码骨架：

- 规则模型
- 本地规则持久化
- 规则列表页
- 规则编辑弹窗
- 前台服务入口

当前机器缺少 `java`、`gradle`、Android SDK 和 `adb`，所以这部分源码尚未在本机编译验证。

后续建议在 Android Studio 中打开 `android/` 目录完成：

1. SDK 安装
2. Gradle 同步
3. 真机调试
4. 将 Python 参考实现中的 TCP/UDP 转发逻辑完整移植到 `PhoneProxyService`
