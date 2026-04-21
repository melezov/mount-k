@echo off
setlocal

rem ============================================================================
rem  mount-k.bat - Mount or unmount a virtual drive pointing to this script's
rem  directory. Built with love: opinionated, portable, persistent, idempotent.
rem
rem  Usage:  mount-k.bat       Mount K: (default when named `mount-x.bat`, same as /M)
rem          mount-k.bat /M    Explicitly mount K: to this directory and persist across reboots
rem          mount-k.bat /D    Unmount K: and remove the boot-time registry entry
rem          mount-k.bat /?    Print this help and exit 0
rem
rem  The drive which we'll mount is derived from the filename: mount-k.bat -> K:
rem ============================================================================
set "VERSION=0.2.0"

rem ############################################################################
rem ####[  Extract drive from filename  ]#######################################
rem ############################################################################
rem The filename must end with `-x.bat` where X is an ASCII drive letter.
rem The A..Z for-loop sanity checks against the input and Uppercases the letter.
set "DRIVE="
set "SCRIPT_NAME=%~n0"
for %%A in ("%~dp0.") do set "SCRIPT_DIR=%%~fA"
for %%A in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    if /I "%SCRIPT_NAME:~-2%"=="-%%A" set "DRIVE=%%A" & goto :args_check
)
call :usage 1>&2
exit /B 123

rem Supported mount filenames: `mount-k.bat`, `SOMETHING-MOUNT-K.bat`, `foo_mount-k.bat`
rem Supported unmount filenames: `unmount-k.bat`, `foo-unmount-K.bat`, `X_Unmount-k.bat`
:args_check
set "ACTION="
set "STEM=%SCRIPT_NAME:~0,-2%"
set "NORM=_%STEM:-=_%"
if /I "%NORM:~-8%"=="_unmount" set "ACTION=unmount"
if not defined ACTION if /I "%NORM:~-6%"=="_mount" set "ACTION=mount"

rem ############################################################################
rem ####[  Argument parsing  ]##################################################
rem ############################################################################
rem Accepting both flags in one invocation is deliberate: `/M /D` (either order)
rem is treated as a rem re-mount, i.e. `/M` wins. If neither flag appeared,
rem ACTION falls back to the stem-derived default.
set "ARG_MOUNT="
:arg_loop
if "%~1"=="" goto :args_done
if /I "%~1"=="/M" (set "ARG_MOUNT=1" & shift & goto :arg_loop)
if /I "%~1"=="/D" (set "ACTION=unmount" & shift & goto :arg_loop)
if "%~1"=="/?" goto :usage
call :usage 1>&2
exit /B 87
:args_done
if defined ARG_MOUNT set "ACTION=mount"

if not defined ACTION (
    >&2 echo %~nx0: for no arguments to work, the filename should be either mount-k.bat or unmount-k.bat; pass /M or /D to choose one.
    call :usage 1>&2
    exit /B 87
)

rem ############################################################################
rem ####[  Registry persistence  ]##############################################
rem ############################################################################
rem `REG_KEY` is here Windows stores boot-time drive mappings.
rem Drives mapped to this registry will survive reboots (before user's login).
rem `REG_WANT` is what we want to write to the registry and `REG_HAVE` is what
rem is currently in there already (reading from the registry doesn't need UAC).
set "REG_KEY=HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\DOS Devices"
set "REG_WANT=\??\%SCRIPT_DIR%"
set "REG_HAVE="
for /F "skip=2 tokens=2*" %%A in ('reg query "%REG_KEY%" /v "%DRIVE%:" 2^>nul') do set "REG_HAVE=%%B"

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
if /I "%ACTION%"=="unmount" goto :unmount

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
rem Write the boot-time registry entry if missing or stale.
if /I "!REG_HAVE!"=="!REG_WANT!" exit /B
set "UAC_CMD=reg add "%REG_KEY%" /v %DRIVE%: /t REG_SZ /d "!REG_WANT!" /f"
call :run_uac
exit /B

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
rem Also, clean up the boot-time registry entry if there is one.
if not defined REG_HAVE exit /B
set "UAC_CMD=reg delete "%REG_KEY%" /v %DRIVE%: /f"
call :run_uac
exit /B

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
    "try { Start-Process -Verb RunAs -Wait -WindowStyle Hidden cmd '/c !UAC_CMD!' -ErrorAction Stop } catch { exit 1223 }"
exit /B

:run_direct
!UAC_CMD! >nul
exit /B

:usage
rem ############################################################################
rem ####[  Usage  ]#############################################################
rem ############################################################################
echo %~nx0 v%VERSION% - https://github.com/melezov/mount-k
echo.
if not defined DRIVE (
    echo Error: script name must end with -x where X is a drive letter ^(e.g. mount-k.bat^)
    exit /B
)
set "NOARGS_TEXT=unavailable; use /M or /D to pick an action"
if /I "%ACTION%"=="mount" set "NOARGS_TEXT=same as /M [default]"
if /I "%ACTION%"=="unmount" set "NOARGS_TEXT=same as /D [default]"
echo Usage: %~nx0 [/M^|/D^|/?]
echo   ^<no args^> - %NOARGS_TEXT%
echo   /M        - mount %DRIVE%: to %~dp0 and persist across reboots
echo   /D        - unmount %DRIVE%: and remove the boot-time registry entry
echo.  /?        - show this help& rem if we run `echo /?` we get echo's usage :P
exit /B

rem -------------------------------------------------------------------------------------------------------------------------------------------------------------------------
rem    #: System Error Code . . . . Generic FORMAT_MESSAGE_FROM_SYSTEM  . . . . . . . . . . . . . . . . . Explanation of error  . . . . . . . . . . . . . . . . . . . . . . .
rem -------------------------------------------------------------------------------------------------------------------------------------------------------------------------
rem    0: ERROR_SUCCESS . . . . . . The operation completed successfully. . . . . . . . . . . . . . . . . Mount or unmount completed successfully . . . . . . . . . . . . . .
rem   31: ERROR_GEN_FAILURE . . . . A device attached to the system is not functioning. . . . . . . . . . Subst or reg add/delete failed unexpectedly . . . . . . . . . . . .
rem   85: ERROR_ALREADY_ASSIGNED  . The local device name is already in use.  . . . . . . . . . . . . . . Target letter is already held by a volume or network share  . . . .
rem   87: ERROR_INVALID_PARAMETER . The parameter is incorrect. . . . . . . . . . . . . . . . . . . . . . Bad switch or ambiguous script name . . . . . . . . . . . . . . . .
rem  123: ERROR_INVALID_NAME  . . . The filename, directory name, or volume label syntax is incorrect.  . Script filename doesn't match mount-x.bat . . . . . . . . . . . . .
rem 1223: ERROR_CANCELLED . . . . . The operation was canceled by the user. . . . . . . . . . . . . . . . Drive was already (un)mounted, but reg add/delete UAC was dismissed
