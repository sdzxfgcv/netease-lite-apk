# 网易云轻听（NeteaseLite）

基于网易云音乐三端公开接口的轻量级 Android 播放器，GitHub Actions 云构建，APK 可直接下载安装。

## 功能

- 搜索单曲、榜单浏览（飙升榜 / 新歌榜 / 热歌榜 / 原创榜）
- 在线播放、进度拖动、播放/暂停、封面显示
- 长按歌曲查看歌词
- 下载可播放的免费歌曲到 `音乐/网易云轻听/`
- **三端接口一键切换**：网页版（weapi）、手机端（eapi）、电脑端（linuxapi）；任一接口失败时自动尝试其他接口
- 没有第三方 SDK、没有广告、没有后台服务，源码只有三个 Java 文件

## 接口说明

| 模式 | 协议 | 端点 |
| --- | --- | --- |
| 网页版 | weapi（AES-CBC 双层 + RSA） | `https://music.163.com/weapi/...` |
| 手机端 | eapi（AES-ECB + MD5 签名） | `https://interfacepc.music.163.com/eapi/...` |
| 电脑端 | linuxapi（AES-ECB eparams） | `https://music.163.com/api/linux/forward` |

使用到的接口：搜索 `/api/cloudsearch/pc`、榜单 `/api/v6/playlist/detail`、播放地址 `/api/song/enhance/player/url/v1`、歌词 `/api/song/lyric`。

界面顶部的「接口:网页版」标签可循环切换三种模式，选择会保存在本地。

## 下载

GitHub Actions 构建完成后，APK 在 Actions 运行的 Artifacts 中（`netease-lite-apk`），或在 Releases 页面（若有发布）。

## 本地构建

```bash
# 需要 JDK 17 + Android SDK：platforms;android-35 与 build-tools;34.0.0
export ANDROID_HOME=/path/to/android-sdk
bash build.sh
# 输出：output/NeteaseLite-v1.0.apk
```

签名使用仓库内的 `debug.keystore`（alias `netease`，密码 `android`），保证每次构建升级安装不会签名冲突。

## 限制与声明

- 非官方接口，随时可能变更；如遇大面积失败，可尝试切换接口模式。
- 仅播放接口正常返回地址的歌曲。VIP / 版权歌曲返回空地址，App 会提示「版权/VIP 限制」，**不提供任何解锁、破解或绕过手段**。
- 播放地址有时效，每次播放均重新获取；歌词与封面来自公开接口。
- 本项目仅供技术学习与个人使用，请支持正版音乐，使用第三方接口的风险由使用者自行承担。
