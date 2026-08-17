# MoeMusic Plugin for Spigot / Paper

[简体中文](./README_zh.md) | English

MoeMusic is a music player plugin for Spigot, Paper, and compatible Minecraft server implementations. It manages a synchronized, server-wide music queue, enabling players to request, search, vote-skip, and manage tracks via in-game chat commands or the client GUI. Players with the MoeMusic client mod installed will hear synchronized music and can access the visual player interface.

---

## Quick Start

1. Download the latest `moemusic-spigot-*.jar` release.
2. Place the JAR file into your server's `plugins/` directory.
3. Start the server once to generate configuration files under `plugins/MoeMusic/`, edit them per your needs.
4. Download the [music source plugins](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8) you want to use and place them in `plugins/MoeMusic/plugins/`.
5. Restart the server.
6. Players with the MoeMusic client mod installed can press `M` to open the player GUI, or use `/music` commands in chat.

> [!WARNING]
> Direct HTTP/HTTPS audio links require permission level 4 (OP) by default to protect players from untrusted hosts. You can grant `moemusic.admin.source.http` or lower `permissions.source_http_submit` in the config to allow regular players to submit links.
> We strongly recommend installing source plugins for trusted music services instead of opening direct link submission. By default, every player can submit links or IDs from supported music sources, which provides a better experience while keeping the server safe.

---

## Features

MoeMusic combines a modular core playback engine with deep Bukkit/Spigot server integration:

### 1. Core Engine Features
- **Cross-Platform Audio Decoding**: Powered by `lavaplayer`. Decodes common audio formats including MP3, OGG, WAV, FLAC, and playlist structures like M3U and PLS.
- **Extensible Plugin System**: Load third-party music sources by dropping standalone JAR plugins into `plugins/MoeMusic/plugins/`.
- **Content Filtering & Safety Policy**: Server-enforced keyword, regex, artist, and track filters, coupled with maximum track duration limits.
- **Media Firewall**: Validates media URLs against whitelist/blacklist rules to protect client privacy and avoid untrusted hosts.
- **Rate Throttling**: Restricts user request rates on the server to prevent spam and API rate-limiting.
- **Loudness Normalization**: Automatically balances volume across tracks with varying loudness for a consistent listening experience.

### 2. Spigot Server Features
- **Synced Server Playlist**: Synchronizes audio playback progress in real time across all connected clients.
- **Interactive Chat Messages**: Formatted chat messages with clickable buttons (`[✕]` to remove, `< (page / total) >` to flip pages) for intuitive chat-based control.
- **Command Completion**: Full tab completion for subcommands, music sources, loaded playlists, and configuration targets.
- **Vote to Skip**: Allows players to initiate and participate in majority-based skip votes.
- **Permission Integration**: Fully compatible with standard Bukkit permissions and permission managers like LuckPerms.

---

## Supported Versions

- Spigot / Paper 1.18.2+.  *NOTE: Not all versions are supported by the client mod.*
- A single plugin JAR runs across all supported versions.

---

## Installing Plugins and Language Files

### Installing Music Source Plugins
1. Place compatible music source plugin JAR files into `plugins/MoeMusic/plugins/` (create the directory if it does not exist).
2. Restart the server.

> [!TIP]
> Check the [Plugins List](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8) to find source plugins maintained by us or third-party developers.

### Customizing Language Files
You can override default messages or add translations for third-party plugins:
1. Identify the namespace (`moemusic` for core features, or the plugin ID prefix before the colon, e.g. `bilibili` for `bilibili:main`).
2. Create the folder `plugins/MoeMusic/lang/<namespace>/`.
3. Place your custom JSON translation file inside (e.g. `en_us.json` or `zh_cn.json`).
4. Run `/music reload all` to apply changes.

---

## Commands

### Player Commands
```text
/music <link-or-id>
/music add <link-or-id>
/music search [--source <source>] [--page <page>] <query>
/music queue [page]
/music list [page]
/music skip
/music pause
/music resume
/music stop
/music remove <index>
/music clear [self]
```

### Admin & Moderation Commands
```text
/music add --now <link-or-id>
/music addById <source> <trackId>
/music select <source> <choiceId>
/music choices <sessionId> [page]
/music clear [all|<player>]
/music system
/music reload <all|filter|autoplay>
/music filter track <ban|unban|toggle> <source> <trackId> [note]
/music filter artist <ban|unban|toggle> <source> <artistId> [note]
```

---

## Configuration

The configuration file is located at `plugins/MoeMusic/moemusic.toml`.

### Key Server Settings
- `default_source_id`: The preferred music source for search queries and ambiguous link resolution.
- `default_language`: Fallback language for console output and chat messages (`en_us`, `zh_cn`, etc.).
- `vote_required_percent`: Percentage of online listening players required to pass a skip vote (e.g. `51` for majority).
- `autoplay`: Autoplay configuration and per-source track contribution limits when the queue is empty.
- `permissions`: Fallback permission level requirements (0–5) when no permission manager is installed.
- `content_filter`: Server-enforced track, artist, title keyword, and regex filter rules.
- `media`: Media firewall rules, rate limits, search result boundaries, and track duration limits.

---

## Permissions

When using a permission plugin such as LuckPerms, assign permission nodes directly. When no permission plugin is present, MoeMusic falls back to permission levels (0–5) defined in `moemusic.toml`:
- **0**: Allowed for all players.
- **1–4**: Reserved for server operators (OP).
- **5**: Completely disabled for players (console only).
- Server console bypasses all permission checks.

| Permission Node | Description | Default Level |
| --- | --- | --- |
| `moemusic.common.submit` | Submit songs to the queue | 0 |
| `moemusic.common.submit.skip_autoplay` | Skip autoplay track upon request | 0 |
| `moemusic.common.vote` | Vote to skip current track | 0 |
| `moemusic.common.view_queue` | View the song queue and choices | 0 |
| `moemusic.common.search` | Search music sources | 0 |
| `moemusic.moderation.queue_control` | Force skip, play now, clear queue, and remove others' songs | 1 |
| `moemusic.moderation.playback_control` | Pause, resume, stop, and seek playback | 1 |
| `moemusic.moderation.autoplay_refresh` | Manually refresh autoplay tracks | 1 |
| `moemusic.moderation.filter_manage` | Manage content filter rules | 2 |
| `moemusic.admin.reload` | Reload server configuration and plugins | 4 |
| `moemusic.admin.system.info` | View system, plugin, and runtime status | 4 |
| `moemusic.admin.source.http` | Submit raw HTTP/HTTPS audio links | 4 |
| `moemusic.privilege.bypass.filter` | Bypass content filter rules | 1 |
| `moemusic.privilege.bypass.duration_policy` | Bypass track duration limits | 2 |
| `moemusic.privilege.bypass.rate_limit` | Bypass request rate limits | 2 |

---

## Developer Resources & Project Links

- **Core Library & Plugin API**: [lolicode-org/MoeMusic](https://github.com/lolicode-org/MoeMusic)
- **Source Plugin Template**: [MoeMusic-source-template](https://github.com/lolicode-org/MoeMusic-source-template)

---

## License

AGPL-3.0-or-later

---

## Acknowledgements

- [lavaplayer](https://github.com/lolicode-org/lavaplayer) - Core audio decoding and playback
- [ktoml](https://github.com/orchestr7/ktoml) - TOML configuration support
- [wire](https://github.com/square/wire) - Protobuf packet serialization
- [Kotlin](https://kotlinlang.org/) - Development language
