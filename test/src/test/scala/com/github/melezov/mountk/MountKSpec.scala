package com.github.melezov.mountk

import com.github.melezov.mountk.WinApi.{queryDosDevices, substDelete}
import org.specs2.specification.core.SpecStructure

import scala.language.implicitConversions

class MountKAdminSpec extends MountKSpec(elevated = true)
class MountKUserSpec extends MountKSpec(elevated = false)

abstract class MountKSpec(elevated: Boolean) extends ScriptSpec:

  val parallelism = 6

  private val extraEnv = if elevated then Seq("SKIP_ELEVATION" -> "1") else Seq.empty

  override def is: SpecStructure = args.execute(threadsNb = drives.length) ^ s2"""
  mount-k basic mount/unmount
    mount to script directory                          $mountToDirectory
    idempotent/noop when already mounted               $idempotentNoop
    mount with explicit /M                             $mountExplicitFlag
    unmount with /d                                    $unmountLower
    unmount with /D                                    $unmountUpper
    handle unmount when not mounted                    $unmountNotMounted
    /M and /D together mean /M (re-mount)              $bothFlagsMountWins
    /D /M in reverse order still means /M              $bothFlagsReverseOrder
    /D /D /M: two wrongs don't beat a right            $bothFlagsDoubleDMountWins
    /M /M is idempotent and still mounts               $doubleMountFlag
    both flags on unmount- override the prefix         $unmountNameBothFlagsMountWins
    show usage on bad argument                         $badArg
    show usage on /? and exit 0                        $helpAndUsage

  script-name default action (word-boundary match)
    unmount-x.bat (no args) unmounts                   $unmountByNameNoArgs
    unmount-x.bat /M overrides prefix and mounts       $unmountNameOverrideMount
    unmount-x.bat /D matches prefix default            $unmountNameExplicitD
    unmount-x.bat when not mounted prints not-mounted  $unmountByNameNotMounted
    unmount-x.bat bad arg exits 87                     $unmountNameBadArg
    unmount-x.bat /? marks /D as default               $unmountNameHelp
    mount-x.bat /M equals mount-x.bat with no args     $mountFlagMatchesNoArgs
    unmount-x.bat equals mount-x.bat /D                $unmountNameMatchesMountWithD
    foo-mount-x.bat defaults to mount                  $dashMountPrefix
    foo_mount-x.bat defaults to mount                  $underscoreMountPrefix
    foo.mount-x.bat rejected (dot not a separator)     $dotMountPrefixRejected
    foo-unmount-x.bat defaults to unmount              $dashUnmountPrefix
    remount-x.bat (no args) errors with 87             $remountAmbiguous
    remount-x.bat /M explicitly mounts                 $remountOverrideMount
    remount-x.bat /D explicitly unmounts               $remountOverrideUnmount
    remount-x.bat /? shows no default + required note  $remountHelpNote
    dismount-x.bat (no args) errors with 87            $dismountAmbiguous

  paths with special characters
    handle spaces in path                              $spaces
    handle parentheses in path                         $parens
    handle spaces and parentheses in path              $spacesAndParens
    handle exclamation marks in path                   $bangs
    handle spaces and exclamation marks in path        $spacesAndBangs
    handle single quotes in path                       $singleQuotes
    handle percent signs in path                       $percentSign
    handle carets in path                              $caret
    handle ampersands in path                          $ampersand
    handle at/hash/dollar in path                      $atHashDollar
    handle brackets and braces in path                 $bracketsAndBraces
    handle equals comma semicolon in path              $equalsCommaSemi
    handle plus tilde underscore in path               $plusTildeUnderscore
    handle backticks in path                           $backticks
    handle dots in path                                $dots
    kitchen sink of special characters                 $kitchenSink

  remounting
    remount when run from a different location         $remount

  edge cases
    recover from stale mount to deleted directory      $staleMount

  registry resilience
    recreate registry when key deleted externally      $registryRecreated
    unmount cleans registry when subst killed          $unmountCleansOrphanedRegistry
    reg parser ignores unrelated values in same key    $regParserIgnoresSiblings

  filename drive letter
    lowercase letter mounts as uppercase               $lowercaseLetter
    uppercase letter works                             $uppercaseLetter
    three drives mounted concurrently                  $concurrentDrives
    number in filename is rejected                     $numberInName
    missing dash-letter is rejected                    $missingDashLetter
    refuse to clobber a real volume                    $refuseRealVolume
"""

  private def mountToDirectory = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def idempotentNoop = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    lease.runScript(script, extraEnv)(): Unit
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: is already mounted to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:"))
  }

  // /d and /D are both accepted because mount-k.bat uses `if /I "%~1"=="/D"` (case-insensitive). A
  // regression that tightened that to a case-sensitive compare would break lowercase /d -- we keep a
  // test for each case so the asymmetry is visible even if someone "simplifies" the if.
  private def unmountLower = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    lease.runScript(script, extraEnv)(): Unit
    val result = lease.runScript(script, extraEnv)("/d")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive unmounted")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def unmountUpper = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    lease.runScript(script, extraEnv)(): Unit
    val result = lease.runScript(script, extraEnv)("/D")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive unmounted")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def unmountNotMounted = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/D")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: is not mounted via subst")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:")))
  }

  private def badArg = withDrive { lease =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/x")
    (result.exitCode must beEqualTo(87)) and
      (result.stderr must contain("Usage:")) and
      (result.stdout must beEmpty)
  }

  private def helpAndUsage = withDrive { lease =>
    // `/?` must print the full header (version + project URL) and the usage block on stdout and
    // exit 0 without touching the registry. Assert each line in the expected order so a
    // regression dropping the version line -- as happened early in the :usage/:help split -- gets
    // caught instead of hiding behind the generic "Usage:" match. Pin every row (`<no args>`,
    // `/M`, `/D`, `/?`) via regex so tweaks to the column padding don't break the test: only the
    // tokens and their left-to-right order are pinned. The `/?` row in particular was once
    // silently eaten by cmd's `echo` builtin (it treats `/?` as a request for echo's OWN help
    // regardless of leading whitespace; the fix is the `echo.  /?` dot-prefix trick). The
    // `[default]` marker rides on the `<no args>` row and points at `/M` for mount-named stems --
    // that's what locks in the stem-to-default derivation.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/?")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("mount-")) and
      (result.stdout must contain(s"v$version")) and
      (result.stdout must contain("github.com/melezov/mount-k")) and
      (result.stdout must contain("Usage:")) and
      ("""<no args>\s+-\s+same as /M\s+\[default\]""".r.findFirstIn(result.stdout) must beSome) and
      (s"""/M\\s+-\\s+mount $drive:""".r.findFirstIn(result.stdout) must beSome) and
      (s"""/D\\s+-\\s+unmount $drive:""".r.findFirstIn(result.stdout) must beSome) and
      ("""/\?\s+-\s+show this help""".r.findFirstIn(result.stdout) must beSome) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:")))
  }

  private def bothFlagsMountWins = withDrive { lease =>
    // `/M /D` (in that order): /M's effect is deferred to post-loop so /D's in-loop
    // `ACTION=unmount` gets overwritten by the final `if defined ARG_MOUNT set "ACTION=mount"`.
    // A regression to a simple last-wins parser would flip this to unmount and fail.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/M", "/D")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def bothFlagsReverseOrder = withDrive { lease =>
    // Same as above but with the args reversed. Pins that order doesn't matter -- /M wins
    // whether it came first or last. Covers the "first-wins" regression just as the sibling
    // test covers the "last-wins" one.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/D", "/M")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def bothFlagsDoubleDMountWins = withDrive { lease =>
    // Two /Ds don't overcome a single /M. Each /D sets ACTION=unmount in-loop but the post-loop
    // `if defined ARG_MOUNT set "ACTION=mount"` overrides regardless of /D multiplicity.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/D", "/D", "/M")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def doubleMountFlag = withDrive { lease =>
    // Repeated /M is idempotent: ARG_MOUNT is a boolean flag, so setting it twice has no
    // additional effect beyond setting it once.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/M", "/M")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def unmountNameBothFlagsMountWins = withDrive { lease =>
    // On an unmount-prefixed script, the stem default is unmount. Passing both flags still
    // resolves to mount because ARG_MOUNT wins regardless of the prefix -- flags beat the name,
    // and the dual-flag rule beats the solo-flag rule. Mounts must succeed even though the stem
    // defaults to unmount.
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)("/M", "/D")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  // Naming rule: the filename prefix picks the default action but `/M` and `/D` always override.
  // `mount-x.bat` -> default mount; `unmount-x.bat` -> default unmount. Drive letter (the suffix)
  // stays the source of truth for WHICH drive; the prefix only swings the default WHAT. Explicit
  // flags always win so a single file on disk can always do either action regardless of its name.
  // The helpers below copy the script under both names in the same lease directory so a pair of
  // calls operate on the same backing directory and therefore target the same subst mapping.

  private def mountExplicitFlag = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, extraEnv)("/M")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def unmountByNameNoArgs = withDrive { lease =>
    // Mount via mount-x.bat (default), then invoke unmount-x.bat (no args). The unmount-x.bat
    // default action is unmount -- equivalent to mount-x.bat /D. Two script copies share one
    // directory so they point at the same registry subkey and the same subst target.
    val drive = lease.drive
    val mountScript = lease.copyScriptTo("base")
    val unmountScript = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    lease.runScript(mountScript, extraEnv)(): Unit
    val result = lease.runScript(unmountScript, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive unmounted")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def unmountNameOverrideMount = withDrive { lease =>
    // `/M` passed to unmount-x.bat must mount. Proves the flag overrides the prefix-derived
    // default -- a regression that dropped the post-loop `if defined ARG_MOUNT set "ACTION=mount"`
    // would silently unmount here despite the explicit `/M`.
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)("/M")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def unmountNameExplicitD = withDrive { lease =>
    // `/D` on unmount-x.bat is redundant with the prefix default but must still work. Guards
    // against a naive refactor that made the flag parse exclusive of the prefix path.
    val drive = lease.drive
    val mountScript = lease.copyScriptTo("base")
    val unmountScript = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    lease.runScript(mountScript, extraEnv)(): Unit
    val result = lease.runScript(unmountScript, extraEnv)("/D")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive unmounted")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def unmountByNameNotMounted = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: is not mounted via subst")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:")))
  }

  private def unmountNameBadArg = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)("/x")
    (result.exitCode must beEqualTo(87)) and
      (result.stderr must contain("Usage:")) and
      (result.stdout must beEmpty)
  }

  private def unmountNameHelp = withDrive { lease =>
    // `/?` on unmount-x.bat must point the `<no args>` row at `/D` with the `[default]` marker.
    // Pins the prefix-to-default derivation symmetrically with helpAndUsage above.
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)("/?")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"v$version")) and
      (result.stdout must contain("Usage:")) and
      ("""<no args>\s+-\s+same as /D\s+\[default\]""".r.findFirstIn(result.stdout) must beSome) and
      (s"""/M\\s+-\\s+mount $drive:""".r.findFirstIn(result.stdout) must beSome) and
      (s"""/D\\s+-\\s+unmount $drive:""".r.findFirstIn(result.stdout) must beSome) and
      ("""/\?\s+-\s+show this help""".r.findFirstIn(result.stdout) must beSome) and
      (result.stderr must beEmpty)
  }

  private def mountFlagMatchesNoArgs = withDrive { lease =>
    // Equivalence probe: `mount-x.bat` and `mount-x.bat /M` must both produce the same final state.
    // Running both on the same lease directory would mostly short-circuit via exists-noop, so we
    // compare exit code + stdout shape + registry + live subst target instead.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val a = lease.runScript(script, extraEnv)()
    val substAfterA = queryDosDevices().get(drive)
    val registryAfterA = a.registry.get(s"$drive:")
    val b = lease.runScript(script, extraEnv)("/M")
    val substAfterB = queryDosDevices().get(drive)
    (a.exitCode must beEqualTo(0)) and
      (b.exitCode must beEqualTo(0)) and
      (substAfterA must beEqualTo(substAfterB)) and
      (b.registry.get(s"$drive:") must beEqualTo(registryAfterA)) and
      (a.stdout must contain(s"$drive: drive mapped to")) and
      (b.stdout must contain(s"$drive: is already mounted to"))
  }

  // Word-boundary stem matcher: the script derives a default action only when the stem (filename
  // minus `-X`) is the bare word `mount`/`unmount` or ends with one of those preceded by a
  // delimiter from the set `-`, `_`, `.`. `remount` and `dismount` contain `mount` as a literal
  // substring but NOT as a word, so they MUST NOT pick up a default -- the user has to pass /M or
  // /D explicitly or the script refuses with exit 87. These tests pin all three delimiters plus
  // the bare-word case on both sides of the mount/unmount split, and lock in the ambiguous-stem
  // refusal so a future refactor that relaxed the match (e.g. a simple `endsWith mount`) would
  // surface here instead of silently re-enabling the `remount` footgun.

  private def dashMountPrefix = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"foo-mount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:"))
  }

  private def underscoreMountPrefix = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"foo_mount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:"))
  }

  private def dotMountPrefixRejected = withDrive { lease =>
    // Dot is not a recognized prefix separator -- the script normalizes `-` to `_` before the
    // suffix check, so the `foo.mount` stem ends in `.mount` (not `_mount`). Without an explicit
    // /M or /D the script errors with 87 just like any other ambiguous name.
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"foo.mount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(87)) and
      (result.stderr must contain("/M or /D")) and
      (result.stdout must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def dashUnmountPrefix = withDrive { lease =>
    // foo-unmount-x.bat must match the unmount rule (suffix `-unmount`), NOT the mount rule. The
    // order of the checks in the script matters: an eager `endsWith mount` would swallow
    // `foo-unmount` here. This test proves that the unmount match wins even though the string
    // technically also ends in `mount`.
    val drive = lease.drive
    val mountScript = lease.copyScriptTo("base")
    val unmountScript = lease.copyScriptTo("base", s"foo-unmount-${drive.toLower}.bat")
    lease.runScript(mountScript, extraEnv)(): Unit
    val result = lease.runScript(unmountScript, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive unmounted")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def remountAmbiguous = withDrive { lease =>
    // `remount-x.bat` contains `mount` as a substring but not as a word -- the stem is `remount`,
    // no delimiter precedes the `mount`, so the matcher refuses to default. Without an explicit
    // flag the script must error with 87 rather than silently assume mount (the footgun this
    // rule was designed to prevent). Asserts both the exit code and a "/M or /D" hint so a future
    // refactor that silently re-defaulted would fail loudly.
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"remount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(87)) and
      (result.stderr must contain("/M or /D")) and
      (result.stdout must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def remountOverrideMount = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"remount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)("/M")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:"))
  }

  private def remountOverrideUnmount = withDrive { lease =>
    val drive = lease.drive
    val mountScript = lease.copyScriptTo("base")
    val remountScript = lease.copyScriptTo("base", s"remount-${drive.toLower}.bat")
    lease.runScript(mountScript, extraEnv)(): Unit
    val result = lease.runScript(remountScript, extraEnv)("/D")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive unmounted")) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:")))
  }

  private def remountHelpNote = withDrive { lease =>
    // `/?` on an ambiguous name still exits 0 and prints usage, but the `<no args>` row carries
    // an `unavailable; pass /M or /D` hint and no `[default]` marker. A regression that
    // re-enabled a silent default for `remount` would swap the hint for a `same as /M [default]`
    // line and fail both assertions.
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"remount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)("/?")
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("Usage:")) and
      ("""<no args>\s+-\s+unavailable""".r.findFirstIn(result.stdout) must beSome) and
      (s"""/M\\s+-\\s+mount $drive:""".r.findFirstIn(result.stdout) must beSome) and
      (s"""/D\\s+-\\s+unmount $drive:""".r.findFirstIn(result.stdout) must beSome) and
      (result.stdout must not(contain("[default]"))) and
      (result.stdout must contain("/M or /D")) and
      (result.stderr must beEmpty)
  }

  private def dismountAmbiguous = withDrive { lease =>
    // `dismount` is the second real-world confusable. Pinned alongside `remount` so the rule is
    // visibly "word-boundary" rather than "a single hardcoded exception for remount".
    val drive = lease.drive
    val script = lease.copyScriptTo("base", s"dismount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(87)) and
      (result.stderr must contain("/M or /D")) and
      (result.stdout must beEmpty) and
      (queryDosDevices() must not(haveKey(drive)))
  }

  private def unmountNameMatchesMountWithD = withDrive { lease =>
    // Symmetric equivalence probe for the inverse name. Mount via mount-x.bat twice (once for
    // each unmount path), unmount the first via `mount-x.bat /D` and the second via
    // `unmount-x.bat` (no args), and assert both end in an identical state: drive gone, registry
    // clean, correct stdout banner. Since the assertions run after separate mount/unmount cycles
    // on the same lease, any asymmetry between the two code paths would surface as a mismatch.
    val drive = lease.drive
    val mountScript = lease.copyScriptTo("base")
    val unmountScript = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")

    lease.runScript(mountScript, extraEnv)(): Unit
    val viaFlag = lease.runScript(mountScript, extraEnv)("/D")
    val substAfterFlag = queryDosDevices().get(drive)
    val regAfterFlag = viaFlag.registry.get(s"$drive:")

    lease.runScript(mountScript, extraEnv)(): Unit
    val viaName = lease.runScript(unmountScript, extraEnv)()
    val substAfterName = queryDosDevices().get(drive)
    val regAfterName = viaName.registry.get(s"$drive:")

    (viaFlag.exitCode must beEqualTo(0)) and
      (viaName.exitCode must beEqualTo(0)) and
      (viaFlag.stdout must contain(s"$drive: drive unmounted")) and
      (viaName.stdout must contain(s"$drive: drive unmounted")) and
      (substAfterFlag must beNone) and
      (substAfterName must beNone) and
      (regAfterFlag must beNone) and
      (regAfterName must beNone) and
      (viaFlag.stderr must beEmpty) and
      (viaName.stderr must beEmpty)
  }

  private def spaces = withDrive { lease =>
    val result = lease.runScript(lease.copyScriptTo("space dir"), extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("space dir")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"${lease.drive}:"))
  }

  private def parens = withDrive { lease =>
    val result = lease.runScript(lease.copyScriptTo("paren (x86) dir"), extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("paren (x86) dir")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"${lease.drive}:"))
  }

  private def spacesAndParens = withDrive { lease =>
    val result = lease.runScript(lease.copyScriptTo("space (x86) dir"), extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("space (x86) dir")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"${lease.drive}:"))
  }

  // `!` interacts with EnableDelayedExpansion: once enabled, `!VAR!` is substituted even inside a
  // literal. mount-k.bat sidesteps this by resolving `SCRIPT_DIR` from `%~dp0` BEFORE any setlocal
  // EnableDelayedExpansion -- so a path containing `!` is stored literally and a later `!SCRIPT_DIR!`
  // read returns it unchanged. This test proves that ordering still works; reversing it in the
  // script would surface here as a truncated-at-first-bang path.
  private def bangs = withDrive { lease =>
    val drive = lease.drive
    val result = lease.runScript(lease.copyScriptTo("bang!dir"), extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("bang!dir")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (result.registry(s"$drive:") must contain("bang!dir"))
  }

  // Bangs plus spaces: exercises both the delayed-expansion hazard (see `bangs`) and the quoting
  // path for spaces simultaneously, since a fix for one can easily regress the other.
  private def spacesAndBangs = withDrive { lease =>
    val result = lease.runScript(lease.copyScriptTo("space !wow! dir"), extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("space !wow! dir")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"${lease.drive}:"))
  }

  private def singleQuotes = withDrive { lease =>
    val drive = lease.drive
    val result = lease.runScript(lease.copyScriptTo("it's a dir"), extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain("it's a dir")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (result.registry(s"$drive:") must contain("it's a dir"))
  }

  // Helper for the special-character tests: mount from a directory whose name contains the given
  // characters, assert exit 0, and verify both the echo AND the registry value round-trip the name
  // unchanged. The registry assertion is the load-bearing one: it proves the character survived
  // the PowerShell-reg-add escape chain (see mount-k.bat :run_uac), not just the subst call.
  private def specialCharCheck(dirName: String) = withDrive { lease =>
    val drive = lease.drive
    val result = lease.runScript(lease.copyScriptTo(dirName), extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(dirName)) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (result.registry(s"$drive:") must contain(dirName))
  }

  private def percentSign = specialCharCheck("per%cent dir")
  private def caret = specialCharCheck("car^et dir")
  private def ampersand = specialCharCheck("am&p dir")
  private def atHashDollar = specialCharCheck("at@hash#dollar$ dir")
  private def bracketsAndBraces = specialCharCheck("brackets[x]braces{y} dir")
  private def equalsCommaSemi = specialCharCheck("eq=comma,semi; dir")
  private def plusTildeUnderscore = specialCharCheck("plus+tilde~under_score dir")
  private def backticks = specialCharCheck("back`tick` dir")
  private def dots = specialCharCheck("dot.ted..dir...works")
  private def kitchenSink = specialCharCheck("w!@#$&()+=~,;.'d dir")

  private def remount = withDrive { lease =>
    // Moving the script to a new directory must atomically remap the drive: the mount path
    // sees SUBST_STATE="other" (the letter is live but points elsewhere),
    // unmaps the old subst, and maps the new one. substA is snapshotted mid-flight so the
    // assertion actually proves the drive pointed at place-a BEFORE the second mount -- without
    // that snapshot a failed remount would still leave place-a visible at assertion time and pass
    // under the weaker "final state contains place-b" check alone.
    val drive = lease.drive
    lease.runScript(lease.copyScriptTo("place-a"), extraEnv)(): Unit
    val substA = queryDosDevices()
    val result = lease.runScript(lease.copyScriptTo("place-b"), extraEnv)()
    (substA.get(drive) must beSome(contain("place-a"))) and
      (result.exitCode must beEqualTo(0)) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("place-b")))
  }

  private def staleMount = withDrive { lease =>
    // subst survives deletion of the backing directory: after the first mount + rmdir, the drive still
    // shows up but resolves to nothing. Running a fresh mount from a brand-new directory must recover
    // cleanly by remapping (same code path as remount, triggered by SUBST_STATE="other"). The
    // substBefore snapshot proves the stale state was actually set up before the recovery ran --
    // otherwise a test that never managed to install the stale subst would trivially pass.
    val drive = lease.drive
    lease.runScript(lease.copyScriptTo("tmp"), extraEnv)(): Unit
    val substBefore = queryDosDevices()
    deleteRecursive(lease.driveRoot.resolve("tmp"))
    val result = lease.runScript(lease.copyScriptTo("base"), extraEnv)()
    (substBefore.get(drive) must beSome(contain("tmp"))) and
      (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:")) and
      (queryDosDevices().get(drive) must beSome(contain("base")))
  }

  private def registryRecreated = withDrive { lease =>
    // If the live subst still matches (SUBST_STATE="ours") but the registry value was deleted by
    // an outside tool, the script must still notice REG_HAVE is empty and restore the boot-time
    // entry. Covers the "already mounted, reg missing" corner: the SUBST_STATE=ours branch + :mount_reg
    // -- the subst branch short-circuits with "already mounted" but mount_reg still runs and
    // writes REG_WANT because REG_HAVE != REG_WANT.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    lease.runScript(script, extraEnv)(): Unit
    lease.regDeleteValue(s"$drive:")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: is already mounted to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:"))
  }

  private def regParserIgnoresSiblings = withDrive { lease =>
    // The reg-query parser in :mount uses `reg query /v "<DRIVE>:"` piped through
    // `findstr /R /C:"^ *<DRIVE>: "` to skip the key header and blank lines. The findstr anchor
    // (leading whitespace, exact value name, trailing space) is defensive: it guarantees that
    // even if someone adds sibling values to the same key whose names or data happen to contain
    // the drive letter, the parser still picks the exact `<DRIVE>:` value and nothing else.
    //
    // Seed three confusable siblings that could trip a lax parser:
    //   - value name `<DRIVE>` (no colon) -- shares the letter prefix
    //   - value name `Z<DRIVE>:` -- ends with the expected token
    //   - value name `misleading` with data containing `<DRIVE>:` -- would match an unanchored
    //                findstr that looked in the data column
    // Then mount, /D, and assert only the target value is deleted, siblings survive intact.
    val drive = lease.drive
    val script = lease.copyScriptTo("sibling-values")
    lease.runScript(script, extraEnv)(): Unit
    lease.regSetValue(s"$drive", "sibling-no-colon")
    lease.regSetValue(s"Z$drive:", s"sibling-prefix-confusion")
    lease.regSetValue("misleading", s"contains $drive: in the data column")
    val result = lease.runScript(script, extraEnv)("/D")
    (result.exitCode must beEqualTo(0)) and
      (result.stderr must beEmpty) and
      (result.registry must not(haveKey(s"$drive:"))) and
      (result.registry.get(s"$drive") must beSome("sibling-no-colon")) and
      (result.registry.get(s"Z$drive:") must beSome("sibling-prefix-confusion")) and
      (result.registry.get("misleading") must beSome(s"contains $drive: in the data column"))
  }

  private def unmountCleansOrphanedRegistry = withDrive { lease =>
    // After a successful mount we manually kill the live subst (simulating someone running
    // `subst /D` outside mount-k.bat), leaving the HKLM registry entry orphaned -- on next boot
    // Session Manager would recreate a subst pointing at a directory the user thought they retired.
    // Running `/D` must clean the reg entry even though there is no live subst to unmap (flows
    // through the "not mounted via subst" branch straight to :unmount_reg).
    //
    // The `_sentinel` value is seeded to guard against a lazy fix that deletes the entire key
    // instead of just the `<DRIVE>:` value. Any unrelated sibling must survive the cleanup.
    val drive = lease.drive
    val script = lease.copyScriptTo("base")
    val mounted = lease.runScript(script, extraEnv)()
    lease.regSetValue("_sentinel", "preserve-me")
    (mounted.registry must haveKey(s"$drive:")) and {
      substDelete(drive): Unit
      val result = lease.runScript(script, extraEnv)("/D")
      (result.exitCode must beEqualTo(0)) and
        (result.stdout must contain(s"$drive: is not mounted via subst")) and
        (result.stderr must beEmpty) and
        (result.registry must not(haveKey(s"$drive:"))) and
        (result.registry.get("_sentinel") must beSome("preserve-me"))
    }
  }

  // The drive letter is derived from the filename's last character via an `if /I` match against
  // A..Z, so the final letter resolves the same whether typed in lower or upper case. Covering
  // both cases defends against a regression that tightens the compare to case-sensitive -- which
  // would make only uppercase filenames work and silently break the common lowercase convention.
  private def lowercaseLetter = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("lower", s"mount-${drive.toLower}.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:"))
  }

  private def uppercaseLetter = withDrive { lease =>
    val drive = lease.drive
    val script = lease.copyScriptTo("upper", s"mount-$drive.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(0)) and
      (result.stdout must contain(s"$drive: drive mapped to")) and
      (result.stderr must beEmpty) and
      (result.registry must haveKey(s"$drive:"))
  }

  private def concurrentDrives =
    // Runs three mount scripts for three distinct drive letters in sequence (within this example).
    // This isn't a concurrency test in the race-condition sense (that's explicitly skipped per
    // findings/skipped-for-now.txt [B6]) -- it's a "no cross-drive interference" test: state
    // written for one drive must not pollute the independent logic for the other two. Regressions
    // here would typically be accidental globals in cmd variables (SCRIPT_DIR, REG_HAVE,
    // MOUNT_STATE) leaking across a single setlocal scope. Skipped when the pool only granted one
    // or two drives to this spec.
    if drives.length < 3 then
      skipped(s"need 3 drives, spec only reserved ${drives.length}")
    else withDrives(3) { leases =>
      val Seq(l1, l2, l3) = leases
      val d1 = l1.drive
      val d2 = l2.drive
      val d3 = l3.drive
      val script1 = l1.copyScriptTo("con")
      val script2 = l2.copyScriptTo("con", s"mount-${d2.toLower}.bat")
      val script3 = l3.copyScriptTo("con", s"mount-${d3.toLower}.bat")
      val r1 = l1.runScript(script1, extraEnv)()
      val r2 = l2.runScript(script2, extraEnv)()
      val r3 = l3.runScript(script3, extraEnv)()
      val subst = queryDosDevices()
      (r1.exitCode must beEqualTo(0)) and
        (r2.exitCode must beEqualTo(0)) and
        (r3.exitCode must beEqualTo(0)) and
        (subst must haveKey(d1)) and
        (subst must haveKey(d2)) and
        (subst must haveKey(d3)) and
        (r1.registry must haveKey(s"$d1:")) and
        (r2.registry must haveKey(s"$d2:")) and
        (r3.registry must haveKey(s"$d3:"))
    }

  private def numberInName = withDrive { lease =>
    val script = lease.copyScriptTo("num", "mount-3.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(123)) and
      (result.stderr must contain("must end with")) and
      (result.stdout must beEmpty)
  }

  private def missingDashLetter = withDrive { lease =>
    val script = lease.copyScriptTo("nodash", "mount.bat")
    val result = lease.runScript(script, extraEnv)()
    (result.exitCode must beEqualTo(123)) and
      (result.stderr must contain("must end with")) and
      (result.stdout must beEmpty)
  }

  private def refuseRealVolume = withDrive { lease =>
    // If the user renames the script to target a letter that already belongs to a real fixed
    // volume (e.g. mount-c.bat when C: is the system drive), the DRIVE_IN_USE check at
    // in :mount fires -- `vol %DRIVE%:` succeeds, `SUBST_STATE` is still empty, and
    // :mount refuses with exit 85. Without this guard the script could silently clobber a real
    // disk letter with a subst, hiding the underlying volume.
    //
    // `realDrive` is the first letter from `existingRootAccessDriveForTest` that passed a write probe at
    // JVM startup -- a writable fixed volume. When None (no candidate in the range is writable), skip.
    realDrive match
      case None =>
        skipped("no existingRootAccessDriveForTest letter was writable at startup")
      case Some(realLetter) =>
        val script = lease.copyScriptTo("real-volume", s"mount-$realLetter.bat")
        val result = lease.runScript(script, extraEnv)()
        (result.exitCode must beEqualTo(85)) and
          (result.stderr must contain(s"$realLetter: is already in use")) and
          (result.stdout must beEmpty) and
          (result.registry must not(haveKey(s"$realLetter:")))
  }
