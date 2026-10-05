# 纯净代理 Android / Pure Proxy

基于 [v2rayNG 1.10.32](https://github.com/2dust/v2rayNG/tree/1.10.32) 的 Android 代理客户端，采用 Xray 内核，增加按分组管理的链式代理设置。应用免费，使用本地二维码识别，不包含广告 SDK、购买或付费解锁。

当前版本：**1.1.2**。Android **7.0+**。本仓库公开客户端源码，不提供代理服务器或节点订阅。

## 下载安装

在 [Releases 下载页](https://github.com/kongwen686/pure-proxy-android/releases/latest) 获取已签名、可安装的 Release 包：

- [arm64 安装包](https://github.com/kongwen686/pure-proxy-android/releases/download/v1.1.2/pure-proxy-1.1.2-arm64-v8a.apk)：适合大多数现代 Android 手机，体积较小。
- [通用安装包](https://github.com/kongwen686/pure-proxy-android/releases/download/v1.1.2/pure-proxy-1.1.2-universal.apk)：包含 arm64-v8a、armeabi-v7a、x86、x86_64；不确定架构时选择这个。
- [安装和使用说明](docs/INSTALL_USAGE.md)：包括覆盖升级、扫码导入、连接、链式代理及分应用设置。

1.1.2 提前将服务提升为前台服务，使用可见的低优先级连接通知，补充幂等退出清理、后台运行设置和仅存本机的退出记录。沿用 1.0.0 / 1.1.0 / 1.1.1 的发布签名，提高安装版本号。此改进不能证明所有意外断开均已解决，设备上的后台限制仍须由用户授权。GitHub Actions 的 Debug 构建产物使用测试签名，请使用 Releases 中的 Release 包升级。

## 功能

- 导入二维码、二维码图片、剪贴板链接、订阅及手动配置。
- 支持 Shadowsocks、VLESS/REALITY、VMess、Trojan、SOCKS、HTTP 等上游协议。
- VPN、分应用代理、自定义路由、DNS、节点测速和流量统计。
- 后台运行设置入口、电池优化和通知状态、可复制的不含节点信息的 VPN 事件记录。
- 普通单节点代理；中转 → 出口的两跳链式代理；可选前置节点的三跳链路。
- 链路按分组保存，以节点 GUID 关联；改名保持关联，节点缺失或出口失败不自动切回中转出口。
- 删除推广入口，关闭 Android 自动备份，保留作者署名及依赖许可证。

详细使用说明见 [CHAIN_PROXY.md](CHAIN_PROXY.md)，隐私说明见 [PRIVACY_PURE_PROXY.md](PRIVACY_PURE_PROXY.md)。本项目并非 Shadowsocks 官方客户端的完整界面或插件复制。

## 节点与敏感信息

源码不包含个人节点 IP、真实代理账号密码、订阅 URL/令牌、实际节点 UUID、截图、配置导出或发布签名私钥。首次运行后，需要自行导入节点。

配置模板仅使用不可连接的 `.invalid` 域名和明确的演示凭据。源码中的公共 DNS 地址、回环/局域网地址、路由网段、公共测试服务以及本地测试中的 `demo` 凭据属于运行或测试所需内容，并非预置代理节点。

公开版本从干净的初始提交开始，不携带本地开发历史。修改后发布前，可运行 `python3 tools/audit_public_source.py` 检查受 Git 跟踪的文件；自动检查不能替代人工审查。

## 从源码构建

需要 Git、Python 3.9+、Java 17、Android SDK 35 和 Build Tools 35.0.0。Gradle wrapper 固定为 8.14.3，已提供校验值。

```sh
git clone --recurse-submodules https://github.com/kongwen686/pure-proxy-android.git
cd pure-proxy-android
python3 tools/prepare_native.py
```

`prepare_native.py` 从上游官方的固定发布版本下载原生输入，先核验所有 SHA-256，再提取库和路由数据库。不会下载最新未固定的内核，也不会接触个人节点或签名密钥。校验和与对应源码版本见 [NATIVE_PROVENANCE.json](NATIVE_PROVENANCE.json)。这些生成文件不提交到 Git。

设置 `ANDROID_HOME` 或在 `V2rayNG/local.properties` 指定 `sdk.dir`，然后：

```sh
cd V2rayNG
./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug
```

测试 APK 在 `V2rayNG/app/build/outputs/apk/fdroid/debug/`。FOSS 包标识为 `io.pureproxy.android.foss`。Debug 包使用本机生成的测试签名，不能覆盖不同签名的已有安装。发布 Release 时，请自行创建并保管签名密钥；本仓库和 CI 不提供个人发布密钥。

GitHub Actions 会准备固定原生输入、检查公开源码、运行单元测试并构建 FOSS Debug APK，无需设置签名 Secrets。

### 原生源码

原生源码作为固定版本的 Git 子模块提供，并保留上游构建脚本。对应 AAR 的 AndroidLibXrayLite 源码固定为 `29e5a05161842f62f2dfecbb6796be08327c004b`（v25.12.2）。其他子模块版本见 `NATIVE_PROVENANCE.json`。快速构建复用上游已发布输入，不代表已经从头重建或独立审计所有原生依赖。

## 验证范围

单元测试覆盖导入地址校验、链路关联与配置、保存回退及设置页状态。`tools/check_proxy_chain.py` 可用桌面 Xray 验证本地单跳、两跳、三跳和出口停机行为，运行方式见 `CHAIN_PROXY.md`。

**Not run：**自动构建不能验证 Android 真机上的 VPN 授权、生命周期、触摸/TalkBack、升级签名兼容或第三方节点的真实出口。请使用自己的节点在设备上验收。

## 许可与来源

本项目遵循上游 **GPL-3.0** 许可证，保留 [LICENSE](LICENSE)、上游源码和作者署名。应用内依赖许可位于 `V2rayNG/app/src/main/assets/open_source_licenses.html`。原始上游说明见 [README_UPSTREAM.md](README_UPSTREAM.md)。

- Android 客户端基础：[2dust/v2rayNG](https://github.com/2dust/v2rayNG)
- 原生绑定：[2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)
- 内核：[XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- 其他原生组件的仓库与许可保留在各子模块中。

提交 Issue 时请先删去真实服务器地址、完整节点链接、密码、UUID、订阅令牌及个人信息。
