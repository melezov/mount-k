package com.github.melezov.mountk

import org.specs2.Specification
import org.specs2.specification.BeforeAfterSpec
import org.specs2.specification.core.Fragments

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.LinkedBlockingQueue
import scala.language.implicitConversions
import scala.sys.process.*
import scala.util.Try

case class RunResult(exitCode: Int, stdout: String, stderr: String, registry: Map[String, String])

val version: String = BuildInfo.version

val projectRoot: String =
  new File(this.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    .getCanonicalPath
    .replace('\\', '/')
    .replaceFirst("(.*/)target/.*", "$1")

/** Path to mount-k.bat - one level up from test project root */
val scriptPath: Path =
  Path.of(projectRoot).getParent.resolve("mount-k.bat")

val scriptContent: String = Files.readString(scriptPath)

/** HKCU subpath under which every spec writes its own private registry subtree. Individual spec roots are
 *  `s"$TestRegSubPath\\<SpecName>"`, so parallel specs never share a key and there's no contention on the
 *  `regDeleteKey` / `regSetValue` calls the tests make. */
val TestRegSubPath: String = "Software\\mount-k-test"

val testRoot: Path = Path.of(projectRoot, "target", "mount-k-test")

/** MAX_PATH = 260 (includes NUL terminator). Maximum usable path = 259. */
val MaxUsablePath = 259

/** NTFS maximum for a single path component (directory or file name). */
val MaxNtfsComponent = 255

def deleteRecursive(path: Path): Unit =
  // Wrap every file-system op in the \\?\ extended-length prefix so deletions work at paths
  // deeper than MAX_PATH (260 chars). Without it, Files.deleteIfExists silently returns false on
  // long leaves and the post-recursion dir delete then fails because the dir still has children.
  val longPath = if path.toString.startsWith("\\\\?\\") then path
                 else Path.of("\\\\?\\" + path.toAbsolutePath.toString)
  if Files.isDirectory(longPath) then
    scala.util.Using(Files.list(longPath))(_.forEach(deleteRecursive)).get
  Files.deleteIfExists(longPath): Unit

// -----------------------------------------------------------------------------
//  Drive pool
// -----------------------------------------------------------------------------

/** Candidate unused drive letters passed in via `-Dmountk.test.unusedDrivesRange` from `unusedDrivesForTest` in
 *  `build.sbt`. These are candidates only; the pool probes each one and admits only those currently free. */
private val unusedDrivesRange: Seq[Char] =
  sys.props.get("mountk.test.unusedDrivesRange") match
    case Some(s) if s.nonEmpty && s.toUpperCase.distinct.length == s.length => s.toUpperCase.toSeq
    case other => sys.error(
      s"mountk.test.unusedDrivesRange must be a non-empty string of distinct drive letters " +
      s"(got '${other.getOrElse("<unset>")}')")

/** Candidate real-hard-drive letters passed in via `-Dmountk.test.realDrivesRange` from
 *  `existingRootAccessDriveForTest` in `build.sbt`. The first one that's writable becomes `realDrive`. */
private val realDrivesRange: Seq[Char] =
  sys.props.get("mountk.test.realDrivesRange") match
    case Some(s) if s.toUpperCase.distinct.length == s.length => s.toUpperCase.toSeq
    case other => sys.error(
      s"mountk.test.realDrivesRange must be a string of distinct drive letters " +
      s"(got '${other.getOrElse("<unset>")}')")

/** Letters from `unusedDrivesRange` that actually pass a live `subst` probe right now. Computed once at JVM
 *  startup against `testRoot` (which we create on demand for the probe). `vol X:` and `GetDriveType` both lie
 *  about some letters Windows reserves for removable media, so the only reliable check is to do the exact
 *  `DefineDosDevice` call the tests will later make. */
val freeDrives: Seq[Char] =
  Files.createDirectories(testRoot): Unit
  unusedDrivesRange.filter(c => WinApi.canSubst(c, testRoot.toAbsolutePath.toString))

/** Real-hard-drive letter chosen from `realDrivesRange` for tests that need a physical disk root
 *  (e.g. writing `D:\mount-d.bat` to test mount-from-drive-root behavior), or None if no candidate
 *  in the range is a writable fixed volume. Tests that need this skip when it's None. */
val realDrive: Option[Char] =
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
 *  from this pool at `beforeAll`, block until at least one drive is free, and release back at `afterAll`.
 *  Cross-spec parallelism falls out naturally: two specs starting concurrently each take what they need, or
 *  the second one waits if the pool is temporarily empty. */
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

// -----------------------------------------------------------------------------
//  ScriptSpec trait
// -----------------------------------------------------------------------------

/** Base trait for every spec that exercises `mount-k.bat` itself (i.e. not `FormatSpec`). Declares the drive
 *  demand the pool honors; owns per-drive registry subkeys, scratch directories, and `runScript` plumbing
 *  (via `Lease`) so parallel examples never collide on state. Concrete specs MUST set `parallelism` -- the
 *  upper bound on drives requested from the pool. Abstract so a spec that forgets won't compile. */
trait ScriptSpec extends Specification with BeforeAfterSpec:
  def parallelism: Int

  lazy val drives: Seq[Char] = DrivePool.reserve(parallelism)

  /** Simple-class-name (e.g. `MountKUserSpec`). Used to scope registry subkeys and scratch directories so
   *  parallel specs are disjoint. `stripSuffix("$")` handles the object-companion case; not expected here
   *  but cheap. */
  lazy val specName: String = getClass.getSimpleName.stripSuffix("$")

  /** Per-spec registry root. Each `Lease` nests its own `drive-X` subkey under this so parallel examples
   *  within a spec write to disjoint subtrees; assertions like `regParserIgnoresSiblings` can seed fixture
   *  values without another example's cleanup wiping them mid-run. */
  lazy val specRegSubPath: String = s"$TestRegSubPath\\$specName"

  /** Spec-local scratch directory. Script copies, subst targets, and everything else goes under here;
   *  each lease further nests under `drive-X` so per-example filesystem state is disjoint too. */
  lazy val specRoot: Path = testRoot.resolve(specName)

  /** Intra-spec pool of the drives the spec reserved from `DrivePool`. `withDrive`/`withDrives` lease from
   *  here; if all drives are in use, a call blocks until one is returned -- which is the "serial within
   *  spec" fallback when `minDrives=1`. */
  private lazy val driveQueue: LinkedBlockingQueue[Char] =
    val q = new LinkedBlockingQueue[Char]()
    drives.foreach(q.put)
    q

  /** A leased drive slot with its own registry subkey (`<specRegSubPath>\drive-X`) and scratch directory
   *  (`<specRoot>\drive-X`). All script-invocation, patching, and registry helpers live here so nothing in
   *  a test needs to thread drive-specific state around by hand. The slot is valid only inside the lambda
   *  passed to `withDrive` / `withDrives`; on exit the drive is subst-cleared, its registry subkey is
   *  deleted, and it's returned to `driveQueue`. */
  final class Lease(val drive: Char):
    val regSubPath: String = s"$specRegSubPath\\drive-$drive"
    val regKey: String = s"HKCU\\$regSubPath"
    val driveRoot: Path = specRoot.resolve(s"drive-$drive")

    /** Patch the script source so it writes to THIS lease's private registry subkey and skips the UAC
     *  relaunch (the test JVM already runs with the intended privilege; `SKIP_ELEVATION` further gates the
     *  ACL-requiring HKLM path). */
    def patchForTest(content: String): String = content
      .replace("HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\DOS Devices", regKey)
      .replace("-Verb RunAs ", "")

    def runScript(script: Path = scriptPath, extraEnv: Seq[(String, String)] = Seq.empty)(args: String*): RunResult =
      val out = new StringBuilder
      val err = new StringBuilder
      val logger = ProcessLogger(
        line => out ++= line ++= "\r\n": Unit,
        line => err ++= line ++= "\r\n": Unit,
      )
      val cmd = script.toString +: args
      val exitCode = Process(cmd, None, extraEnv*).!(logger)
      RunResult(exitCode, out.toString, err.toString, WinApi.regQueryValues(regSubPath))

    def copyScriptTo(subDir: String, name: String = s"mount-${drive.toLower}.bat"): Path =
      val dir = driveRoot.resolve(subDir)
      Files.createDirectories(dir)
      val dest = dir.resolve(name)
      Files.writeString(dest, patchForTest(scriptContent))
      dest

    def regDeleteValue(name: String): Unit = WinApi.regDeleteValue(regSubPath, name)
    def regSetValue(name: String, value: String): Unit = WinApi.regSetValue(regSubPath, name, value)
    def regDeleteKey(): Unit = WinApi.regDeleteKey(regSubPath)
    def regQueryValues: Map[String, String] = WinApi.regQueryValues(regSubPath)

  /** Lease ONE drive for the duration of `f`. Blocks if all drives are currently leased. The lease's
   *  drive is subst-cleared on acquire and release, its registry subkey is wiped on release, and its
   *  scratch directory is wiped on release too -- so each example starts from a clean slate. */
  def withDrive[A](f: Lease => A): A =
    val d = driveQueue.take()
    val lease = new Lease(d)
    try
      WinApi.substDelete(d)
      f(lease)
    finally
      WinApi.substDelete(d)
      WinApi.regDeleteKey(lease.regSubPath)
      if Files.exists(lease.driveRoot) then deleteRecursive(lease.driveRoot)
      driveQueue.put(d)

  /** Lease `n` drives for the duration of `f`. The `n` must be within the spec's reserved `drives.length`;
   *  otherwise requesting more than the pool granted would deadlock. Same per-lease cleanup semantics as
   *  `withDrive`. */
  def withDrives[A](n: Int)(f: Seq[Lease] => A): A =
    require(n >= 1 && n <= drives.length,
      s"withDrives($n): spec reserved ${drives.length} drives (parallelism=$parallelism)")
    val leased = (1 to n).map(_ => driveQueue.take())
    val leases = leased.map(new Lease(_))
    try
      leased.foreach(WinApi.substDelete)
      f(leases)
    finally
      leased.foreach(WinApi.substDelete)
      leases.foreach(l =>
        WinApi.regDeleteKey(l.regSubPath)
        if Files.exists(l.driveRoot) then deleteRecursive(l.driveRoot))
      leased.foreach(driveQueue.put)

  def beforeSpec: Fragments = step {
    println(s"[$specName] drives=${drives.mkString("[", ":, ", ":]")} (reserved ${drives.length}/$parallelism)")
    drives.foreach(WinApi.substDelete)
    // Wipe the whole per-spec subtree so any leftover `drive-X` subkeys from previous runs are gone.
    drives.foreach(d => WinApi.regDeleteKey(s"$specRegSubPath\\drive-$d"))
    WinApi.regDeleteKey(specRegSubPath)
    if Files.exists(specRoot) then deleteRecursive(specRoot)
    Files.createDirectories(specRoot): Unit
  }

  def afterSpec: Fragments = step {
    try
      drives.foreach(WinApi.substDelete)
      drives.foreach(d => WinApi.regDeleteKey(s"$specRegSubPath\\drive-$d"))
      WinApi.regDeleteKey(specRegSubPath)
      if Files.exists(specRoot) then deleteRecursive(specRoot)
    finally DrivePool.release(drives)
  }
