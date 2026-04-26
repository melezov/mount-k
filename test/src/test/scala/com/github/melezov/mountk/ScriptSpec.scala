package com.github.melezov.mountk

import org.specs2.Specification
import org.specs2.specification.BeforeAfterSpec
import org.specs2.specification.core.Fragments
import scribe.*

import java.io.InputStream
import java.nio.file.{Files, Path}
import java.util.concurrent.LinkedBlockingQueue
import scala.language.implicitConversions
import scala.sys.process.*
import scala.util.Try

/** Base trait for every spec that exercises `mount-k.bat` itself (i.e. not `FormatSpec`). Declares the drive
  * demand the pool honors; owns per-drive registry subkeys, scratch directories, and `runScript` plumbing
  * (via `Lease`) so parallel examples never collide on state. Concrete specs MUST set `parallelism` -- the
  * upper bound on drives requested from the pool. Abstract so a spec that forgets won't compile. */
trait ScriptSpec extends Specification with BeforeAfterSpec with TestTimeouts with WinApi:
  export ExitCode.*

  def parallelism: Int

  /** Whether the test JVM is running with admin privilege. Concrete specs that have Admin/User
   * variants override via constructor; specs that don't care (e.g. PERSIST_MODE=never, where
   * `:run_uac` is unreachable) inherit the default. */
  protected def elevated: Boolean = false

  /** Env vars passed to every `lease.runScript` invocation in this spec. Today this is just
   * `SKIP_ELEVATION` -- gating the script's HKLM write path so the test JVM doesn't UAC-prompt --
   * but it's the single point of extension for any future spec-wide env. */
  protected lazy val extraEnv: Seq[(String, String)] =
    if elevated then Seq("SKIP_ELEVATION" -> "1") else Seq.empty

  /** Spec-level override of the script's hardcoded PERSIST_MODE. When defined, every script copy
    * via `copyScriptTo` has its active `set "PERSIST_MODE=..."` line rewritten to this value as
    * part of `patchForTest`. None inherits the script's shipped default (`if-suffixed`). Specs
    * that need to flip the mode per test (validator probes for bogus/empty modes) call
    * `lease.patchPersistMode` after `copyScriptTo` to override on top. */
  protected def persistMode: Option[String] = None

  /** Rewrite the active (uncommented) `set "PERSIST_MODE=..."` line in `content` to `newValue`.
    * `^\s*set` anchors to the line that's not preceded by `rem` so the commented sample lines in
    * the script's PERSIST_MODE selector block are left alone. */
  protected def rewritePersistMode(content: String, newValue: String): String =
    """(?m)^(\s*)set "PERSIST_MODE=[^"]*"""".r
      .replaceFirstIn(content, s"""$$1set "PERSIST_MODE=$newValue"""")

  lazy val drives: Seq[Char] = DrivePool.reserve(parallelism)

  /** Simple-class-name (e.g. `MountKPersistAlwaysUserSpec`). Used to scope registry subkeys and scratch directories so
    * parallel specs are disjoint. `stripSuffix("$")` handles the object-companion case; not expected here
    * but cheap. */
  lazy val specName: String = getClass.getSimpleName.stripSuffix("$")

  /** Per-spec registry root. Each `Lease` nests its own `drive-X` subkey under this so parallel examples
    * within a spec write to disjoint subtrees; assertions like `regParserIgnoresSiblings` can seed fixture
    * values without another example's cleanup wiping them mid-run. */
  lazy val specRegSubPath: String = s"$TestRegSubPath\\$specName"

  /** Spec-local scratch directory. Script copies, subst targets, and everything else goes under here;
    * each lease further nests under `drive-X` so per-example filesystem state is disjoint too. */
  lazy val specRoot: Path = testRoot.resolve(specName)

  /** Intra-spec pool of the drives the spec reserved from `DrivePool`. `withDrive`/`withDrives` lease from
    * here; if all drives are in use, a call blocks until one is returned -- which is the "serial within
    * spec" fallback when `minDrives=1`. */
  private lazy val driveQueue: LinkedBlockingQueue[Char] =
    val q = new LinkedBlockingQueue[Char]()
    drives.foreach(q.put)
    q

  /** A leased drive slot with its own registry subkey (`<specRegSubPath>\drive-X`) and scratch directory
    * (`<specRoot>\drive-X`). All script-invocation, patching, and registry helpers live here so nothing in
    * a test needs to thread drive-specific state around by hand. The slot is valid only inside the lambda
    * passed to `withDrive` / `withDrives`; on exit the drive is subst-cleared, its registry subkey is
    * deleted, and it's returned to `driveQueue`. */
  final class Lease(val drive: Char):
    val regSubPath: String = s"$specRegSubPath\\drive-$drive"
    val regKey: String = s"$TestRegRoot\\$specName\\drive-$drive"
    val driveRoot: Path = specRoot.resolve(s"drive-$drive")

    /** Patch the script source so it writes to THIS lease's private registry subkey, skips the UAC
      * relaunch (the test JVM already runs with the intended privilege; `SKIP_ELEVATION` further gates
      * the ACL-requiring HKLM path), and -- if the spec overrode `persistMode` -- pins PERSIST_MODE to
      * the spec's chosen value. The HKLM and `-Verb RunAs` substitutions are required; if either fails
      * to match we'd silently pass through to the real UAC prompt or HKLM path, so we assert they hit. */
    def patchForTest(content: String): String =
      val HklmKey = s"HKLM\\$HklmDosDevicesSubPath"
      val VerbRunAs = """-Verb\s+RunAs\s+""".r
      require(content.contains(HklmKey), "patchForTest: expected HKLM key not found in script")
      require(VerbRunAs.findFirstIn(content).isDefined, "patchForTest: expected `-Verb RunAs` not found in script")
      val base = VerbRunAs.replaceAllIn(content.replace(HklmKey, regKey), "")
      ScriptSpec.this.persistMode.fold(base)(rewritePersistMode(base, _))

    def runScript(script: Path, callEnv: Seq[(String, String)] = Seq.empty,
                  stdin: Option[InputStream] = None)(args: String*): RunResult =
      val out = new StringBuilder
      val err = new StringBuilder
      val logger = ProcessLogger(
        line => out ++= line += '\n': Unit,
        line => err ++= line += '\n': Unit,
      )
      val cmd = script.toString +: args
      val proc = Process(cmd, None, (extraEnv ++ callEnv) *)
      val exitCode = stdin.fold(proc.!(logger))(is => (proc #< is).!(logger))
      // Inline silent projection (drop non-drive keys) instead of `Registry.parse`, because
      // `regParserIgnoresSiblings` seeds keys like `_sentinel` and `Z<drive>:` to verify the BAT
      // parser tolerates them; the strict `parse` is reserved for explicit assertions.
      val regRaw = ScriptSpec.this.regQueryValues(regSubPath)
      val regProjected = regRaw.collect { case (DriveKey(d), v) => d.head -> v }
      RunResult(
        exitCode,
        RunResult.Output(out.toString),
        RunResult.Output(err.toString),
        RunResult.Registry(regProjected),
        RunResult.Registry(queryDosDevices()),
      )

    def copyScriptTo(subDir: String, name: String = s"mount-${drive.toLower}.bat"): Path =
      val dir = driveRoot.resolve(subDir)
      Files.createDirectories(dir)
      val dest = dir.resolve(name)
      Files.writeString(dest, patchForTest(scriptContent))
      dest

    def regDeleteValue(name: String): Unit = ScriptSpec.this.regDeleteValue(regSubPath, name)

    def regSetValue(name: String, value: String): Unit =
      regCreateKey(regSubPath)
      ScriptSpec.this.regSetValue(regSubPath, name, value)

    /** Convenience: seed a registry value keyed by THIS lease's drive letter (`<drive>:`). The
      * common shape `lease.regSetValue(s"$drive:", value)` rolled out across the persistence specs. */
    def seedReg(value: String): Unit = regSetValue(s"$drive:", value)

    def regDeleteKey(): Unit = ScriptSpec.this.regDeleteKey(regSubPath)

    def regQueryValues: Map[String, String] = ScriptSpec.this.regQueryValues(regSubPath)

    /** Rewrite the script's active (uncommented) `set "PERSIST_MODE=..."` line to `newValue`. Used
      * for one-off overrides on top of the spec-level `persistMode` (e.g. validator tests that need
      * a bogus or empty mode). Asserts the substitution actually hit so a renamed/relocated knob
      * can't silently leave the script with the wrong mode. */
    def patchPersistMode(script: Path, newValue: String): Unit =
      val original = Files.readString(script)
      val patched = rewritePersistMode(original, newValue)
      require(patched != original, "patchPersistMode: active `set \"PERSIST_MODE=...\"` line not found in script")
      Files.writeString(script, patched): Unit

  /** Lease ONE drive for the duration of `f`. Blocks if all drives are currently leased. The lease's
    * drive is subst-cleared on acquire and release, its registry subkey is wiped on release, and its
    * scratch directory is wiped on release too -- so each example starts from a clean slate.
    * The drive letter is passed alongside the lease so tests don't need to re-extract it. */
  def withDrive[A](f: (Lease, Char) => A): A =
    val d = driveQueue.take()
    val lease = new Lease(d)
    try
      substDelete(d): Unit
      f(lease, d)
    finally
      // Each cleanup step is wrapped so a transient Windows failure (locked handle, stale subst)
      // can't leak the drive slot -- `driveQueue.put` MUST run or the pool shrinks for the rest of the JVM.
      Try(substDelete(d)): Unit
      Try(regDeleteKey(lease.regSubPath)): Unit
      Try(if Files.exists(lease.driveRoot) then deleteRecursive(lease.driveRoot)): Unit
      driveQueue.put(d)

  /** Lease `n` drives for the duration of `f`. The `n` must be within the spec's reserved `drives.length`;
    * otherwise requesting more than the pool granted would deadlock. Same per-lease cleanup semantics as
    * `withDrive`. */
  def withDrives[A](n: Int)(f: Seq[Lease] => A): A =
    require(n >= 1 && n <= drives.length,
      s"withDrives($n): spec reserved ${drives.length} drives (parallelism=$parallelism)")
    val leased = (1 to n).map(_ => driveQueue.take())
    val leases = leased.map(new Lease(_))
    try
      leased.foreach(substDelete)
      f(leases)
    finally
      // See `withDrive`: each per-lease cleanup is isolated so an exception on one doesn't prevent
      // subsequent cleanups or the final `put`s that return drives to the queue.
      leased.foreach(d => Try(substDelete(d)))
      leases.foreach(l =>
        Try(regDeleteKey(l.regSubPath)): Unit
        Try(if Files.exists(l.driveRoot) then deleteRecursive(l.driveRoot)))
      leased.foreach(driveQueue.put)

  private def cleanSpecState(): Unit =
    drives.foreach(substDelete)
    // Wipe the whole per-spec subtree so any leftover `drive-X` subkeys from previous runs are gone.
    drives.foreach(d => regDeleteKey(s"$specRegSubPath\\drive-$d"))
    regDeleteKey(specRegSubPath)
    if Files.exists(specRoot) then deleteRecursive(specRoot)

  def beforeSpec: Fragments = step {
    TestTimeouts.startGlobalTimeoutWatchdog()
    info(s"[$specName] drives=${drives.mkString("[", ":, ", ":]")} (reserved ${drives.length}/$parallelism)")
    cleanSpecState()
    Files.createDirectories(specRoot): Unit
  }

  def afterSpec: Fragments = step {
    try cleanSpecState()
    finally DrivePool.release(drives)
  }
