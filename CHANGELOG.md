## 2026-08-17 (v1.4.1)

- Fixed an issue where the queue clear button in the music player UI and context menu remained active when connected to legacy v2 servers.
- Relocated the client-only settings notice to the settings screen subtitle under the title.


- 修复了连接至旧版 v2 协议服务端时，播放器界面和右键菜单中的清空队列按钮仍然可点击的问题。
- 将仅本机生效的设置提示移至设置界面标题下方的副标题处展示。

---

## 2026-08-17 (v1.4.0)

- This release introduced no breaking changes, old clients, servers and plugins will keep working, but upgrading is strongly suggested to benefit from its performance improvements.
- Added support for Spigot / Paper server and Velocity proxy platforms.
- Upgraded the network communication protocol to v3, introducing payload compression and packet chunking/reassembly framing for large payloads, and increasing max payload size to 2 MB with backward compatibility for v2 clients and servers.
- Implemented queue and container selection pagination to improve performance for long playlist.
- Added queue clearing functionality with the `/music clear` command and a clear button in the music player UI.
- Fixed track deletion collisions and target ambiguity when duplicate tracks exist in the queue.
- Added permission level 5 (`LEVEL_DISABLED` / Console Only) for vanilla Minecraft environments without permission mods, allowing server administrators to disable specific MoeMusic actions for all players (Including Operators).
- Enhanced network security and resilience, illegal packets will be dropped as early as possible.
- Fixed an issue where client-to-server and server-to-client channels were not registered properly on vanilla-like or proxy servers.
- Raised public API version to 2.2.0, standardizing baseline runtime libraries and compiler target baselines for maximum cross-version compatibility.


- 该版本没有引入破坏性更改，此前的服务端、客户端和插件都可以和该版本配合使用；但本次更新极大地优化了特定场景下的性能，建议升级。
- 新增了对 Spigot / Paper 服务端与 Velocity 代理服平台的支持。
- 网络通信协议升级至 v3，引入了数据包压缩与大负载分片/重组机制，并将最大数据包容量提升至 2 MB，同时保持对 v2 协议客户端和服务端的向下兼容。
- 实现了队列与容器选择列表的分页机制加载，以提升长列表下的性能。
- 新增了清空队列功能，包括 `/music clear` 指令和播放器界面中的清空按钮。
- 修复了队列中存在重复曲目时删除目标歧义和 ID 冲突的问题。
- 为未安装权限模组的原版 Minecraft 环境新增了 5 级权限（`LEVEL_DISABLED` / 仅控制台），允许服主对所有玩家（包括管理员）默认禁用特定的操作。
- 增强了网络安全与稳定性，异常数据包将会被尽早拦截。
- 修复了在类原版服务端和代理服上客户端与服务端自定义通信通道未正确注册的问题。
- 公共 API 版本提升至 2.2.0，规范了标准运行时基础库和编译基线，以确保插件在各版本 Minecraft 上的兼容性。
