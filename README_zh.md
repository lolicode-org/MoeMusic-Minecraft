# MoeMusic Velocity 代理端插件

简体中文 | [English](./README.md)

MoeMusic for Velocity 是专为 Velocity 代理服务器设计的同步音乐播放插件。插件运行于 Velocity 代理层，在整个群组网络中统筹全局音乐播放列表。安装了 MoeMusic 客户端模组的玩家在各个子服之间跨服切换时，无需重新建立音频连接，即可享受无缝、不间断的同步音乐体验。

---

## 快速开始

1. 下载最新版本的 `moemusic-velocity-*.jar` 发布文件。
2. 将 JAR 文件放入 Velocity 代理端的 `plugins/` 目录中。
3. 启动一次代理端，将在 `plugins/moemusic/` 目录下自动生成配置文件，按需编辑它们。
4. 前往[音源插件列表](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8)下载所需音源插件，并放入 `plugins/moemusic/plugins/` 目录。
5. 重启代理端。
6. 安装了 MoeMusic 客户端模组的玩家在群组网络的任意子服中均可按 `M` 键打开播放器界面，或在聊天栏中使用 `/music` 指令进行交互。

> [!IMPORTANT]
> **请勿**在后端子服上针对同一批玩家重复安装子服版 MoeMusic（如 Spigot 插件或 Fabric 模组）。Velocity 代理端会直接管理统一的客户端音频会话并进行全网调度。

> [!WARNING]
> 默认情况下，直接提交 HTTP/HTTPS 直链音频需要显式分配管理员权限（`moemusic.admin.source.http`），以防范未受信任主机的安全风险。我们强烈建议安装知名音乐服务的音源插件，而不是直接放开直链点歌。

---

## 功能特性

MoeMusic 将模块化音频核心与 Velocity 代理网络能力深度结合：

### 1. 核心引擎功能
- **跨平台音频解码**：基于 `lavaplayer` 引擎，支持 MP3、OGG、WAV、FLAC、M3U 与 PLS 等主流音频与播放列表格式。
- **可扩展的插件系统**：支持将独立音源 JAR 插件放入 `plugins/moemusic/plugins/` 目录，动态接入自定义音乐平台。
- **内容过滤与安全策略**：支持在代理端配置歌曲、艺术家、关键词及正则表达式过滤规则，并限制单曲最大播放时长。
- **媒体防火墙**：客户端内置网络安全防火墙，根据规则校验媒体链接，防止玩家 IP 地址泄露并防范恶意网络请求。
- **请求限流保护**：在代理端统一限制单玩家的点歌与搜索频率，避免高频调用导致第三方接口被封禁。
- **音量平衡**：自动平衡不同歌曲的输出电平，提供更加自然一致的收听体验。

### 2. Velocity 代理端特性
- **跨服无缝播放**：玩家在群组网络的各个子服之间跨服切换时，音乐连续播放不中断、不重置。
- **原生 Brigadier 指令树**：借助 Velocity 原生 Brigadier 引擎提供流畅的指令输入与自动补全体验。
- **Adventure 富文本交互**：提供美观的聊天栏消息，附带可直接点击的交互按钮（如 `[✕]` 删除曲目、`< (当前页 / 总页数) >` 翻页等）。
- **全网切歌投票**：支持代理端全网在线收听玩家共同发起和参与切歌投票。
- **权限系统适配**：深度适配 Velocity 原生权限接口与 Velocity 版 LuckPerms 权限插件。

---

## 支持版本

- Velocity 4.0.0 及以上版本。3.x 旧版本*可能*也能工作，但我们不对此提供任何保证。

> [!NOTE]
> 虽然 velocity 并不限定后端服务器的版本，这意味着你可以借助 velocity 让本插件在低版本工作，
> 但配套的模组端仅支持 1.18.2、1.19~1.19.2、1.20(.1)、1.21(.1)、26.1 及以上的版本，
> 故若要在其他版本上使用本插件，你可能需要配合 ViaVersion 等跨版本兼容插件。

---

## 插件与语言文件安装

### 安装音源插件
1. 将兼容的音源插件 JAR 文件放入 `plugins/moemusic/plugins/` 目录（若目录不存在可手动创建）。
2. 重启代理端。

> [!TIP]
> 可以在 [插件列表](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8) 中查看由官方或社区维护的音源插件。

