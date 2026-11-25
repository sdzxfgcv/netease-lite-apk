# 网易云轻听（NeteaseLite）

基于网易云音乐五种客户端公开接口的轻量级 Android 播放器，**自动择优、无需手动切换**；GitHub Actions 云构建，APK 可直接下载安装。

## 功能

- 搜索单曲、榜单浏览（飙升榜 / 新歌榜 / 热歌榜 / 原创榜）
- 在线播放、进度拖动、播放/暂停、封面显示
- 长按歌曲查看歌词
- 下载可播放的歌曲到 `音乐/网易云轻听/`
- **自动择优**：内置五套接口，启动时探测速度、失败自动降级、成功即记住；搜索/榜单/歌词/播放四类接口各自独立选优
- 没有第三方 SDK、没有广告、没有后台服务

## 接口说明

| 模式 | 协议 | 端点 / 参数 |
| --- | --- | --- |
| 网页版 | weapi（双层 AES-CBC + RSA） | `https://music.163.com/weapi/...` |
| 手机端 | eapi（AES-ECB + MD5 签名） | `eapi`，`os=android` |
| 电脑端 | linuxapi（AES-ECB eparams） | `https://music.163.com/api/linux/forward` |
| macOS | eapi | `eapi`，`os=osx` |
| Windows | eapi | `eapi`，`os=pc` |

macOS / Windows 客户端和手机端同属 eapi 协议，只是 `os` 参数与 UA 不同；实测三者均可用，且 eapi 系列对播放地址的覆盖明显好于网页版/电脑端。

使用到的接口：搜索 `/api/cloudsearch/pc`、榜单 `/api/v6/playlist/detail`、播放地址 `/api/song/enhance/player/url/v1`、歌词 `/api/song/lyric`。

## 自动择优逻辑

1. 启动后后台探测五套接口的响应速度，选出搜索/榜单/歌词的最优接口；
2. 播放地址优先手机端 eapi，依次尝试直到拿到可用地址；
3. 任何请求失败自动换下一个接口，成功则记住该接口，下次直接使用；
4. 顶部标签显示当前自动选中的「搜索/播放」接口，如 `自动·手机/Mac`；点击标签可改为固定某一接口（自动是默认且推荐的选项）。

## 下载

- Release v1.1：https://github.com/sdzxfgcv/netease-lite-apk/releases/tag/v1.1
- Actions Artifacts 中也保留每次构建的 APK（`netease-lite-apk`）。

## 本地构建

```bash
# 需要 JDK 17 + Android SDK：platforms;android-35 与 build-tools
export ANDROID_HOME=/path/to/android-sdk
bash build.sh
# 输出：output/NeteaseLite-v1.1.apk
```

签名使用仓库内的 `debug.keystore`（alias `netease`，密码 `android`），保证每次构建升级安装不会签名冲突。

## 限制与声明

- 非官方接口，随时可能变更；自动择优会在接口失效时降级到其他接口。
- 仅播放接口正常返回地址的歌曲；不提供任何解锁、破解或绕过 VIP/版权的手段。
- 播放地址有时效，每次播放均重新获取；歌词与封面来自公开接口。
- 本项目仅供技术学习与个人使用，请支持正版音乐，使用第三方接口的风险由使用者自行承担。
