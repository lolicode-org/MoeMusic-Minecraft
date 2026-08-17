# MoeMusic Proxy Plugin for Velocity

[简体中文](./README_zh.md) | English

MoeMusic for Velocity is a proxy-wide music player plugin. Running directly at the Velocity proxy layer, it coordinates a unified music queue across your entire server network. Connected players with the MoeMusic client mod enjoy uninterrupted, synchronized audio playback even when switching between backend servers.

---

## Quick Start

1. Download the latest `moemusic-velocity-*.jar` release.
2. Place the JAR file into the Velocity proxy's `plugins/` directory.
3. Start the proxy once to generate configuration files under `plugins/moemusic/`, edit them per your needs.
4. Download the [music source plugins](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8) you want to use and place them into `plugins/moemusic/plugins/`.
5. Restart the proxy.
6. Players with the MoeMusic client mod installed can press `M` to open the player GUI, or use `/music` commands in chat from any backend server.

> [!IMPORTANT]
> **Do not** install server-side MoeMusic adapters (such as the Spigot plugin or Fabric mod) on backend servers for players managed by this Velocity proxy. Velocity terminates the client-facing MoeMusic channel directly and coordinates all audio playback network-wide.

> [!WARNING]
> Direct HTTP/HTTPS audio links require explicit permission (`moemusic.admin.source.http`) by default to protect players from untrusted hosts. We strongly recommend installing music source plugins for trusted services rather than allowing raw link submissions.

---

## Features

MoeMusic combines an extensible playback engine with Velocity proxy capabilities:

### 1. Core Engine Features
- **Cross-Platform Audio Decoding**: Powered by `lavaplayer`. Supports MP3, OGG, WAV, FLAC, M3U, and PLS playback.
- **Extensible Plugin System**: Load standalone JAR plugins in `plugins/moemusic/plugins/` to support custom music platforms.
- **Content Filtering & Safety Policy**: Network-enforced keyword, regex, track, and artist filters, plus configurable track duration boundaries.
- **Media Firewall**: Validates media URLs against rules to guard client privacy and prevent untrusted network connections.
- **Rate Throttling**: Restricts user request rates on the proxy to prevent spam and upstream API exhaustion.
- **Loudness Normalization**: Automatically normalizes audio levels across tracks for a smoother listening experience.

### 2. Velocity Proxy Features
- **Seamless Cross-Server Playback**: Playback continues smoothly without interruption or reconnection when players transfer between backend servers.
- **Native Brigadier Commands**: Full tab completion and structured command execution powered by Velocity's native Brigadier engine.
- **Rich Adventure Chat Messages**: Interactive chat messages with clickable action buttons (`[✕]` to delete, `< (page / total) >` to paginate).
- **Network-Wide Vote Skip**: Allows all active listeners across the proxy network to participate in skip votes.
- **Velocity & LuckPerms Permissions**: Full compatibility with Velocity's native permission provider and LuckPerms.

---

## Supported Versions

- Velocity 4.0.0 and newer.

---

## Installing Plugins and Language Files

### Installing Music Source Plugins
1. Place compatible music source plugin JAR files into `plugins/moemusic/plugins/` (create the folder if it does not exist).
2. Restart the proxy.

> [!TIP]
> Visit the [Plugins List](https://github.com/lolicode-org/MoeMusic/wiki/Plugins---%E6%8F%92%E4%BB%B6%E5%88%97%E8%A1%A8) for available source plugins.

### Customizing Language Files
You can customize text prompts or provide translations for plugins:
1. Determine the namespace (`moemusic` for core features, or the plugin ID prefix before the colon, e.g. `bilibili` for `bilibili:main`).
2. Create the folder `plugins/moemusic/lang/<namespace>/`.
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

The configuration file is located at `plugins/moemusic/moemusic.toml`.

### Key Proxy Settings
- `default_source_id`: Preferred source for search queries and ambiguous link resolution.
- `default_language`: Default language for proxy console logs and fallback chat messages.
- `vote_required_percent`: Percentage of active proxy-wide listening players required to skip a track.
- `autoplay`: Autoplay options and track limits when the shared queue is empty.
- `content_filter`: Proxy-enforced track, artist, keyword, and regex filter rules.
- `media`: Media firewall rules, rate limits, page limits, and maximum track duration.

---

## Permissions

On Velocity, permission nodes are checked via the proxy's permission system (e.g. LuckPerms for Velocity). Undefined nodes fall back to default access levels:
- **Level 0 nodes**: Available to all players by default.
- **Moderation and Admin nodes**: Require explicit permission assignment (`Tristate.TRUE`).
- Proxy console bypasses all permission checks.

| Permission Node | Description | Default Level |
| --- | --- | --- |
| `moemusic.common.submit` | Request songs into the queue | 0 |
| `moemusic.common.submit.skip_autoplay` | Skip autoplay when requesting | 0 |
| `moemusic.common.vote` | Vote to skip current track | 0 |
| `moemusic.common.view_queue` | View the song queue and selection choices | 0 |
| `moemusic.common.search` | Search tracks across sources | 0 |
| `moemusic.moderation.queue_control` | Force skip, play now, clear queue, and remove others' songs | 1 |
| `moemusic.moderation.playback_control` | Pause, resume, stop, and seek playback | 1 |
| `moemusic.moderation.autoplay_refresh` | Manually refresh autoplay tracks | 1 |
| `moemusic.moderation.filter_manage` | Manage content filter rules | 2 |
| `moemusic.admin.reload` | Reload proxy configuration and plugins | 4 |
| `moemusic.admin.system.info` | View runtime, plugin, and source details | 4 |
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
