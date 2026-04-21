# Changelog

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
- UAC elevation via PowerShell `Start-Process -Verb RunAs`; cancelled prompts surface as exit 1223.
- Refuses to clobber real volumes or network shares holding the target letter (exit 85).
- Error glossary mapping script exit codes to Windows system error names.
- Handles script paths with spaces, parentheses, exclamation marks, percent signs, carets, ampersands, and unicode.
- Distinct exit codes for every failure mode (31 generic, 85 in-use, 87 bad args, 123 bad filename, 1223 UAC cancel).
- Errors go to stderr (`>&2`) while success messages go to stdout, so callers can filter cleanly.
