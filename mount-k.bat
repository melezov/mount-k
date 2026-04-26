@echo off
setlocal

set "PROJECT_URL=https://github.com/melezov/mount-k"
set "VERSION=0.3.0"

rem ============================================================================
rem  mount-k.bat - Mount or unmount a virtual drive pointing to this script's
rem  directory. Built with love: opinionated, portable, persistent, idempotent.
rem
rem  Usage:  mount-k.bat       Mount K: to where the script is placed
rem        unmount-k.bat       Unmount K: (requires no arguments)
rem          mount-k.bat /?    Print usage (it changes with the filename)
rem
rem  The drive to (un)mount is derived from the filename: mount-x.bat -> X:
rem ============================================================================

set   "MOUNT_PREFIX=mount-"
set "UNMOUNT_PREFIX=unmount-"

set   "MOUNT_SUFFIX=-and-remember"
set "UNMOUNT_SUFFIX=-and-forget"

rem ############################################################################
rem ####[  Registry persistence mode  ]#########################################
rem ############################################################################
rem
rem set "PERSIST_MODE=always"         & rem always persist into registry
    set "PERSIST_MODE=if-suffixed"    & rem persist only on filename suffix match
rem set "PERSIST_MODE=ask"            & rem ask the user via prompt
rem set "PERSIST_MODE=never"          & rem don't touch the registry in any way

rem ============================================================================
rem  If the `if-suffix` mode is active, the filename dictates whether or not
rem  we'll attempt to elevate the script and persist the mount into registry.
rem
rem    mount-k-and-remember.bat    Mount and persist the mount in DOS Devices registry
rem  unmount-k-and-forget.bat      Unmount and remove the boot-time mount
rem
rem  Setting both suffixes to <empty> makes `if-suffixed` mode work the same as `always`
rem ============================================================================

set "PERSIST_MODES=always if-suffixed ask never"
set "PM_LIST=`%PERSIST_MODES: =`, `%`"
call set "PM_LIST=%%PM_LIST:`%PERSIST_MODE%`=`%PERSIST_MODE%` [default]%%"

rem Store the original file name and extension
set "SCRIPT_NAME=%~n0"
set "SCRIPT_EXT=%~x0"

rem This is the path we will be working on. Its trailing backslash will be
rem stripped (e.g. `D:\code`) unless it's a drive-root path (e.g. `D:\`).
for %%A in ("%~dp0.") do set "SCRIPT_DIR=%%~fA"

rem ############################################################################
rem ####[  Script filename parsing  ]###########################################
rem ############################################################################
rem
rem We'll run through A..Z and see if we match any of the allowed patterns.
rem This is also the best way to uppercase the drive letter in pure batch.
set "ACTION="
for %%A in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    set "DRIVE=%%A"
    if /I "%SCRIPT_NAME%"=="%MOUNT_PREFIX%%%A"                   set "ACTION=M"   & goto :filename_OK
    if /I "%SCRIPT_NAME%"=="%MOUNT_PREFIX%%%A%MOUNT_SUFFIX%"     set "ACTION=M+R" & goto :filename_OK
    if /I "%SCRIPT_NAME%"=="%UNMOUNT_PREFIX%%%A"                 set "ACTION=D"   & goto :filename_OK
    if /I "%SCRIPT_NAME%"=="%UNMOUNT_PREFIX%%%A%UNMOUNT_SUFFIX%" set "ACTION=D+F" & goto :filename_OK
)
call :usage 1>&2
exit /B 123
:filename_OK

set "FILE_ACTION=%ACTION%"

