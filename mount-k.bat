@echo off
setlocal

rem ============================================================================
rem  mount-k.bat - Mount or unmount a virtual drive pointing to this script's
rem  directory. Built with love: portable, persistent, idempotent.
rem
rem  Usage:  mount-k.bat       Mount K: to this directory
rem          mount-k.bat /D    Unmount K: and remove the boot-time registry entry
rem          mount-k.bat /?    Print this help and exit 0
rem
rem  The drive which we'll mount is derived from the filename: mount-k.bat -> K:
rem ============================================================================
set "VERSION=0.1.0"

rem The filename must end with `-x.bat` where X is an ASCII drive letter.
rem The A..Z for-loop sanity checks against the input and Uppercases the letter.
set "DRIVE="
set "SCRIPT_NAME=%~n0"
for %%A in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    if /I "%SCRIPT_NAME:~-2%"=="-%%A" set "DRIVE=%%A" & goto :args_check
)
call :usage 1>&2
exit /B 123

:args_check
if "%~1"=="" goto :args_ok
if /I "%~1"=="/D" goto :args_ok
if "%~1"=="/?" goto :usage

call :usage 1>&2
exit /B 87

:args_ok
rem `SCRIPT_DIR` will hold the directory containing this script (e.g. `D:\Code`)
rem If this is a drive-root path (e.g. `D:\`) then it retains the trailing slash.
for %%A in ("%~dp0.") do set "SCRIPT_DIR=%%~fA"

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
for /F "tokens=2*" %%A in ('subst ^| findstr "^%DRIVE%:\\:"') do (
    set "MOUNT_STATE=exists-other"
    if /I "%%B"=="%SCRIPT_DIR%" set "MOUNT_STATE=exists-noop"
)
rem `vol K:` fails when the letter is free and succeeds when a real volume
rem (physical disk, network share, etc.) owns it.
if not defined MOUNT_STATE (
    vol %DRIVE%: >nul 2>&1 && set "MOUNT_STATE=in-use-skip"
)

rem `SCRIPT_DIR` was set before enabling delayed expansion, so if it
rem contained any `!` in its value those will survive a !SCRIPT_DIR! read.
setlocal EnableDelayedExpansion
if /I "%~1"=="/D" goto :unmount

rem ############################################################################
rem ####[  Mount (no args)  ]###################################################
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
rem ####[  Unmount (/D)  ]######################################################
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
net session >nul 2>&1 && goto :run_direct

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
echo Usage: %~nx0 [/D^|/?]
echo   ^<no args^> - mount %DRIVE%: to %~dp0
echo   /D        - unmount %DRIVE%: and remove the boot-time registry entry
echo.  /?        - show this help& rem if we run `echo /?` we get echo's usage :P
exit /B

rem -------------------------------------------------------------------------------------------------------------------------------------------------------------------------
rem    #: System Error Code . . . . Generic FORMAT_MESSAGE_FROM_SYSTEM  . . . . . . . . . . . . . . . . . Explanation of error  . . . . . . . . . . . . . . . . . . . . . . .
rem -------------------------------------------------------------------------------------------------------------------------------------------------------------------------
rem    0: ERROR_SUCCESS . . . . . . The operation completed successfully. . . . . . . . . . . . . . . . . Mount or unmount completed successfully . . . . . . . . . . . . . .
rem   31: ERROR_GEN_FAILURE . . . . A device attached to the system is not functioning. . . . . . . . . . Subst or reg add/delete failed unexpectedly . . . . . . . . . . . .
rem   85: ERROR_ALREADY_ASSIGNED  . The local device name is already in use.  . . . . . . . . . . . . . . Target letter is already held by a volume or network share  . . . .
rem   87: ERROR_INVALID_PARAMETER . The parameter is incorrect. . . . . . . . . . . . . . . . . . . . . . Unknown command-line switch . . . . . . . . . . . . . . . . . . . .
rem  123: ERROR_INVALID_NAME  . . . The filename, directory name, or volume label syntax is incorrect.  . Script filename doesn't match mount-x.bat . . . . . . . . . . . . .
rem 1223: ERROR_CANCELLED . . . . . The operation was canceled by the user. . . . . . . . . . . . . . . . Drive was already (un)mounted, but reg add/delete UAC was dismissed
