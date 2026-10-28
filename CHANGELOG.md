# Changelog

## 1.0.0 (2026-10-27), Hytale 0.7.4

First release.

- **Snapshots before the game can change a world:** before a new game version first opens it (`pre-update`), and before it first opens with a mod added, removed or updated (`mod-change`). Both are taken in the plugin's `setup()`, before any world loads.
- **On a schedule** (every 60 minutes by default), skipped when nothing changed, and only while players are on, plus one when the last player leaves. **At every clean shutdown**, and **on demand** (`/worldbackup now`, or a named milestone that is never deleted automatically).
- **A first-run snapshot** the first time WorldBackup sees a world, kept like an update snapshot: it may be the only copy from before an update.
- **Restore at the next start,** from the window or `/worldbackup restore <id>`. The world as it is then is backed up and verified first (`pre-restore`), so every restore can be undone. A restore builds the world beside the live one and swaps by renaming; one interrupted by a crash finishes at the next start, and one that fails puts every file back and says why. A restore removes only files its snapshot covered (a snapshot taken without player data leaves today's player data alone), and never touches WorldBackup's own folder. A snapshot staged for a restore is kept from every prune, on every server sharing the backup folder.
- **Safe storage:** every file is flushed to disk before it's renamed into place; a damaged or short block is found by `verify` and written again by the next snapshot; one unreadable snapshot file never stops the others, and no space is freed while one can't be read.
- **Small backups:** files are stored in 64 KB blocks by content, once. A snapshot of a world where little changed costs little space.
- **One backup folder per computer** (`%LOCALAPPDATA%\WorldBackup` on Windows, or `BackupDir`), with one space limit for everything in it. Servers and saves that share it take turns through a lock file.
- **Thinning with age:** the 24 most recent scheduled snapshots, then one a day for 7 days, then one a week for 4 weeks (all settings). Milestones, the latest pre-restore snapshot and pinned snapshots are never removed by count.
- **Budget check** (`/worldbackup budget`): whether the settings fit the space limit and the drive, and roughly how much history they give.
- **Window** (`/worldbackup`): this server's worlds, other worlds in the same backup folder, and deleted ones; each world's snapshots; Back up now, Milestone, Restore, Pin, Verify, Export and Budget, each with its command shown beside it; the restore-pending bar with Cancel; a status line.
- **Deleted worlds stay recoverable:** their snapshots are kept, and `export` writes any snapshot out as a plain folder.
- **Settings** in `mods/Hledjolf_WorldBackup/config.json`, or in Mod Settings Manager if it's installed, where they apply without a restart. On a server with several worlds, each world gets its own section of overrides in Mod Settings Manager; a value not set there follows the server's setting.
- **Command line:** the same jar runs outside the game (`java -jar WorldBackup-1.0.0.jar`): worlds, list, budget, verify, export, and restore into a stopped server. It refuses a server that's still running, asks for `confirm` before restoring a snapshot from an older game build, and for `--into-other` before restoring a snapshot into a different folder.
- **Permissions:** `worldbackup.create` (now, milestone, verify) and `worldbackup.admin` (restore, pin, unpin, prune, export). Operators and the singleplayer world's owner have both.
- Never touches Hytale's own singleplayer backups. The window shows how much space they use.
