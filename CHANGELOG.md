## 2026-05-26 (v1.2.0)

- Fix the incorrect location of the instance lock file used in single-instance mode on Linux platform, which caused the instance lock to fail.
- Add more logs for issue troubleshooting and error reporting.
- Fix the issue where the first song cannot be played when joining a third-party server for the first time after launching the game.
- Optimize the logic of time synchronization between server and client.
- When the handshake fails, the client's prompt screen will display more detailed error information.
- 修复了在 Linux 平台上，单实例模式使用的实例锁文件位置不正确，导致实例锁失效的问题。
- 添加了更多日志，以便问题排查和错误报告。
- 修复了游戏启动后首次加入第三方服务器时，第一首歌无法播放的问题。
- 优化了服务器和客户端间时间同步的逻辑。
- 握手失败时，客户端的提示屏幕上会显示更详细的错误信息。

## 2026-05-24 (v1.1.0)

- Add support for Android platform (arm64 only).
- Trim some native libraries for old platforms to reduce the package size.
- 添加了对Android平台（仅限arm64）的支持。
- 修剪了一些旧平台的原生库以减少包的大小。

## 2026-05-24 (v1.0.1)

- Fix the render order of hud, ensuring it appears below any other elements.
- 修复了hud的渲染顺序，确保它出现在其他元素之下。

## 2026-05-23 (v1.0.0)

- Initial Public Release
- 首次公开发布
