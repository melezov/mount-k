package com.github.melezov.mountk
package test

import org.specs2.specification.core.SpecStructure
import scribe.*

import scala.language.implicitConversions

class LongPathAdminSpec extends LongPathSpec(elevated = true)

class LongPathUserSpec extends LongPathSpec(elevated = false)

abstract class LongPathSpec(override val elevated: Boolean) extends ScriptSpec:

  val parallelism = 2

  // Pin PERSIST_MODE=always so registry-touching long-path assertions stay valid (script default
  // `if-suffixed` is a no-op for plain `mount-x.bat` filenames used here).
  override protected def persistMode: Option[String] = Some("always")

  // ---------------------------------------------------------------------------
  //  Path arithmetic
  // ---------------------------------------------------------------------------

  /** Script filename for a given lease, e.g. `mount-k.bat` (11 chars). */
  private def scriptName(drive: Char): String = s"mount-${drive.toLower}.bat"

  /** File path = `lease.driveRoot` + `\` + subDir + `\` + scriptName.
    * So filePathLen = baseOverhead + subDir.length. */
  private def baseOverhead(drive: Char): Int =
    specRoot.resolve(s"drive-$drive").toString.length + 1 + 1 + scriptName(drive).length

  /** SubDir length needed to produce a file path of `filePathLen` chars. */
  private def subDirLenFor(drive: Char, filePathLen: Int): Int =
    filePathLen - baseOverhead(drive)

  // ---------------------------------------------------------------------------
  //  SubDir builders
  // ---------------------------------------------------------------------------

  /** Single directory name of exact `len` characters. */
  private def singleDir(len: Int, c: Char = 'p'): String =
    require(len >= 1 && len <= MaxNtfsComponent,
      s"single component must be 1..$MaxNtfsComponent, got $len")
    c.toString * len

  /** Deeply nested subDir of exactly `targetLen` characters, maximising the
    * number of directory levels by using 1-char segment names.
    *
    * N segments of 1 char + (N-1) backslash separators = 2N - 1 chars.
    * If targetLen is even the last segment gets one extra char.
    * Returns (subDir, depth). */
  private def deepNest(targetLen: Int): (String, Int) =
    require(targetLen >= 1, s"targetLen must be >= 1, got $targetLen")
    val n = (targetLen + 1) / 2
    val segments = Array.fill(n)("d")
    val baseLen = 2 * n - 1
    val extra = targetLen - baseLen
    if extra > 0 then segments(n - 1) = "d" * (1 + extra)
    (segments.mkString("\\"), n)

  // ---------------------------------------------------------------------------
  //  Common assertion helper
  // ---------------------------------------------------------------------------

  /** Mounts from `subDir` under `lease`, verifies:
    *   - exit code 0
    *   - echo output contains the mapping
    *   - registry value matches the full, un-truncated NT path
    *   - `subst` output lists the mapping */
  private def longPathCheck(lease: Lease, subDir: String) =
    val drive = lease.drive
    val script = lease.copyScriptTo(subDir)
    val expectedDir = script.getParent.toString
    val expectedReg = s"\\??\\$expectedDir"
    val result = lease.runScript(script)()
    result was SUCCESS and
      (result.stdout has s"$drive: drive mapped to") and
      result.stderr.isEmpty and
      (result.persisted has drive) and
      (result.persisted(drive) must beSome(expectedReg)) and
      (result.live(drive) must beSome(contain(expectedDir)))

  // ---------------------------------------------------------------------------
  //  Spec body
  // ---------------------------------------------------------------------------

  override def is: SpecStructure = args.execute(threadsNb = drives.length) ^ s2"""
  long directory names
    handle 200-char directory name                       $longDirName
    handle 30-level deep nesting                         $deepNesting

  MAX_PATH boundary (file path = 259 chars)
    mount with single long dir name                      $atMaxPathSingleDir
    mount with maximum nesting depth                     $atMaxPathDeepNest
    unmount at MAX_PATH boundary                         $unmountAtMaxPath
  """

  // ---- long directory names ------------------------------------------------

  private def longDirName = withDrive { (lease, drive) =>
    val available = subDirLenFor(drive, MaxUsablePath)
    val len = Math.min(200, available)
    if len < 50 then
      skipped(s"specRoot too long (overhead=${baseOverhead(drive)}), only $len chars available for subDir")
    else
      longPathCheck(lease, singleDir(len))
  }

  private def deepNesting = withDrive { (lease, drive) =>
    val subDir = (1 to 30).map(i => f"dn$i%02d").mkString("\\")
    val fileLen = baseOverhead(drive) + subDir.length
    if fileLen > MaxUsablePath then
      skipped(s"30-level nesting needs $fileLen chars, exceeds MAX_PATH ($MaxUsablePath)")
    else
      longPathCheck(lease, subDir)
  }

  // ---- MAX_PATH boundary ---------------------------------------------------

  private def atMaxPathSingleDir = withDrive { (lease, drive) =>
    val available = subDirLenFor(drive, MaxUsablePath)
    if available < 1 then
      skipped(s"specRoot too long (overhead=${baseOverhead(drive)})")
    else if available > MaxNtfsComponent then
      skipped(s"subDir of $available chars exceeds single-component NTFS limit ($MaxNtfsComponent)")
    else
      debug(s"atMaxPathSingleDir: dir name $available chars, file path ${baseOverhead(drive) + available}")
      longPathCheck(lease, singleDir(available))
  }

  private def atMaxPathDeepNest = withDrive { (lease, drive) =>
    val available = subDirLenFor(drive, MaxUsablePath)
    if available < 3 then
      skipped(s"specRoot too long (overhead=${baseOverhead(drive)})")
    else
      val (subDir, depth) = deepNest(available)
      debug(s"atMaxPathDeepNest: $depth levels, subDir ${subDir.length} chars, " +
        s"file path ${baseOverhead(drive) + subDir.length}")
      longPathCheck(lease, subDir)
  }

  private def unmountAtMaxPath = withDrive { (lease, drive) =>
    val available = subDirLenFor(drive, MaxUsablePath)
    if available < 1 then
      skipped(s"specRoot too long (overhead=${baseOverhead(drive)})")
    else
      val subDir = if available <= MaxNtfsComponent then singleDir(available, 'u')
      else deepNest(available)._1
      val script = lease.copyScriptTo(subDir)
      lease.runScript(script)(): Unit
      val result = lease.runScript(script)("/D")
      result was SUCCESS and
        (result.stdout has s"$drive: drive unmounted") and
        result.stderr.isEmpty and
        (result.persisted hasNot drive) and
        (result.live hasNot drive)
  }
