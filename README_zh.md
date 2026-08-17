# MoeMusic Spigot / Paper 服务端插件

简体中文 | [English](./README.md)

MoeMusic 是一款适用于 Spigot、Paper 及其衍生服务端的音乐播放插件。它在服务端统一管理音乐播放队列，支持点歌、搜索、投票切歌及播放控制。安装了 MoeMusic 客户端模组的玩家进入服务器后即可实时收听同步音频，并使用游戏内播放器界面。

---

## 快速开始

1. 下载最新版本的 `moemusic-spigot-*.jar` 发布文件。
2. 将 JAR 文件放入服务端的 `plugins/` 目录中。
3. 启动一次服务端，将在 `plugins/MoeMusic/` 目录下自动生成配置文件，按需编辑它们。
4. 前往[音源插件列表](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8)下载所需音源插件，并放入 `plugins/MoeMusic/plugins/` 目录。
5. 重启服务端。
6. 安装了 MoeMusic 客户端模组的玩家进入服务器即可按 `M` 键打开播放器界面，或在聊天栏中使用 `/music` 指令进行交互。

> [!WARNING]
> 默认情况下，直接提交 HTTP/HTTPS 直链音频需要管理员权限（等级 4），以防止恶意音频或未受信任的主机危害客户端安全。你可以通过分配 `moemusic.admin.source.http` 权限或在配置文件中降低 `permissions.source_http_submit` 等级来允许普通玩家提交直链。
> 我们强烈建议安装知名音乐服务的音源插件，而不是直接放开直链点歌。默认配置下所有玩家均可直接提交受支持音源的歌曲链接或歌曲编号，既能保证服务器安全，又能提供更佳的使用体验。

---

## 功能特性

MoeMusic 将模块化的核心音频引擎与 Spigot 服务端生态深度结合：

### 1. 核心引擎功能
- **跨平台音频解码**：基于 `lavaplayer`，可解码 MP3、OGG、WAV、FLAC 等几乎所有主流音频格式，并支持 M3U 与 PLS 播放列表。
- **可扩展的插件系统**：支持将独立的音源 JAR 插件放入 `plugins/MoeMusic/plugins/` 目录，动态扩展音乐解析平台。
- **内容过滤与安全策略**：支持在服务端配置歌曲、艺术家、关键字及正则表达式过滤规则，并限制单曲最大播放时长。
- **媒体防火墙**：客户端内置网络防火墙机制，依据黑白名单规则校验媒体直链，防止玩家 IP 地址泄露并防范恶意服务器。
- **请求限流保护**：限制单用户的点歌与搜索频率，防止高频滥用与第三方平台接口封禁。
- **音量平衡**：自动平衡不同曲目的响度，带来更加平稳舒适的听歌体验。

### 2. Spigot 服务端特性
- **全服同步播放**：实时向所有在线客户端同步音频播放进度，实现多玩家协同收听。
- **交互式聊天栏体验**：提供富文本格式的消息提示，附带可直接点击的交互按钮（如 `[✕]` 删除曲目、`< (当前页 / 总页数) >` 翻页等）。
- **智能补全提示**：为指令子命令、已加载音源、操作目标及配置项提供完整的按键补全支持。
- **切歌投票机制**：普通玩家可发起和参与切歌投票，按在线收听玩家比例自动判定是否跳过。
- **权限深度集成**：完整适配 Bukkit 标准权限体系与 LuckPerms 权限管理插件。

---

## 支持版本

- Spigot / Paper 1.18.2 及以上版本。

> [!NOTE]
> 虽然理论上，借助插件服务端自身的兼容性，该插件可以在任何使用 JAVA 17 及以上版本的 spigot 兼容服务端运行，
> 但配套的模组端仅支持 1.18.2、1.19~1.19.2、1.20(.1)、1.21(.1)、26.1 及以上的版本，
> 故若要在其他版本上使用本插件，你可能需要配合 ViaVersion 等跨版本兼容插件。

---

## 插件与语言文件安装

### 安装音源插件
1. 将兼容的音源插件 JAR 文件放入 `plugins/MoeMusic/plugins/` 目录（若目录不存在可手动创建）。
2. 重启服务端。

> [!TIP]
> 可以在 [插件列表](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8) 中查看由我们或第三方开发者维护的音源插件。

### 自定义与覆盖语言文件
你可以自定义修改默认提示信息，或为未汉化的第三方插件添加中文翻译：
1. 确定目标命名空间（核心功能命名空间为 `moemusic`；插件命名空间为插件 ID 冒号前缀，如 `bilibili:main` 的命名空间为 `bilibili`）。
2. 在服务端创建对应文件夹：`plugins/MoeMusic/lang/<命名空间>/`。
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

配置文件位于 `plugins/MoeMusic/moemusic.toml`。

### 核心配置项解析
- `default_source_id`：默认搜索与模糊链接解析所优先使用的音源。
- `default_language`：控制台输出及未安装客户端模组的玩家的备用回退语言（如 `zh_cn`、`en_us`）。
- `vote_required_percent`：切歌投票通过所需的在线收听玩家百分比（例如设置为 `51` 表示简单多数赞成即切歌）。
- `autoplay`：队列空闲时的自动轮播选项及各音源最大连续贡献曲目数。
- `permissions`：未安装权限插件时的原版权限等级回退要求（0–5 级）。
- `content_filter`：服务端强制执行的歌曲、艺术家、标题关键词与正则表达式过滤规则。
- `media`：媒体防火墙规则、频率限制阈值、搜索结果页面上限及歌曲播放时长限制。

---

## 权限节点

在使用 LuckPerms 等权限插件时，可直接为玩家或权限组分配权限节点。若未安装任何权限插件，MoeMusic 将遵循 `moemusic.toml` 中配置的回退等级（0–5 级）：
- **0 级**：所有玩家均可使用。
- **1–4 级**：仅服务器管理员（OP）可用。
- **5 级**：默认对所有普通玩家完全禁用（仅控制台可用）。
- 服务器控制台无条件绕过所有权限检查。

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
| `moemusic.admin.reload` | 重载服务端配置与插件 | 4 |
| `moemusic.admin.system.info` | 查看系统、插件及运行时状态信息 | 4 |
| `moemusic.admin.source.http` | 提交直接 HTTP/HTTPS 音频直链 | 4 |
| `moemusic.privilege.bypass.filter` | 绕过服务端内容过滤器检查 | 1 |
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
