## 2026-09-16 (v1.5.0)

- Added support for Minecraft 26.3.
- Updated api version to 2.3.0.
- Added automatic plugin deduplication; duplicate, incompatible, or broken plugins will no longer prevent the game from starting, and an in-game issue screen will display on startup to help diagnose and resolve them.
- Plugins built with modern templates can now also be installed directly into the "mods/" directory as standard Minecraft mods.
- Added configuration options to limit the maximum number of queued tracks and total queue duration allowed per player.
- Expanded request rate limiting to cover skip, vote, playback controls, and queue modifications, with corresponding settings in the config screen.
- Added an in-progress notice when attempting to skip a track that is already switching.
- Added delay and rate limiting to singleplayer automatic track skipping on playback failure to avoid freezing or flooding music sources when consecutive tracks fail.
- Added support for `fabric-permission-api-v1` on Fabric (26.1+).
- Added the missing "Allow duplicate track submission" option to the in-game permission configuration screen.
- Harmonized server logging policies across all platforms: removed some high frequency logs to prevent flooding, while added audit journals for track submissions, queue modifications, and skip votes.
- Formatted core server logs on Paper, Purpur, and Folia with the standard [MoeMusic] plugin prefix instead of full internal Java package names.
- Adjusted the default permission level and rate limit. Existing users won't be affected.

- 适配 Minecraft 26.3。
- 更新 API 版本到 2.3.0。
- 增加插件自动去重机制；重复、不兼容或损坏的插件将不再阻止游戏正常启动，并在启动时展示诊断报告界面以便排查问题。
- 使用新版模板构建的插件现已支持直接放入 "mods/" 目录作为普通模组安装。
- 新增单玩家待播放曲目数上限与总时长上限的配置项，防止单个玩家占用过多队列。
- 扩展了请求频率限制，支持对切歌、投票、播放控制及队列修改等操作进行限频，并可在配置界面中调整。
- 在曲目正在切换时尝试切歌将提示正在切换，避免重复触发。
- 为单人模式下播放失败的自动切歌增加了延迟与频次限制，避免连续失效曲目导致游戏卡顿或频繁请求音源。
- 在 Fabric 平台（26.1及以上版本）上新增对 `fabric-permission-api-v1` 的支持。
- 在游戏内权限设置界面中补全了“允许重复提交乐曲”的权限等级配置项。
- 统一各平台服务端的日志输出规范：移除了部分可能被高频触发的日志，同时添加了对点歌、队列变更及投票等关键操作的审计日志。
- 在 Paper、Purpur 及 Folia 服务端上统一将核心日志前缀格式化为标准的 [MoeMusic] 插件标签，而非显示完整的 Java 包名。
- 调整了默认的权限和频率限制数值。现有用户不会受到影响。