### 自定义与覆盖语言文件
你可以自定义修改默认提示信息，或为未汉化的第三方插件添加中文翻译：
1. 确定目标命名空间（核心功能命名空间为 `moemusic`；插件命名空间为插件 ID 冒号前缀，如 `bilibili:main` 的命名空间为 `bilibili`）。
2. 在代理端配置目录下创建对应文件夹：`plugins/moemusic/lang/<命名空间>/`。
3. 将自定义的 JSON 语言文件放入该文件夹中（如 `zh_cn.json` 或 `en_us.json`）。
4. 执行 `/music reload all` 即可立即加载生效。

---

## 指令列表

### 常用玩家指令
```text
/music <链接或ID>
/music add <链接或ID>
/music search [--source <源ID>] [--page <页码>] <搜索词>
/music queue [页码]
/music list [页码]
/music skip
/music pause
/music resume
/music stop
/music remove <序号>
/music clear [self]
```

### 管理员与管理指令
```text
/music add --now <链接或ID>
/music addById <源ID> <歌曲ID>
/music select <源ID> <选项ID>
/music choices <会话ID> [页码]
/music clear [all|<玩家名>]
/music system
/music reload <all|filter|autoplay>
/music filter track <ban|unban|toggle> <源ID> <歌曲ID> [备注]
/music filter artist <ban|unban|toggle> <源ID> <艺术家ID> [备注]
```

---

## 配置说明

配置文件位于 `plugins/moemusic/moemusic.toml`。

### 核心配置项解析
- `default_source_id`：默认搜索与模糊链接解析所优先使用的音源。
- `default_language`：代理端控制台输出及未安装客户端模组的玩家的备用回退语言（如 `zh_cn`、`en_us`）。
- `vote_required_percent`：切歌投票通过所需的全网在线收听玩家百分比（例如设置为 `51` 表示简单多数赞成即切歌）。
- `autoplay`：队列空闲时的自动轮播选项及各音源最大连续贡献曲目数。
- `content_filter`：代理端强制执行的歌曲、艺术家、标题关键词与正则表达式过滤规则。
- `media`：媒体防火墙规则、频率限制阈值、搜索结果页面上限及歌曲播放时长限制。

---

## 权限节点

在 Velocity 上，MoeMusic 结合代理端权限系统（如 LuckPerms）进行权限判定：
- **0 级权限节点**：默认对所有玩家开放。
- **管理与管理员权限节点**：需要显式赋予权限（`true`）。
- 代理端控制台无条件绕过所有权限检查。

| 权限节点 | 权限说明 | 默认等级 |
| --- | --- | --- |
| `moemusic.common.submit` | 提交歌曲加入播放队列 | 0 |
| `moemusic.common.submit.skip_autoplay` | 点歌时立即切掉当前自动播放的曲目 | 0 |
| `moemusic.common.vote` | 发起或参与切歌投票 | 0 |
| `moemusic.common.view_queue` | 查看当前播放列表与待选歌曲 | 0 |
| `moemusic.common.search` | 搜索各音源歌曲 | 0 |
| `moemusic.moderation.queue_control` | 强制切歌、置顶播放、清空队列及移除他人歌曲 | 1 |
| `moemusic.moderation.playback_control` | 暂停、恢复、停止及调整播放进度 | 1 |
| `moemusic.moderation.autoplay_refresh` | 手动刷新自动播放曲目 | 1 |
| `moemusic.moderation.filter_manage` | 查看与修改内容过滤规则 | 2 |
| `moemusic.admin.reload` | 重载代理端配置与插件 | 4 |
| `moemusic.admin.system.info` | 查看系统、插件及运行时状态信息 | 4 |
| `moemusic.admin.source.http` | 提交直接 HTTP/HTTPS 音频直链 | 4 |
| `moemusic.privilege.bypass.filter` | 绕过代理端内容过滤器检查 | 1 |
| `moemusic.privilege.bypass.duration_policy` | 绕过单曲最大时长限制 | 2 |
| `moemusic.privilege.bypass.rate_limit` | 绕过点歌与搜索频率限制 | 2 |

---

## 开发者资源与项目链接

- **核心库与插件 API**：[lolicode-org/MoeMusic](https://github.com/lolicode-org/MoeMusic)
- **音源插件开发模板**：[MoeMusic-source-template](https://github.com/lolicode-org/MoeMusic-source-template)

---

## 开源协议

AGPL-3.0-or-later

---

## 致谢

- [lavaplayer](https://github.com/lolicode-org/lavaplayer) - 核心音频解码与播放引擎
- [ktoml](https://github.com/orchestr7/ktoml) - TOML 配置文件支持
- [wire](https://github.com/square/wire) - Protobuf 数据包序列化
- [Kotlin](https://kotlinlang.org/) - 开发语言
