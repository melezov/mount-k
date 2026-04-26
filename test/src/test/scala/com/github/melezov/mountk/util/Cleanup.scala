package com.github.melezov.mountk
package util

import com.sun.jna.platform.win32.WinReg
import scribe.*

import java.nio.file.{Files, Path}
import scala.sys.process.*

/** Recovery tool for cancelled / killed test runs and stray mount-k mounts. Discovers work in
  * three buckets and folds whatever needs elevation into a single UAC-prompted batch script:
  *
  *    - Live `subst` mappings: try to remove each in the current (non-elevated) LUID via JNA.
  *      Successful removals are reported inline. Failures (`DefineDosDevice` returns false) mean
  *      the subst belongs to another LUID -- typically an elevated shell -- so its letter goes to
  *      the elevated pass, which retries there.
  *    - `HKCU\<TestRegSubPath>` test subtree: always queued for deletion; HKCU writes don't need
  *      elevation but it's free to bundle with the same bat.
  *    - `HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\DOS Devices`: scanned for
  *      persistent-subst entries (`<LETTER>:` -> `\??\<path>`) mount-k.bat wrote. Always needs
  *      elevation to delete.
  *
  * If the bat has any elevation-requiring line (a failed subst OR an HKLM delete) we run it
  * through PowerShell `Start-Process -Verb RunAs`; otherwise it runs in-process and never prompts. */
object Cleanup:
  private val ProdRegRoot = s"HKLM\\$HklmDosDevicesSubPath"

  // Cleanup tolerates lowercase letters because HKLM entries written by external tools may not be
  // normalized; the `DriveKey` constant in package.scala is strict-uppercase for the test paths.
  private val DriveLetterName = """^([A-Za-z]):$""".r
  private val SubstTargetPrefix = "\\??\\"

  def main(args: Array[String]): Unit =
    info("mount-k cleanup")

    val liveSubsts = ('A' to 'Z').filter(WinApi.isSubst).toList
    val substDeferred = liveSubsts.filter { c =>
      if WinApi.substDelete(c) then
        info(s"unmounted $c:")
        false
      else
        info(s"$c: subst belongs to another LUID, deferring to elevated pass")
        true
    }
    if liveSubsts.isEmpty then info("no subst mappings to clear")

    val prodLetters = WinApi.regQueryValues(WinReg.HKEY_LOCAL_MACHINE, HklmDosDevicesSubPath).collect {
      case (DriveLetterName(letter), target) if target.startsWith(SubstTargetPrefix) =>
        letter.toUpperCase.head
    }.toList.sorted

    val bat = writeCleanupBat(substDeferred, prodLetters)
    try
      val needsElevation = substDeferred.nonEmpty || prodLetters.nonEmpty
      if needsElevation then runElevated(bat, substDeferred, prodLetters) else runDirect(bat)
    finally
      Files.deleteIfExists(bat)
      ()

  private def writeCleanupBat(substLetters: List[Char], prodLetters: List[Char]): Path =
    // Each line reports its own outcome on success / failure so the elevated window (which we
    // don't pipe back) isn't silent; if the user opens the .bat mid-run they'll see what happened.
    val substLines = substLetters.map(c =>
      s"""subst $c: /D >nul 2>&1 && echo   unmounted $c: || echo   FAILED to unmount $c:""")
    val hkcuLine =
      s"""reg delete "$TestRegRoot" /f >nul 2>&1 && echo   removed $TestRegRoot || echo   $TestRegRoot not present"""
    val prodLines = prodLetters.map(c =>
      s"""reg delete "$ProdRegRoot" /v $c: /f >nul 2>&1 && echo   removed $ProdRegRoot\\$c: || echo   FAILED to remove $ProdRegRoot\\$c:""")
    val body = "@echo off" +: (substLines ++ (hkcuLine +: prodLines))
    val bat = Files.createTempFile("mount-k-cleanup-", ".bat")
    Files.writeString(bat, body.mkString("\r\n"))
    bat

  private def runDirect(bat: Path): Unit =
    val rc = Process(Seq("cmd", "/c", bat.toString)).!
    if rc != 0 then warn(s"cleanup script returned exit $rc")

  /** Single PowerShell `Start-Process -Verb RunAs` on the generated .bat: one UAC prompt, one
    * cleanup, no JVM re-launch. `-PassThru` lets us read the child's exit code; the try/catch
    * turns a cancelled UAC prompt into exit 1223 (same convention mount-k.bat uses). */
  private def runElevated(bat: Path, substLetters: List[Char], prodLetters: List[Char]): Unit =
    val reasons = Seq(
      Option.when(substLetters.nonEmpty)(s"subst: ${substLetters.map(c => s"$c:").mkString(", ")}"),
      Option.when(prodLetters.nonEmpty)(s"HKLM: ${prodLetters.map(c => s"$c:").mkString(", ")}"),
    ).flatten.mkString("; ")
    info(s"elevation needed ($reasons); requesting UAC...")

    def psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

    val ps =
      "try { $p = Start-Process -Verb RunAs -Wait -PassThru -WindowStyle Hidden " +
        s"-FilePath ${psQuote(bat.toString)} -ErrorAction Stop; exit $$p.ExitCode } " +
        "catch { exit 1223 }"
    Process(Seq("powershell", "-NoProfile", "-Command", ps)).! match
      case 0 => info("elevated cleanup completed")
      case 1223 => warn("UAC prompt cancelled; entries left in place")
      case n => error(s"elevated cleanup failed (exit $n)")
