package com.github.melezov.mountk
package test
package persistence

import org.specs2.specification.core.SpecStructure

import scala.language.implicitConversions

class MountKPersistIfSuffixedAdminSpec extends MountKPersistIfSuffixedSpec(elevated = true)

class MountKPersistIfSuffixedUserSpec extends MountKPersistIfSuffixedSpec(elevated = false)

// PERSIST_MODE=if-suffixed splits the persist decision out of the action: the FILENAME (not the
// argument) tells :mount_reg / :unmount_reg whether to touch the registry. With the default
// PERSIST_SUFFIXES=-and-remember/-and-forget, a script named `mount-k-and-remember.bat` (suffix
// follows the drive letter) persists on mount, and `unmount-k-and-forget.bat` un-persists on
// unmount; a plain `mount-k.bat` is a no-op on the registry side. This spec pins both halves AND
// the asymmetry: mounting via `mount-k-and-remember.bat` then unmounting THE SAME FILE with /D
// persists on the way in but leaves the registry alone on the way out, because the filename lacks
// the unmount suffix. The Admin/User split mirrors PersistAlways: under `User` the `-Verb RunAs`
// is rewritten out by the harness so PowerShell still runs, exercising the elevation code path.
abstract class MountKPersistIfSuffixedSpec(override val elevated: Boolean) extends ScriptSpec:

  val parallelism = 4

  override protected def persistMode: Option[String] = Some("if-suffixed")

  override def is: SpecStructure = args.execute(threadsNb = drives.length) ^ s2"""
  PERSIST_MODE=if-suffixed
    unsuffixed mount skips registry write             $mountUnsuffixedSkipsRegistry
    suffixed mount persists to registry               $mountSuffixedPersists
    unsuffixed unmount skips registry cleanup         $unmountUnsuffixedSkipsRegistry
    suffixed unmount removes registry entry           $unmountSuffixedRemovesRegistry
    unsuffixed mount preserves pre-existing reg       $mountUnsuffixedPreservesPreexistingReg
    unsuffixed unmount preserves pre-existing reg     $unmountUnsuffixedPreservesPreexistingReg
    asymmetry: mount persists, /D on same name leaves $mountSuffixedThenUnmountSameNameAsymmetry
    suffix after drive persists in if-suffixed mode   $suffixAfterDriveGrantsPersist
    validator accepts if-suffixed                     $validatorAcceptsIfSuffixed
    ambiguous stem errors before suffix resolves      $remountAndRememberAmbiguous

  /? rendering under PERSIST_MODE=if-suffixed
    plain mount-x.bat /? shows no registry effects    $helpPlainNoEffects
    mount-x-and-remember.bat /? lifts /M to +R tail   $helpSuffixedMountLiftsM
    unmount-x-and-forget.bat /? lifts /D to +F tail   $helpSuffixedUnmountLiftsD
"""

  private def mountUnsuffixedSkipsRegistry = withDrive { (lease, drive) =>
    // `mount-k.bat` (no suffix in name) under if-suffixed: subst path runs as usual, but :mount_reg
    // bails on the SCRIPT_NAME-doesn't-contain-MOUNT_SUFFIX guard. No echo from :mount_reg either,
    // because the guard is `exit /B` BEFORE the registry-touching branches.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      (result.stdout hasNot "persisted") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def mountSuffixedPersists = withDrive { (lease, drive) =>
    // `mount-k-and-remember.bat` invoked with no args: the script strips the suffix `-and-remember`
    // before drive extraction, leaving `mount-k`; action resolves to `mount`, and the suffix guard
    // in :mount_reg sees `-and-remember` in SCRIPT_NAME so registry write proceeds. Both halves of
    // the if-suffixed contract verified -- the suffix-after-drive drive extraction AND the
    // suffix-gated registry write.
    val script = lease.copyScriptTo("suffixed", s"mount-${drive.toLower}-and-remember.bat")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      (result.stdout has s"$drive: persisted to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("suffixed")))
  }

  private def unmountUnsuffixedSkipsRegistry = withDrive { (lease, drive) =>
    // `mount-k.bat /D` under if-suffixed: subst removed, but :unmount_reg bails on the SCRIPT_NAME-
    // doesn't-contain-UNMOUNT_SUFFIX guard. Pre-seed a registry value so the test can prove the
    // guard fires (otherwise an unconditional `exit /B` would also pass an `assert reg empty`).
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    lease.seedReg("preexisting-stale-value")
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"$drive: drive unmounted") and
      (result.stdout hasNot "removed from registry") and
      result.stderr.isEmpty and
      (result.persisted(drive) must beSome("preexisting-stale-value")) and
      (result.live hasNot drive)
  }

  private def unmountSuffixedRemovesRegistry = withDrive { (lease, drive) =>
    // `unmount-k-and-forget.bat` (no args): after stripping the suffix `-and-forget`, drive=K and
    // action=unmount; suffix guard in :unmount_reg sees `-and-forget` so reg delete proceeds.
    // Pair-set with mountSuffixedPersists: mount via the mount-suffixed name to seed the entry,
    // then unmount via the unmount-suffixed name (different file, same lease so same backing dir
    // and same registry subkey).
    val mountScript = lease.copyScriptTo("paired", s"mount-${drive.toLower}-and-remember.bat")
    val unmountScript = lease.copyScriptTo("paired", s"unmount-${drive.toLower}-and-forget.bat")
    lease.runScript(mountScript)(): Unit
    val result = lease.runScript(unmountScript)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive unmounted") and
      (result.stdout has s"$drive: removed from registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def mountUnsuffixedPreservesPreexistingReg = withDrive { (lease, drive) =>
    // Stale registry entry from a previous always-mode run: mount via `mount-k.bat` under
    // if-suffixed must leave it untouched (no read, no write, no refresh). The :mount_reg guard
    // exits before the REG_HAVE compare, so even if the value matched REG_WANT exactly the
    // "already persisted" echo would not fire. Asserts both the byte-for-byte survival AND the
    // absence of any reg-related stdout line.
    val script = lease.copyScriptTo("base")
    lease.seedReg("preexisting-stale-value")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      (result.stdout hasNot "persisted") and
      result.stderr.isEmpty and
      (result.persisted(drive) must beSome("preexisting-stale-value"))
  }

  private def unmountUnsuffixedPreservesPreexistingReg = withDrive { (lease, drive) =>
    // Symmetric to mountUnsuffixedPreservesPreexistingReg: under if-suffixed, `mount-k.bat /D`
    // must remove the live subst but leave the registry entry intact -- because the filename
    // doesn't carry the unmount suffix, the registry side is a no-op regardless of what's there.
    val script = lease.copyScriptTo("base")
    lease.runScript(script)(): Unit
    lease.seedReg("preexisting-stale-value")
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"$drive: drive unmounted") and
      (result.stdout hasNot "removed from registry") and
      result.stderr.isEmpty and
      (result.persisted(drive) must beSome("preexisting-stale-value"))
  }

  private def mountSuffixedThenUnmountSameNameAsymmetry = withDrive { (lease, drive) =>
    // The load-bearing if-suffixed invariant: filename, NOT action, decides registry interaction.
    // Mount via `mount-k-and-remember.bat` (no args) -- writes to registry via the mount-suffix
    // hit. Then unmount via the SAME `mount-k-and-remember.bat /D` -- the unmount-suffix
    // (`-and-forget`) is NOT in this filename, so :unmount_reg bails and the registry entry
    // survives. The user is expected to deploy a separate `unmount-k-and-forget.bat` for cleanup;
    // this test pins that asymmetry so a future "DRY-it-up" refactor that collapsed the two
    // suffixes into one filename check would surface here.
    val script = lease.copyScriptTo("asymmetric", s"mount-${drive.toLower}-and-remember.bat")
    val mounted = lease.runScript(script)()
    val result = lease.runScript(script)("/D")
    mounted was SUCCESS and
      (mounted.stdout has s"$drive: persisted to") and
      (mounted.persisted has drive) and
      (result was SUCCESS) and
      (result.stdout has s"$drive: drive unmounted") and
      (result.stdout hasNot "removed from registry") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live hasNot drive)
  }

  private def suffixAfterDriveGrantsPersist = withDrive { (lease, drive) =>
    // New grammar: the persist suffix from PERSIST_SUFFIXES follows the drive letter directly in
    // the filename. `mount-k-and-remember.bat` -> strip `-and-remember` -> `mount-k` -> drive=K,
    // action=mount, suffix matched -> registry write proceeds as if PERSIST_MODE were always.
    val script = lease.copyScriptTo("suf-after", s"mount-${drive.toLower}-and-remember.bat")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      (result.stdout has s"$drive: persisted to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("suf-after")))
  }

  private def remountAndRememberAmbiguous = withDrive { (lease, drive) =>
    // Action resolution precedes suffix resolution: `remount-k-and-remember.bat` carries the
    // mount suffix in its name; the strict A..Z parser only accepts the four canonical templates
    // (mount-X, mount-X-and-remember, unmount-X, unmount-X-and-forget). `remount-X-and-remember`
    // matches none of them, so the script exits 123 (Unexpected filename) before any argument or
    // registry processing. The suffix alone cannot rescue a non-canonical stem.
    val script = lease.copyScriptTo("base", s"remount-${drive.toLower}-and-remember.bat")
    val result = lease.runScript(script)()
    result was ERROR_INVALID_NAME and
      result.stdout.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def validatorAcceptsIfSuffixed = withDrive { (lease, drive) =>
    // Direct probe of the validator's allowlist: `if-suffixed` must NOT trigger Unsupported.
    // Implied by every other test in this spec, but pinned explicitly so a future tightening of
    // the validator that dropped `if-suffixed` would surface here with a clear 87 instead of being
    // inferred from a cascade of unrelated assertion failures.
    val script = lease.copyScriptTo("validator")
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"$drive: drive mapped to")
  }

  // ---------------------------------------------------------------------------
  //  /? rendering under PM=if-suffixed
  // ---------------------------------------------------------------------------
  // The :usage block has special branches for if-suffixed: _M_NORM stays at M unless
  // FILE_ACTION=M+R (and same for _D_NORM with D+F). Pin all three permutations: plain
  // filename (no effect on either row), mount-suffixed (lift /M only), unmount-suffixed
  // (lift /D only). Together these prove the suffix-aware lift is wired correctly.

  private def helpPlainNoEffects = withDrive { (lease, drive) =>
    // The `hasNot` lines on the registry-effect tails are the load-bearing assertion: under
    // PM=if-suffixed with a plain (unsuffixed) filename, neither /M nor /D should pick up any
    // mode-driven tail. The positive `has` lines just confirm both rows exist.
    val script = lease.copyScriptTo("help-plain")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:`""".r) and
      (result.stdout hasNot "persist across reboots") and
      (result.stdout hasNot "remove the boot-time") and
      (result.stdout has """`if-suffixed` \[default\]""".r) and
      result.stderr.isEmpty
  }

  private def helpSuffixedMountLiftsM = withDrive { (lease, drive) =>
    // mount-X-and-remember.bat: FILE_ACTION=M+R, so _M_NORM lifts to M+R and the /M row gains
    // the persist tail. /D stays plain because FILE_ACTION's `+R` doesn't apply to it; `hasNot
    // remove the boot-time` pins that asymmetry.
    val script = lease.copyScriptTo("help-mount-suffixed", s"mount-${drive.toLower}-and-remember.bat")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `.+` and persist across reboots""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:`""".r) and
      (result.stdout hasNot "remove the boot-time") and
      result.stderr.isEmpty
  }

  private def helpSuffixedUnmountLiftsD = withDrive { (lease, drive) =>
    // Symmetric: unmount-X-and-forget.bat lifts /D's tail, leaves /M plain. `hasNot persist
    // across reboots` pins the asymmetry.
    val script = lease.copyScriptTo("help-unmount-suffixed", s"unmount-${drive.toLower}-and-forget.bat")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:` and remove the boot-time registry entry""".r) and
      (result.stdout hasNot "persist across reboots") and
      result.stderr.isEmpty
  }

