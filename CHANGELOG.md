# Changelog

## v0.4.0

### Added
- ANSI-colored output feat. OSC 8 clickable hyperlink for the GitHub URL
- `NO_COLOR=1` env var opts out of all coloring and hyperlinks, per [no-color.org](https://no-color.org)
- New `:setup_colors` subroutine called once at script entry. Centralizes terminal probing and class
  setup so the rest of the script just consumes the resulting variables.
- Renamed/new script-level vars `PROJECT_NAME` / `PROJECT_VERSION` / `PROJECT_URL`
- `FormatSpec` now also rejects UTF-8 BOM markers (`EF BB BF`) at the start of any tracked file.

### Changed
- `:usage` signature simplified to `Usage: mount-k.bat <options>`.
- `ScriptSpec.extraEnv` now sets `NO_COLOR=1` by default so plain-text substring assertions in the
  rest of the suite stay valid against the new colored output. Specs that exercise color (e.g.
  `ColorSpec`) opt out by overriding `extraEnv`.
- Every persistence and behavior spec updated to expect backtick-wrapped drives in mount/unmount
  echoes (`` `K:` drive mapped to `` instead of `K: drive mapped to`).
- `Build.scala` now reads the version from `set "PROJECT_VERSION=(v.+)"` (was `set "VERSION=(.+)"`).

## v0.3.0

### Added
- `PERSIST_MODE` setting controls how mount/unmount interacts with the HKLM boot-time registry entry. Four values:
  `always`, `never`, `if-suffixed` (default), `ask`. The mode is the source of truth for whether a mount/unmount
  also writes/deletes the registry entry.
- `MOUNT_PREFIX`/`UNMOUNT_PREFIX` (default `mount-`/`unmount-`) and `MOUNT_SUFFIX`/`UNMOUNT_SUFFIX` (default
  `-and-remember`/`-and-forget`) settings declare the filename templates the script accepts. Suffixed names opt
  in to registry persistence under `if-suffixed` mode and as the auto-accept default under `ask` mode.
- `mount-k-and-remember.bat` / `unmount-k-and-forget.bat` filename forms: mount/unmount and persist the mapping
  in (or remove from) the boot-time registry. `mount-k.bat` / `unmount-k.bat` stay session-only by default.
- `/PM <mode>` runtime CLI flag overrides `PERSIST_MODE` for the current invocation. The next argument is the
  mode value; the value is validated against the same allowlist as the hardcoded setting.
- Internal `ACTION` code model with four codes: `M` (mount), `M+R` (mount + persist registry), `D` (unmount),
  `D+F` (unmount + remove registry). Filename -> `FILE_ACTION`, then normalized by `PERSIST_MODE` and CLI flags
  into the runtime `ACTION` that actually executes. Makes "what is this run going to do" inspectable in one var.
- `:usage` now annotates each flag with what it will actually do given the current `PERSIST_MODE` and filename:
  `mount K: to <path> and persist across reboots`, `mount K: to <path> - ask before persisting into registry`,
  `unmount K: and remove the boot-time registry entry`, `, do not touch registry`, etc.
- `:usage` "filename is wrong" branch: when the script is run from a non-conforming filename, prints
  `Unexpected filename:` plus the four expected templates, plus a hint to rename to a conforming form to see
  real usage. Replaces the previous attempt to print full usage from a degenerate state.

### Changed
- **Breaking:** Filename matching is now exact-form against four templates: `mount-k`, `mount-k-and-remember`,
  `unmount-k` and `unmount-k-and-forget` (case-insensitive). The previous fuzzy stem-normalization is gone.
- **Breaking:** `mount-k.bat` and `unmount-k.bat` no longer touch the registry by default. The shipping default is
  `PERSIST_MODE=if-suffixed`, so registry persistence requires either the suffixed filename
  (`mount-k-and-remember.bat`/`unmount-k-and-forget.bat`), `/PM always` on the command line, or flipping the
  hardcoded `PERSIST_MODE`. The 0.2.0 default of "always persist" is reachable via `PERSIST_MODE=always`.
- `reg query` is now skipped unless `ACTION` ends in `+R`/`+F` or `PERSIST_MODE=ask`. There is no point reading
  the current registry value when no registry op can possibly happen on this run.
- `:mount_reg` and `:unmount_reg` no longer return silently on success: every outcome (write, no-op, decline,
  cancel, error) now produces a line on stdout or stderr.

## v0.2.0

### Added
- `/M` flag for explicit mount (previously no-args was the only way to mount).
- `unmount-x.bat` rename-to-flip-default: if the filename stem contains `unmount` (via `-` or `_` separators,
  e.g. `foo-unmount-k.bat`, `X_Unmount-k.bat`), the no-args default becomes unmount instead of mount.
- Combined-flag invocations: `/M /D` (either order) is accepted; `/M` always wins as a deliberate re-mount.
- Ambiguous filenames fail fast: if the stem doesn't contain `mount` or `unmount` as a recognizable token
  (e.g. `remount-k.bat`), running without `/M`/`/D` exits 87 with a clear error instead of silently picking a default.

### Changed
- Admin detection uses `reg query "HKU\S-1-5-19"` instead of `net session`. Removes dependency on the LanmanServer
  service, so the admin check works on systems where SMB server is disabled.
- `subst` output parsing tokenizes in pure cmd instead of piping through `findstr`. One fewer external utility.
  We now depend only on `subst`, `reg`, and `powershell`.
- `:usage` output lists `/M` and reflects the stem-derived default in the `<no args>` line.
- Argument parsing rewritten as a loop so flags can appear in any order and quantity.

## v0.1.0

Initial release.

- Mount any drive letter to the script's directory via `subst`. Drive is derived from the filename suffix
  (`mount-k.bat` -> `K:`, `mount-p.bat` -> `P:`).
- Persist the mapping across reboots by writing to `HKLM\...\Session Manager\DOS Devices`.
- Self-idempotent: mount + mount = mount; unmount + unmount = no-op with the registry left clean.
- `/D` flag unmounts and removes the registry entry.
- UAC elevation via PowerShell `Start-Process -Verb RunAs`; canceled prompts surface as exit 1223.
- Refuses to clobber real volumes or network shares holding the target letter (exit 85).
- Error glossary mapping script exit codes to Windows system error names.
- Handles script paths with spaces, parentheses, exclamation marks, percent signs, carets, ampersands, and unicode.
- Distinct exit codes for every failure mode (31 generic, 85 in-use, 87 bad args, 123 bad filename, 1223 UAC cancel).
- Errors go to stderr (`>&2`) while success messages go to stdout, so callers can filter cleanly.
