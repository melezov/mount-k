package com.github.melezov.mountk
package test
package persistence

import org.specs2.specification.core.SpecStructure

import java.io.{ByteArrayInputStream, InputStream}
import scala.language.implicitConversions

class MountKPersistAskAdminSpec extends MountKPersistAskSpec(elevated = true)

class MountKPersistAskUserSpec extends MountKPersistAskSpec(elevated = false)

// PERSIST_MODE=ask defers the registry decision to the user at runtime: before each registry
// write or delete the script prompts and acts on the reply. The default answer derives from
// the filename: a PERSIST_SUFFIXES suffix (e.g. mount-k-and-remember.bat) defaults to Y and
// shows [Y/n] non-blocking; a plain name (e.g. mount-k.bat) defaults to N and shows [y/N].
// Tests inject answers via stdin piping (stdinY/stdinN). The Admin/User split mirrors
// MountKPersistAlwaysSpec -- when the user answers Y the script still goes through :run_uac
// for the HKLM write, so both elevation paths are exercised.
abstract class MountKPersistAskSpec(override val elevated: Boolean) extends ScriptSpec:

  val parallelism = 4

  override protected def persistMode: Option[String] = Some("ask")

  override def is: SpecStructure = args.execute(threadsNb = drives.length) ^ s2"""
  PERSIST_MODE=ask
    mount with Y answer persists to registry            $mountAskYesPersists
    mount with N answer skips registry                  $mountAskNoPersists
    unmount with Y answer removes from registry         $unmountAskYesRemoves
    unmount with N answer preserves registry entry      $unmountAskNoPreserves
    idempotent mount does not ask (already persisted)   $idempotentMountNoAsk
    cold unmount does not ask (was not persisted)       $coldUnmountNoAsk
    closed stdin treated as N (no hang)                 $closedStdinTreatedAsNo
    validator accepts ask                               $validatorAcceptsAsk

  /? rendering under PERSIST_MODE=ask
    /? shows ask tail on both /M and /D                 $helpUnderAskShowsAskTails
"""

  private def stdinY = Some(new ByteArrayInputStream("Y\n".getBytes))

  private def stdinN = Some(new ByteArrayInputStream("N\n".getBytes))

  private def mountAskYesPersists = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, stdin = stdinY)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      (result.stdout has s"$drive: persisted to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def mountAskNoPersists = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, stdin = stdinN)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      (result.stdout has s"$drive: persistence skipped") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live(drive) must beSome(contain("base")))
  }

  private def unmountAskYesRemoves = withDrive { (lease, drive) =>
    val script = lease.copyScriptTo("base")
    lease.runScript(script, stdin = stdinY)(): Unit
    val result = lease.runScript(script, stdin = stdinY)("/D")
    result was SUCCESS and
      (result.stdout has s"$drive: drive unmounted") and
      (result.stdout has s"$drive: removed from registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive) and
      (result.live hasNot drive)
  }

  private def unmountAskNoPreserves = withDrive { (lease, drive) =>
    // First mount with Y seeds the registry entry; unmount with N clears the subst
    // but must leave the registry entry intact.
    val script = lease.copyScriptTo("base")
    lease.runScript(script, stdin = stdinY)(): Unit
    val result = lease.runScript(script, stdin = stdinN)("/D")
    result was SUCCESS and
      (result.stdout has s"$drive: drive unmounted") and
      (result.stdout has s"$drive: registry cleanup skipped") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.live hasNot drive)
  }

  private def idempotentMountNoAsk = withDrive { (lease, drive) =>
    // REG_HAVE==REG_WANT early-exit in :mount_reg fires before :ask_persist is reached.
    // No stdin provided: if the prompt were reached the script would block on stdin (or
    // produce wrong output on EOF), surfacing the regression as a timeout or failure.
    val script = lease.copyScriptTo("base")
    lease.runScript(script, stdin = stdinY)(): Unit
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"$drive: is already persisted in registry") and
      result.stderr.isEmpty and
      (result.persisted has drive)
  }

  private def coldUnmountNoAsk = withDrive { (lease, drive) =>
    // REG_HAVE undefined early-exit in :unmount_reg fires before :ask_persist is reached.
    // No stdin provided: if the prompt were reached the script would block or produce wrong
    // output, proving the guard fired without risking a false pass.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script)("/D")
    result was SUCCESS and
      (result.stdout has s"$drive: is not mounted via subst") and
      (result.stdout has s"$drive: was not persisted in registry") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive)
  }

  private def closedStdinTreatedAsNo = withDrive { (lease, drive) =>
    // Null stdin: set /P receives EOF immediately, _ANS stays empty, default-N fires -> skipped.
    val script = lease.copyScriptTo("base")
    val result = lease.runScript(script, stdin = Some(InputStream.nullInputStream()))()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      (result.stdout has s"$drive: persistence skipped") and
      result.stderr.isEmpty and
      (result.persisted hasNot drive)
  }

  private def validatorAcceptsAsk = withDrive { (lease, drive) =>
    // Direct probe of the validator: `ask` must no longer trip the "Unsupported" rejection.
    // Implied by every other test in this spec but pinned explicitly so a future tightening
    // that dropped `ask` from the allowlist would surface here with 87 instead of a cascade.
    val script = lease.copyScriptTo("validator")
    val result = lease.runScript(script, stdin = stdinN)()
    result was SUCCESS and
      (result.stderr hasNot "Unsupported") and
      (result.stdout has s"$drive: drive mapped to")
  }

  private def helpUnderAskShowsAskTails = withDrive { (lease, drive) =>
    // Under PM=ask the :usage block sets MOUNT_EFFECT and UNMOUNT_EFFECT to the "ask before
    // ..." tails on BOTH /M and /D. Pin both lines (the second branch was previously broken by
    // a `&^` line-continuation bug that emitted a stray `' '` to stderr and left UNMOUNT_EFFECT
    // unset; the regression test is `result.stderr.isEmpty` here).
    val script = lease.copyScriptTo("help-ask")
    val result = lease.runScript(script)("/?")
    result was SUCCESS and
      (result.stdout has s"""/M\\s+-\\s+mount `$drive:` to `.+` - ask before persisting into registry""".r) and
      (result.stdout has s"""/D\\s+-\\s+unmount `$drive:` - ask before removing from registry""".r) and
      (result.stdout has """`ask` \[default\]""".r) and
      result.stderr.isEmpty
  }
