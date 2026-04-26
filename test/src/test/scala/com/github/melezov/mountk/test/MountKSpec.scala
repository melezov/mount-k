package com.github.melezov.mountk
package test

import org.specs2.specification.core.SpecStructure

import scala.language.implicitConversions

class MountKAdminSpec extends MountKSpec(elevated = true)
class MountKUserSpec extends MountKSpec(elevated = false)

// Behavioral spec for mount-k.bat under the default PERSIST_MODE=always: argument parsing,
// stem-derived action defaults, special-character path round-tripping, remount/stale recovery,
// registry resilience under external corruption, and drive-letter sanity. Tests that exercise
// PERSIST_MODE differences (always echoes, never, if-suffixed, validator) live in the
// `persistence` sub-package -- this file stays focused on the script's general behavior.
abstract class MountKSpec(override val elevated: Boolean) extends ScriptSpec:

  val parallelism = 6

  // Pin PERSIST_MODE=always so registry-touching assertions in this spec stay valid; the script's
  // shipped default `if-suffixed` would only persist when the filename carries a suffix, which
  // these tests don't exercise.
  override protected def persistMode: Option[String] = Some("always")

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

  script-name default action
    unmount-x.bat (no args) unmounts                   $unmountByNameNoArgs
    unmount-x.bat /M overrides prefix and mounts       $unmountNameOverrideMount
    unmount-x.bat /D matches prefix default            $unmountNameExplicitD
    unmount-x.bat when not mounted prints not-mounted  $unmountByNameNotMounted
    unmount-x.bat bad arg exits 87                     $unmountNameBadArg
    unmount-x.bat /? marks /D as default               $unmountNameHelp
    mount-x.bat /M equals mount-x.bat with no args     $mountFlagMatchesNoArgs
    unmount-x.bat equals mount-x.bat /D                $unmountNameMatchesMountWithD
    foo-mount-x.bat (non-canonical) rejected           $fooDashMountRejected
    foo-mount-x.bat /M still rejected by parser        $fooDashMountSlashMRejected
    foo_mount-x.bat (non-canonical) rejected           $fooUnderscoreMountRejected
    foo.mount-x.bat (non-canonical) rejected           $fooDotMountRejected
    foo-unmount-x.bat (non-canonical) rejected         $fooDashUnmountRejected
    remount-x.bat (substring confusable) rejected      $remountRejected
    dismount-x.bat (substring confusable) rejected     $dismountRejected
    mountain-x.bat (substring confusable) rejected     $mountainRejected

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

  private def mountToDirectory = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to `") and
      (result.stdout has """drive mapped to `[^`]+\\base`""".r) and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def idempotentNoop = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` is already mounted to `") and
      (result.stdout has """is already mounted to `[^`]+\\base`""".r) and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  // /d and /D are both accepted because mount-k.bat uses `if /I "%~1"=="/D"` (case-insensitive). A
  // regression that tightened that to a case-sensitive compare would break lowercase /d -- we keep a
  // test for each case so the asymmetry is visible even if someone "simplifies" the if.
  private def unmountLower = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)("/d")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive unmounted") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def unmountUpper = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive unmounted") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def unmountNotMounted = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` is not mounted via subst") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def badArg = withDrive { (lease, _) =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/x")
    result was ERROR_INVALID_PARAMETER and
      (result.stderr has "Usage:") and
      result.stdout.isEmpty
  }

  private def helpAndUsage = withDrive { (lease, drive) =>
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
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has "mount-") and
      (result.stdout has s"$version") and
      (result.stdout has "github.com/melezov/mount-k") and
      (result.stdout has "Usage:") and
      (result.stdout has """<no args>\s+-\s+same as /M \(mount\) since the filename defines the default action""".r) and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `.+` and persist across reboots""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:` and remove the boot-time registry entry""".r) and
      (result.stdout has """/PM\s+<mode>\s+-\s+override PERSIST_MODE - one of: `always` \[default\], `if-suffixed`, `ask`, `never`""".r) and
      (result.stdout has """/\?\s+-\s+show this help""".r) and
      result.stderr.isEmpty and
      (result.persisted hasNot drive)
  }

  private def bothFlagsMountWins = withDrive { (lease, drive) =>
    // `/M /D` (in that order): /M's effect is deferred to post-loop so /D's in-loop
    // `ACTION=unmount` gets overwritten by the final `if defined ARG_MOUNT set "ACTION=mount"`.
    // A regression to a simple last-wins parser would flip this to unmount and fail.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/M", "/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def bothFlagsReverseOrder = withDrive { (lease, drive) =>
    // Same as above but with the args reversed. Pins that order doesn't matter -- /M wins
    // whether it came first or last. Covers the "first-wins" regression just as the sibling
    // test covers the "last-wins" one.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/D", "/M")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def bothFlagsDoubleDMountWins = withDrive { (lease, drive) =>
    // Two /Ds don't overcome a single /M. Each /D sets ACTION=unmount in-loop but the post-loop
    // `if defined ARG_MOUNT set "ACTION=mount"` overrides regardless of /D multiplicity.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/D", "/D", "/M")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def doubleMountFlag = withDrive { (lease, drive) =>
    // Repeated /M is idempotent: ARG_MOUNT is a boolean flag, so setting it twice has no
    // additional effect beyond setting it once.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/M", "/M")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def unmountNameBothFlagsMountWins = withDrive { (lease, drive) =>
    // On an unmount-prefixed script, the stem default is unmount. Passing both flags still
    // resolves to mount because ARG_MOUNT wins regardless of the prefix -- flags beat the name,
    // and the dual-flag rule beats the solo-flag rule. Mounts must succeed even though the stem
    // defaults to unmount.
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script)("/M", "/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  // Naming rule: the filename prefix picks the default action but `/M` and `/D` always override.
  // `mount-x.bat` -> default mount; `unmount-x.bat` -> default unmount. Drive letter (the suffix)
  // stays the source of truth for WHICH drive; the prefix only swings the default WHAT. Explicit
  // flags always win so a single file on disk can always do either action regardless of its name.
  // The helpers below copy the script under both names in the same lease directory so a pair of
  // calls operate on the same backing directory and therefore target the same subst mapping.

  private def mountExplicitFlag = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/M")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def unmountByNameNoArgs = withDrive { (lease, drive) =>
    // Mount via mount-x.bat (default), then invoke unmount-x.bat (no args). The unmount-x.bat
    // default action is unmount -- equivalent to mount-x.bat /D. Two script copies share one
    // directory so they point at the same registry subkey and the same subst target.
    val mountScript = lease.copyScriptTo("base")
    val unmountScript = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    lease.runScript(mountScript)(): Unit
    val result = lease.runScript(unmountScript)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive unmounted") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def unmountNameOverrideMount = withDrive { (lease, drive) =>
    // `/M` passed to unmount-x.bat must mount. Proves the flag overrides the prefix-derived
    // default -- a regression that dropped the post-loop `if defined ARG_MOUNT set "ACTION=mount"`
    // would silently unmount here despite the explicit `/M`.
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script)("/M")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def unmountNameExplicitD = withDrive { (lease, drive) =>
    // `/D` on unmount-x.bat is redundant with the prefix default but must still work. Guards
    // against a naive refactor that made the flag parse exclusive of the prefix path.
    val mountScript = lease.copyScriptTo("base")
    val unmountScript = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    lease.runScript(mountScript)(): Unit
    val result = lease.runScript(unmountScript)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive unmounted") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def unmountByNameNotMounted = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` is not mounted via subst") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def unmountNameBadArg = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script)("/x")
    result was ERROR_INVALID_PARAMETER and
      (result.stderr has "Usage:") and
      result.stdout.isEmpty
  }

  private def unmountNameHelp = withDrive { (lease, drive) =>
    // `/?` on unmount-x.bat must point the `<no args>` row at `/D (unmount)`. Pins the prefix-to-
    // default derivation symmetrically with helpAndUsage above. Same effect tails as the mount
    // case (always-mode wraps both /M and /D in registry effects) so both rows carry their
    // mode-driven explanations.
    val script = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has s"$version") and
      (result.stdout has "Usage:") and
      (result.stdout has s"Usage: unmount-${drive.toLower}.bat") and
      (result.stdout has """<no args>\s+-\s+same as /D \(unmount\) since the filename defines the default action""".r) and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `.+` and persist across reboots""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:` and remove the boot-time registry entry""".r) and
      (result.stdout has """/\?\s+-\s+show this help""".r) and
      result.stderr.isEmpty
  }

  private def mountFlagMatchesNoArgs = withDrive { (lease, drive) =>
    // Equivalence probe: `mount-x.bat` and `mount-x.bat /M` must both produce the same final state.
    // Running both on the same lease directory would mostly short-circuit via exists-noop, so we
    // compare exit code + stdout shape + registry + live subst target instead.
    val script = lease.copyScriptTo("base")
    val a = lease.runScript(script)()
    val b = lease.runScript(script)("/M")
    a was SUCCESS and
      (b was SUCCESS) and
      (a.live.underlying.get(drive) must beEqualTo(b.live.underlying.get(drive))) and
      (b.persisted(drive) must beEqualTo(a.persisted(drive))) and
      (a.stdout has s"`$drive:` drive mapped to") and
      (b.stdout has s"`$drive:` is already mounted to")
  }

  // Strict A..Z parser: the script accepts ONLY the four canonical filename templates
  // (mount-X.bat, mount-X-and-remember.bat, unmount-X.bat, unmount-X-and-forget.bat). Any other
  // shape -- prefixed (`foo-mount-x.bat`), substring-confusable (`remount-x.bat`,
  // `dismount-x.bat`, `mountain-x.bat`), missing dash, etc. -- exits 123 (Unexpected filename)
  // before argument or registry processing. /M, /D, and /? do NOT bypass the rejection because
  // the parser runs first. These tests pin a representative sample so a future loosening of the
  // parser (an `endsWith mount` or `startsWith mount-` regression) would surface here.

  private def fooDashMountRejected = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base", s"foo-mount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has s"Unexpected filename: `foo-mount-${drive.toLower}.bat`") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def fooDashMountSlashMRejected = withDrive { (lease, drive) =>
    // /M (or /D, /?) cannot rescue a non-canonical filename: parser runs before arg loop.
    val script = lease.copyScriptTo("base", s"foo-mount-${drive.toLower}.bat")
    val result = lease.runScript(script)("/M")
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename") and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def fooUnderscoreMountRejected = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base", s"foo_mount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive)
  }

  private def fooDotMountRejected = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base", s"foo.mount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive)
  }

  private def fooDashUnmountRejected = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base", s"foo-unmount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive)
  }

  private def remountRejected = withDrive { (lease, drive) =>
    // `remount-x.bat` is a real-world confusable: contains `mount` as a literal substring but
    // does not match `mount-X` exactly. Strict parser refuses; the user has to rename to one of
    // the canonical four templates.
    val script = lease.copyScriptTo("base", s"remount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def dismountRejected = withDrive { (lease, drive) =>
    // Second confusable, pinned alongside `remount` so the rule reads as "strict template match",
    // not "hardcoded blocklist of one name".
    val script = lease.copyScriptTo("base", s"dismount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive)
  }

  private def mountainRejected = withDrive { (lease, drive) =>
    // Third confusable; `mountain` extends `mount` without a separator after.
    val script = lease.copyScriptTo("base", s"mountain-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive)
  }

  private def unmountNameMatchesMountWithD = withDrive { (lease, drive) =>
    // Symmetric equivalence probe for the inverse name. Mount via mount-x.bat twice (once for
    // each unmount path), unmount the first via `mount-x.bat /D` and the second via
    // `unmount-x.bat` (no args), and assert both end in an identical state: drive gone, registry
    // clean, correct stdout banner. Since the assertions run after separate mount/unmount cycles
    // on the same lease, any asymmetry between the two code paths would surface as a mismatch.
    val mountScript = lease.copyScriptTo("base")
    val unmountScript = lease.copyScriptTo("base", s"unmount-${drive.toLower}.bat")

    lease.runScript(mountScript)(): Unit
    val viaFlag = lease.runScript(mountScript)("/D")

    lease.runScript(mountScript)(): Unit
    val viaName = lease.runScript(unmountScript)()

    viaFlag was SUCCESS and
      (viaName was SUCCESS) and
      (viaFlag.stdout has s"`$drive:` drive unmounted") and
      (viaName.stdout has s"`$drive:` drive unmounted") and
      (viaFlag.live hasNot drive) and
      (viaName.live hasNot drive) and
      (viaFlag.persisted hasNot drive) and
      (viaName.persisted hasNot drive) and
      viaFlag.stderr.isEmpty and
      viaName.stderr.isEmpty
  }

  private def spaces = withDrive { (lease, drive) =>
    val result = lease.runScript(lease.copyScriptTo("space dir"))()
    result was SUCCESS and
      (result.stdout has "space dir") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  private def parens = withDrive { (lease, drive) =>
    val result = lease.runScript(lease.copyScriptTo("paren (x86) dir"))()
    result was SUCCESS and
      (result.stdout has "paren (x86) dir") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  private def spacesAndParens = withDrive { (lease, drive) =>
    val result = lease.runScript(lease.copyScriptTo("space (x86) dir"))()
    result was SUCCESS and
      (result.stdout has "space (x86) dir") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  // `!` interacts with EnableDelayedExpansion: once enabled, `!VAR!` is substituted even inside a
  // literal. mount-k.bat sidesteps this by resolving `SCRIPT_DIR` from `%~dp0` BEFORE any setlocal
  // EnableDelayedExpansion -- so a path containing `!` is stored literally and a later `!SCRIPT_DIR!`
  // read returns it unchanged. This test proves that ordering still works; reversing it in the
  // script would surface here as a truncated-at-first-bang path.
  private def bangs = withDrive { (lease, drive) =>
    val result = lease.runScript(lease.copyScriptTo("bang!dir"))()
    result was SUCCESS and
      (result.stdout has "bang!dir") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.persisted(drive) must beSome(contain("bang!dir")))
  }

  // Bangs plus spaces: exercises both the delayed-expansion hazard (see `bangs`) and the quoting
  // path for spaces simultaneously, since a fix for one can easily regress the other.
  private def spacesAndBangs = withDrive { (lease, drive) =>
    val result = lease.runScript(lease.copyScriptTo("space !wow! dir"))()
    result was SUCCESS and
      (result.stdout has "space !wow! dir") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  private def singleQuotes = withDrive { (lease, drive) =>
    val result = lease.runScript(lease.copyScriptTo("it's a dir"))()
    result was SUCCESS and
      (result.stdout has "it's a dir") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.persisted(drive) must beSome(contain("it's a dir")))
  }

  // Helper for the special-character tests: mount from a directory whose name contains the given
  // characters, assert exit 0, and verify both the echo AND the registry value round-trip the name
  // unchanged. The registry assertion is the load-bearing one: it proves the character survived
  // the PowerShell-reg-add escape chain (see mount-k.bat :run_uac), not just the subst call.
  private def specialCharCheck(dirName: String) = withDrive { (lease, drive) =>
    val result = lease.runScript(lease.copyScriptTo(dirName))()
    result was SUCCESS and
      (result.stdout has dirName) and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.persisted(drive) must beSome(contain(dirName)))
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

  private def remount = withDrive { (lease, drive) =>
    // Moving the script to a new directory must atomically remap the drive: the mount path
    // sees SUBST_STATE="other" (the letter is live but points elsewhere),
    // unmaps the old subst, and maps the new one. substA is snapshotted mid-flight so the
    // assertion actually proves the drive pointed at place-a BEFORE the second mount -- without
    // that snapshot a failed remount would still leave place-a visible at assertion time and pass
    // under the weaker "final state contains place-b" check alone.
    val resultA = lease.runScript(lease.copyScriptTo("place-a"))()
    val result = lease.runScript(lease.copyScriptTo("place-b"))()
    resultA was SUCCESS and
      (resultA.live(drive) must beSome(contain("place-a"))) and
      (result was SUCCESS) and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("place-b")))
  }

  private def staleMount = withDrive { (lease, drive) =>
    // subst survives deletion of the backing directory: after the first mount + rmdir, the drive still
    // shows up but resolves to nothing. Running a fresh mount from a brand-new directory must recover
    // cleanly by remapping (same code path as remount, triggered by SUBST_STATE="other"). The
    // substBefore snapshot proves the stale state was actually set up before the recovery ran --
    // otherwise a test that never managed to install the stale subst would trivially pass.
    val resultBefore = lease.runScript(lease.copyScriptTo("tmp"))()
    deleteRecursive(lease.driveRoot.resolve("tmp"))
    val result = lease.runScript(lease.copyScriptTo("base"))()
    resultBefore was SUCCESS and
      (resultBefore.live(drive) must beSome(contain("tmp"))) and
      (result was SUCCESS) and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def registryRecreated = withDrive { (lease, drive) =>
    // If the live subst still matches (SUBST_STATE="ours") but the registry value was deleted by
    // an outside tool, the script must still notice REG_HAVE is empty and restore the boot-time
    // entry. Covers the "already mounted, reg missing" corner: the SUBST_STATE=ours branch + :mount_reg
    // -- the subst branch short-circuits with "already mounted" but mount_reg still runs and
    // writes REG_WANT because REG_HAVE != REG_WANT.
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    lease.regDeleteValue(s"$drive:")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` is already mounted to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def regParserIgnoresSiblings = withDrive { (lease, drive) =>
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
    val script = lease.copyScriptTo("sibling-values")
    lease.runScript(script)(): Unit
    lease.regSetValue(s"$drive", "sibling-no-colon")
    lease.regSetValue(s"Z$drive:", "sibling-prefix-confusion")
    lease.regSetValue("misleading", s"contains $drive: in the data column")
    val result = lease.runScript(script)("/D")
    val raw = lease.regQueryValues
    result was SUCCESS and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive) and
      (raw.get(s"$drive") must beSome("sibling-no-colon")) and
      (raw.get(s"Z$drive:") must beSome("sibling-prefix-confusion")) and
      (raw.get("misleading") must beSome(s"contains $drive: in the data column"))
  }

  private def unmountCleansOrphanedRegistry = withDrive { (lease, drive) =>
    // After a successful mount we manually kill the live subst (simulating someone running
    // `subst /D` outside mount-k.bat), leaving the HKLM registry entry orphaned -- on next boot
    // Session Manager would recreate a subst pointing at a directory the user thought they retired.
    // Running `/D` must clean the reg entry even though there is no live subst to unmap (flows
    // through the "not mounted via subst" branch straight to :unmount_reg).
    //
    // The `_sentinel` value is seeded to guard against a lazy fix that deletes the entire key
    // instead of just the `<DRIVE>:` value. Any unrelated sibling must survive the cleanup.
    val script = lease.copyScriptTo("base")
    val mounted = lease.runScript(script)()
    lease.regSetValue("_sentinel", "preserve-me")
    mounted.persisted has drive and {
      substDelete(drive): Unit
      val result = lease.runScript(script)("/D")
      result was SUCCESS and
        (result.stdout has s"`$drive:` is not mounted via subst") and
        result.stderr.isEmpty and
        (result.persisted hasNot drive) and
        (result.live hasNot drive) and
        (lease.regQueryValues.get("_sentinel") must beSome("preserve-me"))
    }
  }

  // The drive letter is derived from the filename's last character via an `if /I` match against
  // A..Z, so the final letter resolves the same whether typed in lower or upper case. Covering
  // both cases defends against a regression that tightens the compare to case-sensitive -- which
  // would make only uppercase filenames work and silently break the common lowercase convention.
  private def lowercaseLetter = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("lower", s"mount-${drive.toLower}.bat")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  private def uppercaseLetter = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("upper", s"mount-$drive.bat")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive)
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
      val r1 = l1.runScript(script1)()
      val r2 = l2.runScript(script2)()
      val r3 = l3.runScript(script3)()
      r1 was SUCCESS and
        (r2 was SUCCESS) and
        (r3 was SUCCESS) and
        (r1.live has d1) and
        (r2.live has d2) and
        (r3.live has d3) and
        (r1.persisted has d1) and
        (r2.persisted has d2) and
        (r3.persisted has d3)
    }

  private def numberInName = withDrive { (lease, _) =>
    val script = lease.copyScriptTo("num", "mount-3.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename: `mount-3.bat`") and
      (result.stderr has "mount-<drive>[-and-remember].bat") and
      result.stdout.isEmpty
  }

  private def missingDashLetter = withDrive { (lease, _) =>
    val script = lease.copyScriptTo("nodash", "mount.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      (result.stderr has "Unexpected filename: `mount.bat`") and
      (result.stderr has "mount-<drive>[-and-remember].bat") and
      result.stdout.isEmpty
  }

  private def refuseRealVolume = withDrive { (lease, _) =>
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
        val result = lease.runScript(script)()
        result was ERROR_ALREADY_ASSIGNED and
          (result.stderr has s"`$realLetter:` is already in use") and
          result.stdout.isEmpty and
          (result.persisted hasNot realLetter)
  }