rem ############################################################################
rem ####[  Argument parsing  ]##################################################
rem ############################################################################
rem
rem Arguments allow to override the defaults we divined in the above sections.
rem   `/M`            mount (/M + /D = remount, mount always wins)
rem   `/D`            unmount
rem   `/PM` <mode>    override PERSIST_MODE; the next arg is the mode
rem.  `/?`            show help
set "PERSIST_MODE_DEFAULT=%PERSIST_MODE%"
set "PERSIST_MODE_VIA_ARGS="
set "GOT_EM="
:arg_loop
if "%~1"=="" goto :args_done
if /I "%~1"=="/M"  (set "GOT_EM=1" & shift /1 & goto :arg_loop)
if /I "%~1"=="/D"  (set "ACTION=D" & shift /1 & goto :arg_loop)
if /I "%~1"=="/PM" (
    if not "%~2"=="" (
        set "PERSIST_MODE=%~2"
        set "PERSIST_MODE_VIA_ARGS=1"
        shift /1 & shift /1 & goto :arg_loop
    )
    >&2 echo /PM requires a value, allowed values are: %PM_LIST%
    exit /B 87
)
if "%~1"=="/?" goto :usage
>&2 echo Unknown argument: %~1
call :usage 1>&2
exit /B 87
:args_done
if defined GOT_EM set "ACTION=M"

for %%A in (%PERSIST_MODES%) do if /I "%PERSIST_MODE%"=="%%A" (
    set "PERSIST_MODE=%%A" & rem normalize casing
    goto :persist_mode_OK
)
if defined PERSIST_MODE_VIA_ARGS (
    >&2 echo Unsupported /PM `%PERSIST_MODE%` - must be one of: %PM_LIST%
    exit /B 87
)
>&2 echo Unsupported PERSIST_MODE: %PERSIST_MODE% (should not happen - error in the script?)
exit /B 13
:persist_mode_OK

rem ############################################################################
rem ####[  ACTION normalization (PERSIST_MODE -> ACTION)  ]#####################
rem ############################################################################
rem After this block ACTION is the runtime source of truth:
rem  always       -> upgrade plain ACTION to its +R/+F variant
rem  never        -> downgrade +R/+F back to plain
rem  if-suffixed  -> upgrade only if FILE_ACTION carried the matching suffix
rem  ask          -> leave ACTION as-is; runtime prompt decides per call
if /I "%PERSIST_MODE%"=="always" (
    if "%ACTION%"=="M" set "ACTION=M+R"
    if "%ACTION%"=="D" set "ACTION=D+F"
)
if /I "%PERSIST_MODE%"=="never" (
    if "%ACTION%"=="M+R" set "ACTION=M"
    if "%ACTION%"=="D+F" set "ACTION=D"
)
if /I "%PERSIST_MODE%"=="if-suffixed" (
    if "%ACTION%"=="M" if "%FILE_ACTION%"=="M+R" set "ACTION=M+R"
    if "%ACTION%"=="D" if "%FILE_ACTION%"=="D+F" set "ACTION=D+F"
)

