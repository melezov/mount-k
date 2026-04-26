package com.github.melezov.mountk
package test
package persistence

import org.specs2.specification.core.SpecStructure

import scala.language.implicitConversions

class MountKPersistAlwaysAdminSpec extends MountKPersistAlwaysSpec(elevated = true)

class MountKPersistAlwaysUserSpec extends MountKPersistAlwaysSpec(elevated = false)

// PERSIST_MODE=always is the always-persist mode (the v0.2.0 behavior): every successful mount
// writes or refreshes the boot-time registry entry and every successful unmount removes it. The
// v0.3.0 "Important change" adds stdout/stderr echoes on the registry round-trip so a caller can
// tell success from a dismissed UAC prompt or a reg-add/delete failure by reading the script's
// output instead of inferring it from the exit code. Previously this path was silent. The
// validator section pins the rejection path for unsupported PERSIST_MODE values -- the allowlist
// is `always`, `never`, `if-suffixed`, and `ask`.
abstract class MountKPersistAlwaysSpec(override val elevated: Boolean) extends ScriptSpec:

  val parallelism = 4

  override protected def persistMode: Option[String] = Some("always")

  override def is: SpecStructure = args.execute(threadsNb = drives.length) ^ s2"""
  PERSIST_MODE=always persistence echoes
    mount echoes persisted-to-registry                 $mountEchoesPersisted
    idempotent mount echoes already-persisted          $mountNoopEchoesAlreadyPersisted
    unmount echoes removed-from-registry               $unmountEchoesRemoval
    unmount-when-absent echoes was-not-persisted       $unmountAbsentEchoesNotPersisted

  PERSIST_MODE validator
    invalid value exits 13 with Unsupported message    $invalidPersistModeRejected
    empty value is rejected                            $emptyPersistModeRejected

  PERSIST_MODE /PM CLI override
    /PM always accepted                                $cliPmAlwaysAccepted
    /PM if-suffixed accepted                           $cliPmIfSuffixedAccepted
    /PM ask accepted                                   $cliPmAskAccepted
    /PM never accepted                                 $cliPmNeverAccepted
    /PM bogus rejected with exit 87                    $cliPmInvalidRejected
    /PM with no value rejected with exit 87            $cliPmMissingValueRejected
    /PM with switch as value (/PM /M) rejected         $cliPmSwitchAsValueRejected
    repeated /PM keeps the last value                  $cliPmRepeatedLastWins
    /PM with hostile chars survives echo               $cliPmHostileCharsSurviveEcho

  filename suffix grammar
    suffix-after-drive name accepted in always mode    $suffixAfterDriveAccepted

  /? rendering under PERSIST_MODE=always
    /? shows always-mode effect tails on /M and /D     $helpUnderAlwaysShowsEffects
    /? text immune to /D runtime override              $helpImmuneToSlashD
    /? text immune to /PM runtime override             $helpImmuneToSlashPm

  PERSIST_MODE casing variants
    /PM ALWAYS (uppercase) accepted                    $cliPmAlwaysUpperAccepted
    /PM If-Suffixed (mixed) accepted                   $cliPmIfSuffixedMixedAccepted
    /PM aSk (mixed) accepted                           $cliPmAskMixedAccepted
    /PM NEVER (uppercase) accepted                     $cliPmNeverUpperAccepted
    hardcoded `Always` PERSIST_MODE accepted           $hardcodedPmMixedAccepted

  ACTION normalization on flag override
    /M on unmount-X-and-forget.bat flattens to plain M $slashMOnDPlusFFlattens
    /D on mount-X-and-remember.bat flattens to plain D $slashDOnMPlusRFlattens
    /PM never on suffixed mount-name downgrades        $slashPmNeverOnSuffixedDowngrades
"""

  private def mountEchoesPersisted = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("persisted-echo")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` persisted to") and
      (result.stdout has "persisted-echo") and
      (result.stdout has "in registry") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  private def mountNoopEchoesAlreadyPersisted = withDrive { (lease, drive) =>
    // Second mount short-circuits via REG_HAVE==REG_WANT and takes the noop branch. The echo
    // proves we went through :mount_reg's compare-and-skip path rather than silently falling
    // through to a reg add that would have duplicated the write.
    val script = lease.copyScriptTo("noop-echo")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` is already persisted in registry") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  private def unmountEchoesRemoval = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("remove-echo")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` removed from registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive)
  }

  private def unmountAbsentEchoesNotPersisted = withDrive { (lease, drive) =>
    // Unmount a drive that was never mounted: the subst path prints "not mounted via subst" and
    // :unmount_reg takes the REG_HAVE-undefined branch, echoing "was not persisted in registry".
    // Together they confirm both cleanup paths are observable, not silent.
    val script = lease.copyScriptTo("absent-echo")
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` is not mounted via subst") and
      (result.stdout has s"`$drive:` was not persisted in registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive)
  }

  private def invalidPersistModeRejected = withDrive { (lease, drive) =>
    // PERSIST_MODE is a compile-time literal in the script -- a value outside the allowlist means
    // someone shipped a bad script, not that a user passed a bad flag. Exit 13 signals "error in
    // the script itself" rather than ERROR_INVALID_PARAMETER (which is reserved for bad CLI input).
    val script = lease.copyScriptTo("bogus-mode")
    lease.patchPersistMode(script, "bogus")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_DATA and
      (result.stderr has "Unsupported PERSIST_MODE: bogus") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def emptyPersistModeRejected = withDrive { (lease, drive) =>
    // An empty value is neither `always` nor `never` nor any reserved mode -- it must trip the
    // validator just like a typo would. Also locks in that cmd's quoted-compare doesn't silently
    // succeed when the value is blank. Same exit-13 script-invariant semantics as
    // invalidPersistModeRejected.
    val script = lease.copyScriptTo("empty-mode")
    lease.patchPersistMode(script, "")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_DATA and
      (result.stderr has "Unsupported PERSIST_MODE:") and
      result.stdout.isEmpty and
      (result.persisted hasNot drive)
  }

  private def cliPmAlwaysAccepted = withDrive { (lease, drive) =>
    // /PM always overrides the script-default PERSIST_MODE before the allowlist check; the
    // validator must accept it and move on without emitting any "Unsupported" message.
    val script = lease.copyScriptTo("pm-always")
    val result = lease.runScript(script)("/PM", "always")
    result.stderr hasNot "Unsupported"
  }

  private def cliPmIfSuffixedAccepted = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("pm-if-suffixed")
    val result = lease.runScript(script)("/PM", "if-suffixed")
    result.stderr hasNot "Unsupported"
  }

  private def cliPmAskAccepted = withDrive { (lease, drive) =>
    // /PM ask flips runtime to ask-mode, which would block on stdin at the registry prompt;
    // pipe `N` so the script answers and exits without hanging.
    val script = lease.copyScriptTo("pm-ask")
    val stdinN = Some(new java.io.ByteArrayInputStream("N\n".getBytes))
    val result = lease.runScript(script, stdin = stdinN)("/PM", "ask")
    result.stderr hasNot "Unsupported"
  }

  private def cliPmNeverAccepted = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("pm-never")
    val result = lease.runScript(script)("/PM", "never")
    result.stderr hasNot "Unsupported"
  }

  private def cliPmInvalidRejected = withDrive { (lease, drive) =>
    // /PM <bogus> is user-supplied input, distinct from a broken script default (which exits 13).
    // Exit 87 plus a stderr message naming /PM so the user sees which CLI arg was wrong, plus the
    // allowlist and the script's current default so they know which values would have worked.
    val script = lease.copyScriptTo("pm-bogus")
    val result = lease.runScript(script)("/PM", "bogus")
    result was ERROR_INVALID_PARAMETER and
      (result.stderr has "Unsupported /PM `bogus`") and
      (result.stderr has "`always` [default], `if-suffixed`, `ask`, `never`")
  }

  private def cliPmSwitchAsValueRejected = withDrive { (lease, _) =>
    // The arg parser greedily consumes the next token as the /PM value, even if it looks like a
    // switch. So `/PM /M` sets PERSIST_MODE=/M, fails the allowlist, exits 87. A regression that
    // peeks ahead and treats `/M` as a switch instead would silently accept `/PM` with no value.
    val script = lease.copyScriptTo("pm-switch-as-value")
    val result = lease.runScript(script)("/PM", "/M")
    result was ERROR_INVALID_PARAMETER and
      (result.stderr has "Unsupported /PM `/M`")
  }

  private def cliPmRepeatedLastWins = withDrive { (lease, drive) =>
    // `/PM always /PM never` -- the arg loop applies each `/PM <value>` independently, so the
    // last one wins. Pin that semantics so a regression that errored on duplicate flags would
    // surface here.
    val script = lease.copyScriptTo("pm-repeated")
    val result = lease.runScript(script)("/PM", "always", "/PM", "never")
    // PM=never neutralizes registry persistence even though the script ships `if-suffixed` and
    // we've pinned `always` here -- so a successful mount with no registry write proves
    // last-wins semantics.
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.stdout hasNot "persisted") and
      (result.persisted hasNot drive)
  }

  private def cliPmHostileCharsSurviveEcho = withDrive { (lease, _) =>
    // A bogus mode value containing `)` used to break the rejection echo because it ran inside an
    // `if defined PERSIST_MODE_VIA_ARGS ( ... )` parens block -- the unescaped `)` closed the
    // block early. The fix routes the echo through delayed expansion so the value is only
    // substituted at execute time, after the block has been parsed. (We test parens-only because
    // `&` and `|` get eaten by cmd's command-line parsing before the script even sees them, no
    // matter how Process quotes the arg.)
    val script = lease.copyScriptTo("pm-hostile")
    val result = lease.runScript(script)("/PM", "foo)bar(qux")
    result was ERROR_INVALID_PARAMETER and
      (result.stderr has "Unsupported /PM") and
      (result.stderr has "foo)bar(qux")
  }

  private def cliPmMissingValueRejected = withDrive { (lease, drive) =>
    // /PM with no following token is invalid user input; exit 87 with a message that explains
    // /PM needs a value and lists the allowlist (with the current default tagged inline).
    val script = lease.copyScriptTo("pm-missing")
    val result = lease.runScript(script)("/PM")
    result was ERROR_INVALID_PARAMETER and
      (result.stderr has "/PM requires a value") and
      (result.stderr has "`always` [default], `if-suffixed`, `ask`, `never`")
  }

  private def suffixAfterDriveAccepted = withDrive { (lease, drive) =>
    // In always mode the suffix-after-drive grammar is accepted: `mount-k-and-remember.bat` is a
    // valid script name. The script strips the suffix before drive extraction (leaving `mount-k`),
    // mounts the drive, and writes the registry entry exactly as a plain `mount-k.bat` would --
    // the suffix is structurally validated but does not alter always-mode behavior.
    val script = lease.copyScriptTo("suf-after", s"mount-${drive.toLower}-and-remember.bat")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.stdout has s"`$drive:` persisted to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("suf-after")))
  }

  // ---------------------------------------------------------------------------
  //  /? rendering (PM=always pins the registry-effect tails on /M and /D)
  // ---------------------------------------------------------------------------

  private def helpUnderAlwaysShowsEffects = withDrive { (lease, drive) =>
    // Under PM=always the :usage block sets _M_NORM=M+R and _D_NORM=D+F, which expand
    // MOUNT_EFFECT/UNMOUNT_EFFECT to the registry tails. Pins both tails so a regression in
    // the always branch of :usage would surface here.
    val script = lease.copyScriptTo("help-always")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `.+` and persist across reboots""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:` and remove the boot-time registry entry""".r) and
      (result.stdout has """/PM\s+<mode>\s+-\s+override PERSIST_MODE - one of: `always` \[default\]""".r) and
      result.stderr.isEmpty
  }

  private def helpImmuneToSlashD = withDrive { (lease, _) =>
    // Per the :usage comment ("Usage text is driven by the FILENAME, not by CLI overrides"),
    // `mount-k.bat /D /?` must produce the same usage as `mount-k.bat /?` -- the runtime ACTION
    // override does not retarget the `<no args>` row. Compare the help-line tails between the
    // two invocations to pin the immunity.
    val script = lease.copyScriptTo("help-immune-d")
    val plain = lease.runScript(script)("/?")
    val withD = lease.runScript(script)("/D", "/?")
    plain was SUCCESS and
      (withD was SUCCESS) and
      (plain.stdout must beEqualTo(withD.stdout)) and
      (withD.stdout has """<no args>\s+-\s+same as /M \(mount\)""".r)
  }

  private def helpImmuneToSlashPm = withDrive { (lease, _) =>
    // /PM at the CLI overrides PERSIST_MODE for the runtime, but the :usage block reads
    // PERSIST_MODE_DEFAULT (snapshotted before the arg loop). Pin that `mount-k.bat /PM never /?`
    // still labels `always` as `[default]` and still shows the always-mode effect tails.
    val script = lease.copyScriptTo("help-immune-pm")
    val result = lease.runScript(script)("/PM", "never", "/?")
    result was SUCCESS and
      (result.stdout has """`always` \[default\]""".r) and
      (result.stdout hasNot """`never` \[default\]""".r) and
      (result.stdout has "and persist across reboots") and
      result.stderr.isEmpty
  }

  // ---------------------------------------------------------------------------
  //  PERSIST_MODE casing variants
  // ---------------------------------------------------------------------------

  private def cliPmAlwaysUpperAccepted = withDrive { (lease, drive) =>
    // The validator uses `if /I` against the canonical lowercase allowlist, then rewrites
    // PERSIST_MODE to the canonical form. Uppercase `/PM ALWAYS` must pass the allowlist and
    // produce a normal mount; the post-normalize PERSIST_MODE is `always`.
    val script = lease.copyScriptTo("pm-upper")
    val result = lease.runScript(script)("/PM", "ALWAYS")
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.persisted has drive)
  }

  private def cliPmIfSuffixedMixedAccepted = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("pm-mixed")
    val result = lease.runScript(script)("/PM", "If-Suffixed")
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"`$drive:` drive mapped to")
  }

  private def cliPmAskMixedAccepted = withDrive { (lease, drive) =>
    // /PM aSk flips runtime to ask-mode; pipe `N` so the prompt resolves and the script exits
    // without blocking on stdin. Pin both the validator's case-insensitive accept and the
    // post-normalize behavior (ask runs the prompt, no registry on N).
    val script = lease.copyScriptTo("pm-ask-mixed")
    val stdinN = Some(new java.io.ByteArrayInputStream("N\n".getBytes))
    val result = lease.runScript(script, stdin = stdinN)("/PM", "aSk")
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.persisted hasNot drive)
  }

  private def cliPmNeverUpperAccepted = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("pm-never-upper")
    val result = lease.runScript(script)("/PM", "NEVER")
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"`$drive:` drive mapped to") and
      // /PM NEVER overrides spec-level always; downgrade fires, no registry write.
      (result.persisted hasNot drive)
  }

  private def hardcodedPmMixedAccepted = withDrive { (lease, drive) =>
    // Symmetric to the /PM CLI casing tests above but for the in-script knob: the hardcoded
    // `set "PERSIST_MODE=..."` line is rewritten to mixed-case `Always` and must normalize
    // through the same `if /I` validator without tripping the Unsupported branch.
    val script = lease.copyScriptTo("pm-hardcoded-mixed")
    lease.patchPersistMode(script, "Always")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.persisted has drive)
  }

  // ---------------------------------------------------------------------------
  //  ACTION normalization on flag override (/M and /D flatten +R/+F today)
  // ---------------------------------------------------------------------------
  // These pin the behavior of /M and /D resetting ACTION to the plain M/D variant, dropping
  // the +R/+F that the filename suffix carried. Under PM=always the downstream normalization
  // re-upgrades plain M/D back to M+R/D+F so the registry round-trip still happens, which is
  // what these tests pin.

  private def slashMOnDPlusFFlattens = withDrive { (lease, drive) =>
    // Filename derives FILE_ACTION=D+F; arg /M sets ACTION=M (plain), then ACTION normalization
    // re-upgrades M -> M+R because PERSIST_MODE=always. So under PM=always the registry IS
    // written even though /M discarded the +F. Pin both: the mount succeeds and registry has
    // the entry.
    val script = lease.copyScriptTo("flat-mfromdf", s"unmount-${drive.toLower}-and-forget.bat")
    val result = lease.runScript(script)("/M")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.stdout has s"`$drive:` persisted to") and
      (result.persisted has drive)
  }

  private def slashDOnMPlusRFlattens = withDrive { (lease, drive) =>
    // Symmetric: FILE_ACTION=M+R, /D sets ACTION=D, normalization re-upgrades D -> D+F under
    // PM=always so the registry entry IS removed. Mount first to seed the entry, then unmount
    // via /D.
    val script = lease.copyScriptTo("flat-dfrommr", s"mount-${drive.toLower}-and-remember.bat")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive unmounted") and
      (result.stdout has s"`$drive:` removed from registry") and
      (result.persisted hasNot drive)
  }

  private def slashPmNeverOnSuffixedDowngrades = withDrive { (lease, drive) =>
    // mount-X-and-remember.bat /PM never: filename derives ACTION=M+R, /PM never re-runs the
    // never-branch normalization which downgrades M+R -> M. Mount succeeds, registry untouched.
    // Pins that /PM truly overrides the suffix's persistence intent (not just the runtime mode
    // label).
    val script = lease.copyScriptTo("pm-never-suffixed", s"mount-${drive.toLower}-and-remember.bat")
    val result = lease.runScript(script)("/PM", "never")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.stdout hasNot "persisted") and
      (result.stdout hasNot "registry") and
      (result.persisted hasNot drive)
  }
