package com.github.melezov.mountk

import java.nio.file.{Files, Path}
import scala.util.Try

/** Candidate unused drive letters passed in via `-Dmountk.test.unusedDrivesRange` from `unusedDrivesForTest` in
  * `build.sbt`. These are candidates only; the pool probes each one and admits only those currently free. */
private lazy val unusedDrivesRange: Seq[Char] =
  sys.props.get("mountk.test.unusedDrivesRange") match
    case Some(s) if s.nonEmpty && s.toUpperCase.distinct.length == s.length => s.toUpperCase.toSeq
    case other => sys.error(
      "mountk.test.unusedDrivesRange must be a non-empty string of distinct drive letters " +
        s"(got '${other.getOrElse("<unset>")}')")

/** Candidate real-hard-drive letters passed in via `-Dmountk.test.realDrivesRange` from
  * `existingRootAccessDriveForTest` in `build.sbt`. The first one that's writable becomes `realDrive`. */
private lazy val realDrivesRange: Seq[Char] =
  sys.props.get("mountk.test.realDrivesRange") match
    case Some(s) if s.toUpperCase.distinct.length == s.length => s.toUpperCase.toSeq
    case other => sys.error(
      "mountk.test.realDrivesRange must be a string of distinct drive letters " +
        s"(got '${other.getOrElse("<unset>")}')")

/** Letters from `unusedDrivesRange` that actually pass a live `subst` probe right now. Computed once at JVM
  * startup against `testRoot` (which we create on demand for the probe). `vol X:` and `GetDriveType` both lie
  * about some letters Windows reserves for removable media, so the only reliable check is to do the exact
  * `DefineDosDevice` call the tests will later make. */
lazy val freeDrives: Seq[Char] =
  Files.createDirectories(testRoot)
  unusedDrivesRange.filter(c => WinApi.canSubst(c, testRoot.toAbsolutePath.toString))

/** Real-hard-drive letter chosen from `realDrivesRange` for tests that need a physical disk root
  * (e.g. writing `D:\mount-d.bat` to test mount-from-drive-root behavior), or None if no candidate
  * in the range is a writable fixed volume. Tests that need this skip when it's None. */
lazy val realDrive: Option[Char] =
  realDrivesRange.find { c =>
    if WinApi.isSubst(c) then false
    else if !WinApi.isFixedDrive(c) then false
    else
      val probe = Path.of(s"$c:\\.mount-k-test-write-probe")
      Try {
        Files.writeString(probe, "probe")
        Files.deleteIfExists(probe)
        true
      }.getOrElse(false)
  }

/** Synchronized pool of currently-available drive letters. Specs reserve up to their declared `parallelism`
  * from this pool at `beforeAll`, block until at least one drive is free, and release back at `afterAll`.
  * Cross-spec parallelism falls out naturally: two specs starting concurrently each take what they need, or
  * the second one waits if the pool is temporarily empty. */
object DrivePool:
  private val lock = new Object
  private var available: Set[Char] = freeDrives.toSet

  def reserve(parallelism: Int): Seq[Char] = lock.synchronized {
    require(parallelism >= 1, s"parallelism must be >= 1 (got $parallelism)")
    while available.isEmpty do lock.wait()
    val take = math.min(parallelism, available.size)
    val picked = available.toSeq.sorted.take(take)
    available --= picked
    picked
  }

  def release(drives: Seq[Char]): Unit = lock.synchronized {
    available ++= drives
    lock.notifyAll()
  }
