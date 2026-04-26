package com.github.melezov.mountk
package test

import org.specs2.specification.core.SpecStructure

import scala.language.implicitConversions

// Pins the script's ANSI color behavior. ScriptSpec defaults `NO_COLOR=1` for the rest of the suite
// so plain-text substring assertions stay valid; this spec opts back into colors and verifies each
// colored region in the `:usage` block by role -- a regression that swapped a color, dropped a
// reset, or broke the OSC 8 hyperlink would surface here distinctly.
class ColorSpec extends ScriptSpec:

  val parallelism = 1

  // Drop the default NO_COLOR=1 so the script's terminal/Windows-version detection runs.
  override protected lazy val extraEnv: Seq[(String, String)] = Seq.empty

  // WT_SESSION trips both `:color_OK` AND `:hyperlink_OK`, unlocking colors + OSC 8.
  private val withColor: Seq[(String, String)] = Seq("WT_SESSION" -> "color-spec")
  // ANSICON enables colors but is NOT a hyperlink-capable terminal, so OSC 8 stays off.
  private val withColorOnly: Seq[(String, String)] = Seq("ANSICON" -> "color-spec")

  private val Esc: String = 0x1b.toChar.toString
  private def code(seq: String): String = s"$Esc[${seq}m"

  // SGR codes match what `:setup_colors` actually emits, byte-for-byte. The label names describe
  // the perceived hue only -- the inline `1;` (when present) is implementation detail and isn't
  // reflected in the constant name.
  private val Reset          = code("0")
  private val White          = code("1;97")    // c_PROJECT_NAME
  private val Green          = code("1;92")    // c_DRIVE, c_PROJECT_VERSION
  private val Teal           = code("1;96")    // c_PATH
  private val Yellow         = code("1;93")    // c_SW
  private val Violet         = code("1;95")    // c_ERROR -- error label
  private val Red            = code("1;91")    // c_BAD   -- bad-value highlight
  private val UnderlineWhite = code("1;4;97")  // c_PROJECT_URL visible text inside OSC 8 envelope

  // OSC 8 hyperlink wrappers as emitted by `:hyperlink_OK`. ST = `ESC \`.
  private val Url = "https://github.com/melezov/mount-k"
  private val OscOpen = s"$Esc]8;;$Url$Esc\\"
  private val OscClose = s"$Esc]8;;$Esc\\"

  override def is: SpecStructure = args.execute(threadsNb = 1) ^ s2"""
  /? usage paints each role
    project name `mount-k` is white                       $projectNameWhite
    version `vX.Y.Z` is green                             $versionGreen
    project URL is underlined white OSC 8 link            $urlUnderlinedWhiteHyperlink
    script filename in Usage line is teal                 $scriptNameTeal
    flags (/M /D /PM /?) are yellow                       $flagsYellow
    drive letter in /M row is green                       $driveGreenInUsage
    script directory in /M row is teal                    $pathTealInUsage

  mount echo color spans
    `K:` drive letter is green                            $mountEchoDriveGreen
    SCRIPT_DIR after `drive mapped to ` is teal           $mountEchoPathTeal

  unmount echo color spans
    `K:` in `drive unmounted` echo is green               $unmountEchoDriveGreen
    `K:` in cold-unmount echo is green                    $coldUnmountEchoDriveGreen

  Error paths
    `Unsupported /PM` label is violet                     $errorLabelViolet
    bogus mode value is red                               $badValueRed
    invalid filename: example templates are teal          $invalidFilenameTemplatesTeal

  Color-detection signals (each, alone)
    WT_SESSION enables colors                             $signalWtSession
    ANSICON enables colors                                $signalAnsicon
    TERM_PROGRAM enables colors                           $signalTermProgram
    ConEmuANSI=ON enables colors                          $signalConEmuAnsi

  OSC 8 hyperlink gating
    ANSICON: colors on but URL has NO OSC 8 envelope      $colorsOnButHyperlinkOff

  Color suppression
    NO_COLOR=1 emits no ESC bytes anywhere                $noColorSuppressesEsc
"""

  /** Run /? with colors+hyperlinks enabled. All color examples share this path -- one script
    * invocation per example, with the per-role assertion inside the lambda. */
  private def usageWithColor[A](f: (RunResult.Output, Char) => A): A = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("color")
    val result = lease.runScript(script, callEnv = withColor)("/?")
    require(result.exitCode == 0, s"/? exited ${result.exitCode}; stderr=${result.stderr.value}")
    f(result.stdout, drive)
  }

  // -- /? usage paint roles --------------------------------------------------

  private def projectNameWhite = usageWithColor { (out, _) =>
    out has s"${White}mount-k$Reset"
  }

  private def versionGreen = usageWithColor { (out, _) =>
    // BuildInfo.version already includes the `v` prefix (e.g. `v0.3.0`), matching what the script
    // prints. Don't add a literal `v` here.
    out has s"$Green$version$Reset"
  }

  private def urlUnderlinedWhiteHyperlink = usageWithColor { (out, _) =>
    out has s"$OscOpen$UnderlineWhite$Url$Reset$OscClose"
  }

  private def scriptNameTeal = usageWithColor { (out, drive) =>
    out has s"${Teal}mount-${drive.toLower}.bat$Reset"
  }

  private def flagsYellow = usageWithColor { (out, _) =>
    out has s"$Yellow/M$Reset" and
      (out has s"$Yellow/D$Reset") and
      (out has s"$Yellow/PM$Reset") and
      (out has s"$Yellow/?$Reset")
  }

  private def driveGreenInUsage = usageWithColor { (out, drive) =>
    out has s"$Green$drive:$Reset"
  }

  private def pathTealInUsage = usageWithColor { (out, _) =>
    // The /M row wraps SCRIPT_DIR in teal: `<Teal>...\color<Reset>`.
    out has s"""\\Q$Teal\\E[^\\Q$Esc\\E]*\\\\color\\Q$Reset\\E""".r
  }

  // -- mount echo color spans ------------------------------------------------

  private def mountEchoDriveGreen = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("mount-color")
    val result = lease.runScript(script, callEnv = withColor)()
    result was SUCCESS and
      (result.stdout has s"$Green$drive:$Reset") and
      (result.stdout has "drive mapped to")
  }

  private def mountEchoPathTeal = withDrive { (lease, _) =>
    val script = lease.copyScriptTo("mount-color")
    val result = lease.runScript(script, callEnv = withColor)()
    result was SUCCESS and
      // The mount echo wraps the leased scratch dir in teal; assert a teal-wrapped segment whose
      // tail is the `\mount-color` sub-dir name.
      (result.stdout has s"""\\Q$Teal\\E[^\\Q$Esc\\E]*\\\\mount-color\\Q$Reset\\E""".r)
  }

  // -- unmount echo color spans ----------------------------------------------

  private def unmountEchoDriveGreen = withDrive { (lease, drive) =>
    // Mount first, then unmount: the unmount echo says ``<drive>: drive unmounted``.
    val script = lease.copyScriptTo("unmount-color")
    lease.runScript(script, callEnv = withColor)(): Unit
    val result = lease.runScript(script, callEnv = withColor)("/D")
    result was SUCCESS and
      (result.stdout has s"$Green$drive:$Reset") and
      (result.stdout has "drive unmounted")
  }

  private def coldUnmountEchoDriveGreen = withDrive { (lease, drive) =>
    // Cold unmount on a drive that was never mounted: the script echoes
    // ``<drive>: is not mounted via subst``. The drive must still be green-painted.
    val script = lease.copyScriptTo("cold-unmount-color")
    val result = lease.runScript(script, callEnv = withColor)("/D")
    result was SUCCESS and
      (result.stdout has s"$Green$drive:$Reset") and
      (result.stdout has "is not mounted via subst")
  }

  // -- Error paths -----------------------------------------------------------

  /** Helper: run `/PM bogus` under colors. The validator echoes the rejection on stderr
    * with `c_ERR`-wrapped label and `c_BAD`-wrapped bad value. */
  private def withBogusPm[A](f: RunResult.Output => A): A = withDrive { (lease, _) =>
    val script = lease.copyScriptTo("bogus-pm")
    val result = lease.runScript(script, callEnv = withColor)("/PM", "bogus")
    require(result.exitCode == ERROR_INVALID_PARAMETER.code,
      s"/PM bogus exited ${result.exitCode}; stderr=${result.stderr.value}")
    f(result.stderr)
  }

  private def errorLabelViolet = withBogusPm { err =>
    err has s"${Violet}Unsupported /PM$Reset"
  }

  private def badValueRed = withBogusPm { err =>
    err has s"${Red}bogus$Reset"
  }

  private def invalidFilenameTemplatesTeal = withDrive { (lease, _) =>
    // A name that doesn't match `mount-X.bat` / `unmount-X.bat[-and-...]`. The script falls
    // through the A..Z scan, calls :usage with FILE_ACTION undefined, and prints the two
    // "Expected" template paths.
    val script = lease.copyScriptTo("invalid-name", "weird-x.bat")
    val result = lease.runScript(script, callEnv = withColor)()
    result was ERROR_INVALID_NAME and
      (result.stderr has s"${Teal}mount-<drive>[-and-remember].bat$Reset") and
      (result.stderr has s"${Teal}unmount-<drive>[-and-forget].bat$Reset")
  }

  // -- Color-detection signals (each, alone) ---------------------------------

  /** Helper: run /? with one and only one color-trigger env var set, assert ESC sequences leak
    * into stdout. Validates that each detection branch in `:setup_colors` is wired -- a regression
    * that drops one signal would surface here even if WT_SESSION still works. */
  private def signalEnablesColor(signal: String, value: String) = withDrive { (lease, _) =>
    val script = lease.copyScriptTo(s"signal-${signal.toLowerCase}")
    val result = lease.runScript(script, callEnv = Seq(signal -> value))("/?")
    result was SUCCESS and (result.stdout has Esc)
  }

  private def signalWtSession = signalEnablesColor("WT_SESSION", "color-spec")
  private def signalAnsicon = signalEnablesColor("ANSICON", "color-spec")
  private def signalTermProgram = signalEnablesColor("TERM_PROGRAM", "color-spec")
  private def signalConEmuAnsi = signalEnablesColor("ConEmuANSI", "ON")

  // -- OSC 8 hyperlink gating ------------------------------------------------

  private def colorsOnButHyperlinkOff = withDrive { (lease, _) =>
    // ANSICON trips `_COLOR_OK` but is NOT in the hyperlink-capable list (WT_SESSION,
    // ConEmuPID, TERM_PROGRAM). So URL gets underline + white SGRs but no OSC 8 wrapper.
    val script = lease.copyScriptTo("color-no-link")
    val result = lease.runScript(script, callEnv = withColorOnly)("/?")
    result was SUCCESS and
      (result.stdout has s"$UnderlineWhite$Url$Reset") and
      (result.stdout hasNot s"$Esc]8;;")
  }

  // -- Color suppression -----------------------------------------------------

  private def noColorSuppressesEsc = withDrive { (lease, _) =>
    val script = lease.copyScriptTo("color-off")
    val result = lease.runScript(script, callEnv = Seq("NO_COLOR" -> "1"))("/?")
    result was SUCCESS and
      (result.stdout hasNot Esc) and
      result.stderr.isEmpty
  }
