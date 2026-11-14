# AutoRestart

A small Paper plugin that restarts the server at a fixed time every day. It warns
players in advance, counts down the last seconds, and hands the actual relaunch to
a startup script so the server comes back up automatically.

The plugin does not start the server itself. It only shuts the server down and
writes a marker file that the startup script watches for.

## Features

- Daily restart at a configurable `HH:mm` time in a configurable time zone.
- Advance warnings at configurable minute marks (default: 30, 10, 5, 3 and 1 minute
  before the restart).
- Optional per-second countdown during the last N seconds.
- Players are kicked with a configurable message right before the shutdown.
- Writes `restart.flag` and then performs a normal shutdown, so a startup script can
  detect the restart request and launch the server again.
- All player-facing messages can be shown in Chinese or English.

## Requirements

- Java 21
- Paper 1.21.8 (the plugin is built against `paper-api` 1.21.8, `api-version: '1.21'`)

## Installation

1. Build the plugin (see [Building](#building)) or download `AutoRestart.jar`.
2. Put the jar into the server's `plugins/` folder.
3. Start the server once so that `plugins/AutoRestart/config.yml` is generated.
4. Adjust `restart-time`, `time-zone`, `warn-minutes`, `language`, etc.
5. Always start the server through the `start.bat` script described below, otherwise the
   server will shut down at the restart time and stay offline.

## Configuration

`plugins/AutoRestart/config.yml`:

| Key | Default | Description |
| --- | --- | --- |
| `restart-time` | `"03:00"` | Daily restart time in 24-hour `HH:mm` format. An invalid value is logged and replaced with `03:00`. |
| `time-zone` | `""` | `ZoneId` used to evaluate `restart-time`, e.g. `Asia/Shanghai`. Empty or invalid values fall back to the server's system time zone. |
| `warn-minutes` | `[30, 10, 5, 3, 1]` | Minute marks before the restart at which a warning is broadcast. Processed from largest to smallest; entries `<= 0` are ignored. An empty list restores the default values. |
| `final-countdown-seconds` | `10` | Broadcast a message every second during the last N seconds. Clamped to `0`-`60`; `0` disables the countdown. |
| `language` | `zh` | Language of all player-facing messages: `zh` or `en`. Any other value falls back to `zh`. |
| `prefix` | `""` | Optional override for the broadcast prefix. Empty means "use `prefix` from `lang/<language>.yml`". |
| `kick-message` | `""` | Optional override for the disconnect message shown when players are kicked. Empty means "use `kick-message` from `lang/<language>.yml`". |

### Language files

On startup the plugin extracts `plugins/AutoRestart/lang/zh.yml` and
`plugins/AutoRestart/lang/en.yml` from the jar if they do not exist yet. Both files
contain the same keys:

- `prefix` – broadcast prefix
- `kick-message` – message shown to kicked players
- `restart-warning-minutes` – minute warning, `{minutes}` placeholder
- `restart-warning-seconds` – per-second countdown, `{seconds}` placeholder

Edit the file of the language you use to customize the wording. If a key is missing
from the file in the data folder, the built-in text from the jar is used instead.
Deleting a language file makes the plugin extract it again on the next start.

## How the automatic restart works

At the configured time the plugin:

1. broadcasts the warnings and the final countdown;
2. kicks every online player with the kick message;
3. waits 2 seconds (40 ticks) so player data can be saved;
4. writes `restart.flag` to the server working directory (the directory the server
   process was started from, i.e. `System.getProperty("user.dir")`);
5. calls `Bukkit.shutdown()`.

`restart.flag` is the handshake with the startup script. The script starts the server,
waits for the process to exit, and only starts it again when the flag exists. A manual
`/stop` does not create the flag, so the script exits and the server stays offline.

An example `start.bat` (place it in the server directory that contains `paper.jar`):

```bat
@echo off
cd /d "%~dp0"
:loop
if exist restart.flag del /q restart.flag
java -Xms4G -Xmx4G -jar paper.jar nogui
if exist restart.flag goto loop
echo Server stopped without a restart request.
pause
```

The flag is deleted at the beginning of each loop iteration, so a stale flag cannot
cause an endless restart loop. The plugin also overwrites the flag with the text
`auto-restart` every time it requests a restart.

### Why `Bukkit.shutdown()` instead of `Bukkit.restart()`

Paper's in-process restart spawns a new server process by itself. Combined with the
`start.bat` loop, that would result in two server processes fighting over the same
world and port. The plugin therefore only performs a regular shutdown and lets the
wrapper script do the single relaunch. The flag file also keeps a manual stop from
being turned into a restart.

## Building

Requirements: JDK 21 and Maven 3.9+.

```bash
mvn package
```

The result is `target/AutoRestart.jar`. The build has no runtime dependencies besides
the Paper API, which is provided by the server.

## Project layout

```
src/main/java/com/autorestart/AutoRestartPlugin.java   scheduler, announcements, shutdown
src/main/java/com/autorestart/Lang.java                language file loading and placeholders
src/main/resources/config.yml                          default configuration
src/main/resources/lang/zh.yml                         Chinese messages
src/main/resources/lang/en.yml                         English messages
src/main/resources/plugin.yml                          plugin metadata
```
