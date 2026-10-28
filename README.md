# World Backup

Backups of your Hytale worlds that happen before the game can break them: before a new game version or a changed mod list first opens a world, on a schedule, at every clean shutdown, and when you press a button. Restore from a window at the next start, and undo the restore if you change your mind. Part of the Hledjolf utility family. MIT.

## Install

1. Put `WorldBackup-1.0.0.jar` in your server's `mods` folder (singleplayer: `%APPDATA%\Hytale\UserData\Mods\`, then enable it in the world's mod list).
2. Open the world. The first time World Backup sees a world, it backs it up.
3. Type `/worldbackup` for the window.

Backups go to `%LOCALAPPDATA%\WorldBackup\` (one folder for every world on the computer, with one space limit, 10 GB by default). Another drive is safer: set `BackupDir`.

**Singleplayer:** Hytale enables mods per world, so World Backup protects the worlds you enable it in, while they're open. **Servers:** every world on the server.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/worldbackup` (`/wbak`) | Opens the window | anyone |
| `/worldbackup list [world]`, `worlds`, `budget [world]` | Snapshots; every world in the backup folder; whether the space settings fit | anyone |
| `/worldbackup now [label]`, `milestone <label>`, `verify <id>` | Back up now; a pinned, named snapshot; check a snapshot's files | `worldbackup.create` |
| `/worldbackup restore <id>`, `restore cancel` | Restore at the next start (the world is backed up first, so it can be undone) | `worldbackup.admin` |
| `/worldbackup pin <id>`, `unpin <id>`, `prune`, `export <id>` | Protect a snapshot; stop protecting it; apply the keep settings; write a snapshot out as a plain folder | `worldbackup.admin` |

Operators and the singleplayer world's owner have both permissions. A server restores and pins only its own worlds.

## Settings

`mods/Hledjolf_WorldBackup/config.json`, or the settings page in Mod Settings Manager if it's installed (changes apply without a restart, except `BackupDir`): space limit, free space to keep, how often, skip if unchanged, only while players are on, a copy speed limit, how many to keep (24 recent, then one a day for 7 days, then one a week for 4 weeks, 2 shutdown snapshots), snapshots before updates and mod changes (3 kept), worlds to skip, the backup folder, player data, and notices to operators.

## Command line

The jar also runs outside the game, for when the server is stopped:

```
java -jar WorldBackup-1.0.0.jar help
java -jar WorldBackup-1.0.0.jar list "My World"
java -jar WorldBackup-1.0.0.jar restore <id> <server folder>
```

Commands: `worlds`, `list`, `budget`, `verify`, `export`, `restore` (into a stopped server). `--store <dir>` for another backup folder.

## Compatibility

Hytale server 0.7.4, dedicated servers and singleplayer. No dependencies. Never changes Hytale's own singleplayer backups.

## License

MIT, copyright (c) 2026 Hledjolf. See `LICENSE`.

## Building

JDK 25 and the Hytale launcher install (its server jar is the API, compile-only). `./gradlew build` makes `build/mods/WorldBackup-<version>-dev.jar`; `./gradlew release` makes the release jar and zip in `release/<version>/`. Another server jar: `-PhytaleServerJar=<path>`.
