# MoeMusic for Spigot

Server-side MoeMusic integration for Spigot 1.18.2 through 26.2. Players still
need the matching MoeMusic client mod to receive synchronized playback.

## Install

1. Build with Java 17 or newer using `./gradlew build`.
2. Copy `build/libs/moemusic-spigot-1.3.0.jar` to the server's `plugins/` directory.
3. Start the server once, then place music-source plugin JARs in
   `plugins/MoeMusic/plugins/`.

Spigot downloads and caches the Kotlin, kotlinx, and SLF4J runtime libraries
declared in `plugin.yml` from Maven Central on first startup. The first startup
therefore needs network access, unless the server's library cache is already
populated.

Configuration is generated under `plugins/MoeMusic/`. Standard Bukkit
permission nodes are used when explicitly configured; otherwise MoeMusic's
configured permission levels fall back to everyone for level 0 and operators
for levels 1 through 4.

## Compatibility

One plugin JAR supports the full version range. Spigot 1.18.2 through 1.20.6
limit plugin messages to 32,766 bytes; Spigot 1.21 and newer allow 1 MiB. The
plugin reads the runtime limit, removes lyrics from oversized playback
snapshots on old versions, and logs, reports, and skips a track if its required
playback data still cannot fit. Other oversized messages are logged, reported,
and dropped without truncation.

To verify compilation against the newest supported API:

```sh
./gradlew test -PspigotApiVersion=26.2-R0.1-SNAPSHOT
```