rem ############################################################################
rem ####[  Registry persistence  ]##############################################
rem ############################################################################
rem `REG_KEY` is where Windows stores boot-time drive mappings.
rem Drives mapped to this registry will survive reboots (before user's login).
rem `REG_WANT` is what we want to write to the registry and `REG_HAVE` is what
rem is currently in there already (reading from the registry doesn't need UAC).
set "REG_KEY=HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\DOS Devices"
set "REG_WANT=\??\%SCRIPT_DIR%"
set "REG_HAVE="

rem Read REG_HAVE only when we might use it - either `ACTION` that ends with
rem +R/+F or when `PERSIST_MODE` is `ask` - no need to ask if it's a noop
if not "%ACTION:~-2,1%"=="+" if /I not "%PERSIST_MODE%"=="ask" goto :reg_have_done
for /F "skip=2 tokens=2*" %%A in ('reg query "%REG_KEY%" /v "%DRIVE%:" 2^>nul') do set "REG_HAVE=%%B"
:reg_have_done

rem Figure out the current `MOUNT_STATE` for the `%DRIVE%` letter:
rem   <undefined>    - drive letter is unused
rem   `exists-noop`  - already substed to `SCRIPT_DIR`
rem   `exists-other` - substed to a different directory
rem   `in-use-skip`  - held by a real volume or network share; refuse to touch it
set "MOUNT_STATE="
rem `subst` prints lines like `K:\: => C:\some\path`, so we match on the weird
rem `<DRIVE>:\:` syntax (can't exist in a normal path) and extract what we need:
for /F "tokens=1,2*" %%A in ('subst') do (
    if /I "%%A"=="%DRIVE%:\:" (
        set "MOUNT_STATE=exists-other"
        if /I "%%C"=="%SCRIPT_DIR%" set "MOUNT_STATE=exists-noop"
    )
)
rem `vol K:` fails when the letter is free and succeeds when a real volume
rem (physical disk, network share, etc.) owns it.
if not defined MOUNT_STATE (
    vol %DRIVE%: >nul 2>&1 && set "MOUNT_STATE=in-use-skip"
)

rem `SCRIPT_DIR` was set before enabling delayed expansion, so if it
rem contained any `!` in its value those will survive a !SCRIPT_DIR! read.
setlocal EnableDelayedExpansion
if /I "%ACTION:~0,1%"=="D" goto :unmount

rem ############################################################################
rem ####[  Mount  ]#############################################################
rem ############################################################################
if "%MOUNT_STATE%"=="exists-noop" (
    echo %DRIVE%: is already mounted to !SCRIPT_DIR!
    goto :mount_reg
)
if "%MOUNT_STATE%"=="in-use-skip" (
    >&2 echo %DRIVE%: is already in use by another drive or network share.
    exit /B 85
)
if "%MOUNT_STATE%"=="exists-other" (
    subst %DRIVE%: /D 1>&2 || (
        >&2 echo Failed to unmount existing %DRIVE%: drive ^(errorlevel !ERRORLEVEL!^).
        exit /B 31
    )
)
subst %DRIVE%: "!SCRIPT_DIR!" 1>&2 || (
    >&2 echo Failed to mount %DRIVE%: drive ^(errorlevel !ERRORLEVEL!^).
    exit /B 31
)
echo %DRIVE%: drive mapped to !SCRIPT_DIR!

:mount_reg
rem Touch the registry only when ACTION says so (M+R) or when PM=ask (runtime prompt).
if not "%ACTION%"=="M+R" if /I not "%PERSIST_MODE%"=="ask" exit /B
if /I "!REG_HAVE!"=="!REG_WANT!" (
    echo %DRIVE%: is already persisted in registry
    exit /B
)
if /I "%PERSIST_MODE%"=="ask" (
    call :ask_persist
    if !ERRORLEVEL! neq 0 (
        echo %DRIVE%: persistence skipped
        exit /B
    )
)
set "UAC_CMD=reg add "%REG_KEY%" /v %DRIVE%: /t REG_SZ /d "!REG_WANT!" /f"
call :run_uac
set "REG_EC=!ERRORLEVEL!"
if "!REG_EC!"=="0" (
    echo %DRIVE%: persisted to !REG_WANT! in registry
    exit /B
)
if "!REG_EC!"=="1223" (
    >&2 echo Persistence to registry cancelled by user.
    exit /B 1223
)
>&2 echo Failed to persist %DRIVE%: to registry ^(errorlevel !REG_EC!^).
exit /B 31

:unmount
rem ############################################################################
rem ####[  Unmount  ]###########################################################
rem ############################################################################
if /I not "%MOUNT_STATE:~0,7%"=="exists-" (
    echo %DRIVE%: is not mounted via subst
    goto :unmount_reg
)
subst %DRIVE%: /D 1>&2 || (
    >&2 echo Failed to unmount %DRIVE%: drive ^(errorlevel !ERRORLEVEL!^).
    exit /B 31
)
echo %DRIVE%: drive unmounted

:unmount_reg
rem Touch the registry only when ACTION says so (D+F) or when PM=ask (runtime prompt).
if not "%ACTION%"=="D+F" if /I not "%PERSIST_MODE%"=="ask" exit /B
if not defined REG_HAVE (
    echo %DRIVE%: was not persisted in registry
    exit /B
)
if /I "%PERSIST_MODE%"=="ask" (
    call :ask_persist
    if !ERRORLEVEL! neq 0 (
        echo %DRIVE%: registry cleanup skipped
        exit /B
    )
)
set "UAC_CMD=reg delete "%REG_KEY%" /v %DRIVE%: /f"
call :run_uac
set "REG_EC=!ERRORLEVEL!"
if "!REG_EC!"=="0" (
    echo %DRIVE%: removed from registry
    exit /B
)
if "!REG_EC!"=="1223" (
    >&2 echo Registry cleanup cancelled by user.
    exit /B 1223
)
>&2 echo Failed to remove %DRIVE%: from registry ^(errorlevel !REG_EC!^).
exit /B 31

:run_uac
rem ############################################################################
rem ####[  Command elevator  ]##################################################
rem ############################################################################
rem Runs `UAC_CMD` through Powershell to trigger UAC-elevation. If `SKIP_ELEVATION`
rem is set (or if we're already an Administrator) we run the command directly.
if "%SKIP_ELEVATION%"=="1" goto :run_direct
rem https://learn.microsoft.com/en-us/windows/win32/secauthz/well-known-sids
reg query "HKU\S-1-5-19" >nul 2>&1 && goto :run_direct

set "UAC_CMD=!UAC_CMD:\"=\\"!" & rem prevent `"\??\X:\"` from breaking the quotes
set "UAC_CMD=!UAC_CMD:"=\"!"   & rem for PowerShell's -Command parser
set "UAC_CMD=!UAC_CMD:'=''!"   & rem for the single-quoted argument to `cmd /c`
rem Since `Start-Process` returns success when the UAC prompt is cancelled we use
rem `-ErrorAction Stop` inside a try/catch to turn dismissal into status 1223.
powershell -NoProfile -Command ^
    "try { exit (Start-Process "^
        "-PassThru -Verb RunAs -WindowStyle Hidden -Wait -ErrorAction Stop "^
        "cmd.exe -ArgumentList '/c', '!UAC_CMD!' "^
    ").ExitCode } catch { exit 1223 }"
exit /B

:run_direct
!UAC_CMD! >nul
exit /B

:ask_persist
rem ############################################################################
rem ####[  Persist prompt  ]#####################################################
rem ############################################################################
rem Called from :mount_reg and :unmount_reg when PERSIST_MODE=ask. Returns
rem exit /B 0 to proceed with the registry operation or exit /B 1 to skip it.
rem Default answer derives from the filename: FILE_ACTION ending in +R/+F defaults to Y
rem (shown as [Y/n], non-blocking); plain filename defaults to N ([y/N]).
if /I "!ACTION:~0,1!"=="M" (
    set "_PERSIST_PROMPT=Persist !DRIVE!: to registry?"
) else (
    set "_PERSIST_PROMPT=Remove !DRIVE!: from registry?"
)
if "!FILE_ACTION:~-2,1!"=="+" (
    <nul set /P "_=!_PERSIST_PROMPT! [Y/n]: "
    echo.
    exit /B 0
)
set "_ANS="
set /P "_ANS=!_PERSIST_PROMPT! [y/N]: "
if "!_ANS!"=="" exit /B 1
if /I "!_ANS!"=="Y" exit /B 0
exit /B 1

:usage
rem ############################################################################
rem ####[  Usage  ]#############################################################
rem ############################################################################
echo mount-k v%VERSION% - %PROJECT_URL%
echo.
if not defined FILE_ACTION (
    echo Unexpected filename: `%SCRIPT_NAME%%SCRIPT_EXT%`
    echo Expected `%MOUNT_PREFIX%^<drive^>[%MOUNT_SUFFIX%]%SCRIPT_EXT%`
    echo       or `%UNMOUNT_PREFIX%^<drive^>[%UNMOUNT_SUFFIX%]%SCRIPT_EXT%`
    echo.
    echo Cannot show usage as it's filename dependent - rename the script to
    echo `%MOUNT_PREFIX%^<unused-drive-letter^>%SCRIPT_EXT%` and run with `/?` to see usage.
    exit /B
)

set "_M_NORM=M"
set "_D_NORM=D"
if /I "%PERSIST_MODE_DEFAULT%"=="always"      set "_M_NORM=M+R" & set "_D_NORM=D+F"
if /I "%PERSIST_MODE_DEFAULT%"=="if-suffixed" if "%FILE_ACTION%"=="M+R" set "_M_NORM=M+R"
if /I "%PERSIST_MODE_DEFAULT%"=="if-suffixed" if "%FILE_ACTION%"=="D+F" set "_D_NORM=D+F"
rem `never` always leaves _M_NORM=M / _D_NORM=D as initialized; `ask` leaves them too
rem (runtime decides per call).

set   "MOUNT_EFFECT="
set "UNMOUNT_EFFECT="
if "%_M_NORM%"=="M+R"                   set   "MOUNT_EFFECT= and persist across reboots"
if "%_D_NORM%"=="D+F"                   set "UNMOUNT_EFFECT= and remove the boot-time registry entry"
if /I "%PERSIST_MODE_DEFAULT%"=="ask"   set   "MOUNT_EFFECT= - ask before persisting into registry"
if /I "%PERSIST_MODE_DEFAULT%"=="ask"   set "UNMOUNT_EFFECT= - ask before removing from registry"
if /I "%PERSIST_MODE_DEFAULT%"=="never" set   "MOUNT_EFFECT=, do not touch registry"
if /I "%PERSIST_MODE_DEFAULT%"=="never" set "UNMOUNT_EFFECT=, do not touch registry"
set "NOARGS_REF=/M (mount)"
if "%FILE_ACTION:~0,1%"=="D" set "NOARGS_REF=/D (unmount)"

echo Usage: %SCRIPT_NAME%%SCRIPT_EXT% [/M^|/D^|/PM ^<mode^>^|/?]
echo   ^<no args^>    - same as %NOARGS_REF% since the filename defines the default action
echo   /M           - mount `%DRIVE%:` to `%SCRIPT_DIR%`%MOUNT_EFFECT%
echo   /D           - unmount `%DRIVE%:`%UNMOUNT_EFFECT%
echo   /PM ^<mode^>   - override PERSIST_MODE - one of: %PM_LIST%
echo.  /?           - show this help& rem `echo /?` would print echo's own usage :P
exit /B

rem -------------------------------------------------------------------------------------------------------------------------------------------------------------------------
rem    #: System Error Code . . . . Generic FORMAT_MESSAGE_FROM_SYSTEM  . . . . . . . . . . . . . . . . . Explanation of error  . . . . . . . . . . . . . . . . . . . . . . .
rem -------------------------------------------------------------------------------------------------------------------------------------------------------------------------
rem    0: ERROR_SUCCESS . . . . . . The operation completed successfully. . . . . . . . . . . . . . . . . Mount or unmount completed successfully . . . . . . . . . . . . . .
rem   13: ERROR_INVALID_DATA  . . . The data is invalid.  . . . . . . . . . . . . . . . . . . . . . . . . Hardcoded PERSIST_MODE outside allowlist (script bug) . . . . . . .
rem   31: ERROR_GEN_FAILURE . . . . A device attached to the system is not functioning. . . . . . . . . . Subst or reg add/delete failed unexpectedly . . . . . . . . . . . .
rem   85: ERROR_ALREADY_ASSIGNED  . The local device name is already in use.  . . . . . . . . . . . . . . Target letter is already held by a volume or network share  . . . .
rem   87: ERROR_INVALID_PARAMETER . The parameter is incorrect. . . . . . . . . . . . . . . . . . . . . . Bad switch or ambiguous script name . . . . . . . . . . . . . . . .
rem  123: ERROR_INVALID_NAME  . . . The filename, directory name, or volume label syntax is incorrect.  . Script filename doesn't match mount-x.bat . . . . . . . . . . . . .
rem 1223: ERROR_CANCELLED . . . . . The operation was canceled by the user. . . . . . . . . . . . . . . . Drive was already (un)mounted, but reg add/delete UAC was dismissed
