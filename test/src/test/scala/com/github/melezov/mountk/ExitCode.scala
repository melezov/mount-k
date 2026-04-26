package com.github.melezov.mountk

/** `mount-k.bat` exit codes. `SUCCESS` is the ordinary 0; everything else is a Windows system
  * error code whose name comes from `winerror.h` so test assertions read as intent
  * (`ERROR_INVALID_PARAMETER`) instead of bare numbers. See
  * https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes for the full
  * table. Only codes mount-k.bat actually emits (or is planned to emit) are listed here -- add
  * new entries as new error paths get introduced, don't pre-populate the rest of winerror.h.
  * Keep this enum and the exit-code glossary at the bottom of `mount-k.bat` in sync. */
enum ExitCode(val code: Int):

  /** Ordinary success. */
  case SUCCESS extends ExitCode(0)

  /** The data is invalid. --script-internal invariant violation: a literal baked into the script
    * fails its own sanity check (e.g. `PERSIST_MODE` doesn't match the hardcoded allowlist). Not
    * a user error -- it means someone shipped a broken `mount-k.bat`. */
  case ERROR_INVALID_DATA extends ExitCode(13)

  /** The local device name is already in use. --mount target collides with an existing real
    * volume (e.g. `mount-c.bat` when `C:` is the system drive). */
  case ERROR_ALREADY_ASSIGNED extends ExitCode(85)

  /** The parameter is incorrect. --user-supplied CLI input was rejected: unrecognized command-
    * line flag, ambiguous script-name stem (neither mount nor unmount), no action resolvable. */
  case ERROR_INVALID_PARAMETER extends ExitCode(87)

  /** The filename, directory name, or volume label syntax is incorrect. --script filename
    * doesn't match the expected pattern (missing `-X` drive-letter suffix, digit in the
    * drive-letter position, etc.). */
  case ERROR_INVALID_NAME extends ExitCode(123)
