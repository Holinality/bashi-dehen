# 巴士得很

巴士得很是一个用 Vibe Coding 开发的公交查询 App，主要代码由 AI 编写。

没有开屏广告，也没有信息流。打开就能查附近站点、公交方向和到站时间，也能看地铁线网、规划换乘。

## 下载

[下载 Android 安装包](https://bus.holinality.com/download)

日常使用直接下载安装包即可。仓库里的源码可以自己编译，默认使用独立包名和签名，能和正式版同时安装。

## 功能

- 搜线路和站点，查看双向到站、车辆位置及站序。
- 按定位查附近站点，收藏常用线路和候车站；不授权定位也能手动查。
- 查看地铁线网，用本地数据规划换乘。
- 配置自己的高德 Key 后，使用相关地图、地点搜索和联网规划。
- 城际、市域铁路查询，行程步骤和乘车码跳转。
- 浅色、深色和跟随系统主题。

实时公交取决于上游接口和当地数据，不能保证每座城市、每条线路都能查到。

## 源码版的地铁数据

仓库附带北京、上海、广佛和深圳的 OSM 基础线网，可以离线查站、规划连通路线。资料有缺口的地方会断开或分段显示。

这套数据没有运营时刻表，也没有实时列车位置。其他城市的完整资料、官方线网图片和精确描图没有放进仓库，和下载页的发行包有所不同。范围、来源和本地补充资料的方法见 [数据说明](docs/DATA_SOURCES.md)。

## 自己编译

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0 和 PowerShell 7。Linux/macOS 的 Bash 包装脚本也需要 `pwsh`。最低支持 Android 5.0。

Windows：

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-17'
$env:ANDROID_SDK_ROOT = 'C:/path/to/android-sdk'
./build.ps1
```

Linux/macOS：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build.sh
```

也可以复制 `local.properties.example` 为 `local.properties`，填写本机路径。构建会先跑测试，APK 和 SHA256 摘要在 `build/` 目录。单独运行测试用 `./test.ps1` 或 `bash test.sh`。

默认包名为 `com.holi.prdbus.opensource`，第一次构建会生成本机开发签名，不能覆盖正式版。长期分发自己的安装包时，请在仓库外保管签名库，并配置 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。不要上传实际配置、签名库、Cookie 或私人日志。

有权使用的额外资料可以通过 `APP_ASSETS_OVERLAY` 从本机目录加入。重新生成公开地铁目录可运行 `python data/rebuild_osm_catalog.py`。

## 高德 Key

仓库没有共享 Key。可以在应用设置里填写自己的 Web 服务 Key，并查看申请指南。没有 Key 时，公交基础查询和已有的离线地铁资料仍可使用。

权限、免费额度和收费以高德控制台为准。配置 Key 不代表取得数据再分发许可。

## 隐私与反馈

定位用来查附近站点和显示行程进度。查询时会按需要把关键词、站点或坐标发给对应服务商；开发者下载站不接收 GPS 轨迹。首次使用会弹出说明，设置里也能查看。[隐私说明](PRIVACY.md)

报问题请带上城市、线路、方向和复现步骤。截图先遮住个人信息，密钥和会话内容不要发到 Issue。[安全问题处理](SECURITY.md)

## 许可与致谢

代码采用 [MIT 许可证](LICENSE)。第三方代码和数据按各自的许可使用，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。

感谢提供参考的开源项目和开放数据贡献者。
