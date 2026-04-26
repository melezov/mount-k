package com.github.melezov.mountk
package test
package persistence

import org.specs2.specification.core.SpecStructure

import scala.language.implicitConversions

// PERSIST_MODE=never deliberately bypasses every registry interaction: no read, no write, no
// delete, no UAC prompt, no admin auto-detection. There is no Admin/User split here -- the only
// code path that consults `SKIP_ELEVATION` is `:run_uac`, which is unreachable under `never`, so
// elevated and non-elevated runs would exercise identical bytes. Tests focus on the deltas vs
// the always-mode behavior covered exhaustively in MountKPersistAlwaysSpec.
class MountKPersistNeverSpec extends ScriptSpec:

  val parallelism = 4

  override protected def persistMode: Option[String] = Some("never")

  override def is: SpecStructure = args.execute(threadsNb = drives.length) ^ s2"""
  PERSIST_MODE=never
    mount succeeds without writing registry          $mountWithoutRegistryWrite
    idempotent mount has no registry echo            $mountIdempotentNoRegistryEcho
    unmount removes subst without touching registry  $unmountWithoutRegistryDelete
    unmount-when-absent has no registry echo         $unmountAbsentNoRegistryEcho
    mount preserves a pre-existing registry entry    $mountPreservesPreexistingReg
    unmount preserves a pre-existing registry entry  $unmountPreservesPreexistingReg
    validator accepts never                          $validatorAcceptsNever
    suffix-after-drive name downgraded to plain mount $suffixAfterDriveDowngradedToPlainMount

  /? rendering under PERSIST_MODE=never
    /? shows do-not-touch tail on both /M and /D     $helpUnderNeverShowsNoTouchTails
"""

  private def mountWithoutRegistryWrite = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.stdout hasNot "persisted") and
      (result.stdout hasNot "registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def mountIdempotentNoRegistryEcho = withDrive { (lease, drive) =>
    // Second mount short-circuits the subst path with `is already mounted`. Under `always` it then
    // falls through to :mount_reg and either echoes `is already persisted` or refreshes the entry.
    // Under `never`, :mount_reg's first instruction is `if "%PERSIST_MODE%"=="never" exit /B`, so
    // neither echo nor write happens. Pin both the positive subst echo AND the absence of any
    // registry-related stdout so a regression that re-enabled :mount_reg under never would fail.
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` is already mounted to") and
      (result.stdout hasNot "persisted") and
      (result.stdout hasNot "registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive)
  }

  private def unmountWithoutRegistryDelete = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive unmounted") and
      (result.stdout hasNot "removed from registry") and
      (result.stdout hasNot "was not persisted") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def unmountAbsentNoRegistryEcho = withDrive { (lease, drive) =>
    // Cold unmount (drive was never mounted): under `always` :unmount_reg echoes `was not persisted
    // in registry`. Under `never` the `:unmount_reg` guard exits before reaching that echo, so the
    // only line is the subst-side `is not mounted via subst`. Asserts the registry-side line is
    // absent in addition to the subst-side line being present.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` is not mounted via subst") and
      (result.stdout hasNot "was not persisted") and
      (result.stdout hasNot "removed from registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def mountPreservesPreexistingReg = withDrive { (lease, drive) =>
    // "Don't touch the registry" applied literally: if a stale entry exists from a previous
    // `always` run (or any other tool), `never`-mode mount must not refresh, overwrite, or even
    // read-and-rewrite it. Pre-seed an arbitrary value at the drive's key, mount under `never`,
    // and assert the seed survives byte-for-byte. The `REG_HAVE` read is gated on ACTION+/+R/+F or
    // PM=ask, so under `never` it stays empty regardless of what's actually in the registry.
    val script = lease.copyScriptTo("base")
    lease.seedReg("preexisting-stale-value")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.stdout hasNot "persisted") and
      result.stderr.isEmpty and
      (result.persisted(drive) must beSome("preexisting-stale-value"))
  }

  private def unmountPreservesPreexistingReg = withDrive { (lease, drive) =>
    // Symmetric to mountPreservesPreexistingReg: under `always`, /D would delete the entry.
    // Under `never` the entry must survive intact. Catches a regression that wired the unmount
    // path through :unmount_reg even with PERSIST_MODE=never.
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    lease.seedReg("preexisting-stale-value")
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive unmounted") and
      (result.stdout hasNot "removed from registry") and
      result.stderr.isEmpty and
      (result.persisted(drive) must beSome("preexisting-stale-value"))
  }

  private def validatorAcceptsNever = withDrive { (lease, drive) =>
    // Direct probe of the validator's allowlist growth: `never` must NOT trigger the
    // `Unsupported PERSIST_MODE: never` rejection that any unrecognized value would. Implied
    // by every other test in this spec (they all run under `never`), but pinned explicitly so the
    // intent is visible -- a future tightening of the validator that dropped `never` would surface
    // here with a 87 exit instead of being inferred from a cascade of unrelated assertion failures.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"`$drive:` drive mapped to")
  }

  private def suffixAfterDriveDowngradedToPlainMount = withDrive { (lease, drive) =>
    // The strict A..Z parser accepts `mount-X-and-remember.bat` as ACTION=M+R based on filename
    // alone, BEFORE PERSIST_MODE is consulted. In never mode the ACTION normalization step then
    // downgrades M+R -> M, so the mount succeeds but the registry stays untouched. Pins that
    // never-mode does NOT reject the suffixed filename: it just neutralizes the persistence
    // intent the suffix encoded.
    val script = lease.copyScriptTo("suf-after", s"mount-${drive.toLower}-and-remember.bat")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"`$drive:` drive mapped to") and
      (result.stdout hasNot "persisted") and
      (result.stdout hasNot "registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live(drive) must beSome(contain("suf-after")))
  }

  private def helpUnderNeverShowsNoTouchTails = withDrive { (lease, drive) =>
    // Under PM=never the :usage block sets MOUNT_EFFECT and UNMOUNT_EFFECT to ", do not touch
    // registry" on BOTH /M and /D (mode applies to both directions). Pin both lines.
    val script = lease.copyScriptTo("help-never")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `.+`, do not touch registry""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:`, do not touch registry""".r) and
      (result.stdout has """`never` \[default\]""".r) and
      result.stderr.isEmpty
  }
