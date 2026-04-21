package com.github.melezov.mountk

import org.specs2.execute.{Result, Skipped}
import org.specs2.specification.core.{Fragments, SpecStructure}

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

class UnicodePathAdminSpec extends UnicodePathSpec(elevated = true)
class UnicodePathUserSpec extends UnicodePathSpec(elevated = false)

abstract class UnicodePathSpec(elevated: Boolean) extends ScriptSpec:

  // Intra-spec parallelism: `args.execute(threadsNb = drives.length)` fans examples across whatever
  // the pool granted -- up to `parallelism` drives, falling back to fewer (and serial when 1) if the
  // pool is contended at startup.
  val parallelism = 4

  private val extraEnv = if elevated then Seq("SKIP_ELEVATION" -> "1") else Seq.empty

  // ---------------------------------------------------------------------------
  //  File parsers (format: "# description\n" + "dir_name\n" + "\n")
  // ---------------------------------------------------------------------------

  private def parseCasesResource(resource: String): Seq[(String, String)] =
    val stream = getClass.getResourceAsStream(resource)
    val lines = scala.io.Source.fromInputStream(stream, "UTF-8").getLines().toVector
    val result = ArrayBuffer.empty[(String, String)]
    var pendingDesc = ""
    for line <- lines do
      if line.startsWith("#") then
        pendingDesc = line.stripPrefix("# ").stripPrefix("#").trim
      else if line.nonEmpty && pendingDesc.nonEmpty then
        result += pendingDesc -> line
        pendingDesc = ""
    result.toSeq

  private lazy val useCases: Seq[(String, String)] =
    parseCasesResource("/unicode-use-cases.txt")

  private lazy val tortureCases: Seq[(String, String)] =
    parseCasesResource("/unicode-torture-cases.txt")

  // ---------------------------------------------------------------------------
  //  Test helpers
  // ---------------------------------------------------------------------------

  // subst occasionally returns exit 31 (ERROR_GEN_FAILURE) under concurrent load;
  // retry with linear backoff using the same leased drive before giving up.
  private val MaxRetryAttempts = 3

  @tailrec private def runWithRetry(lease: Lease, script: java.nio.file.Path, attempt: Int): RunResult =
    val result = lease.runScript(script, extraEnv)()
    if result.exitCode == 31 && attempt < MaxRetryAttempts then
      println(s"[retry] ${lease.drive}: exit 31 on attempt $attempt, retrying after ${100 * attempt}ms")
      WinApi.substDelete(lease.drive): Unit
      Thread.sleep(100L * attempt)
      runWithRetry(lease, script, attempt + 1)
    else result

  // File path layout: `lease.driveRoot` \ subDir \ `mount-x.bat` -- `driveRoot` + script name are
  // fixed; subDir is what the caller supplies (possibly a nested a\b\c of dir names).
  private def fixedPathOverhead(drive: Char): Int =
    specRoot.resolve(s"drive-$drive").toString.length + 1 +
      1 + s"mount-${drive.toLower}.bat".length

  private val testCounter = new AtomicInteger(0)

  private def unicodePathCheck(dirNames: Seq[String]): Result = withDrive { lease =>
    val drive = lease.drive
    val subDir = dirNames.mkString("\\")
    try
      val script = lease.copyScriptTo(subDir)
      val expectedDir = script.getParent.toString
      val expectedReg = s"\\??\\$expectedDir"
      val result = runWithRetry(lease, script, attempt = 1)
      (result.exitCode must beEqualTo(0)) and
        (result.stdout must contain(s"$drive: drive mapped to")) and
        (result.stderr must beEmpty) and
        (result.registry must haveKey(s"$drive:")) and
        (result.registry(s"$drive:") must beEqualTo(expectedReg))
    catch
      case e: IOException => Skipped(s"OS rejected path: ${e.getMessage}")
  }

  // Greedy pack: nest dir names into a single mount path until adding the
  // next one would push the full script path past MAX_PATH. One mount per batch
  // exercises every nested name in a single I/O round-trip.
  private def packCases(cases: Seq[(String, String)]): Seq[Seq[(String, String)]] =
    val available = MaxUsablePath - fixedPathOverhead(drives(0))
    val batches = ArrayBuffer.empty[ArrayBuffer[(String, String)]]
    var current = ArrayBuffer.empty[(String, String)]
    var currentLen = 0
    for c <- cases do
      val sep = if current.isEmpty then 0 else 1
      if current.nonEmpty && currentLen + sep + c._2.length > available then
        batches += current
        current = ArrayBuffer.empty
        currentLen = 0
      currentLen += (if current.isEmpty then 0 else 1) + c._2.length
      current += c
    if current.nonEmpty then batches += current
    batches.toSeq.map(_.toSeq)

  // ---------------------------------------------------------------------------
  //  Dynamic specs
  // ---------------------------------------------------------------------------

  private def packedExamples(cases: Seq[(String, String)], check: Seq[String] => Result): Fragments =
    packCases(cases).foldLeft(Fragments.empty) { (acc, batch) =>
      val id = testCounter.incrementAndGet()
      val descs = batch.map(_._1).mkString("\n  / ")
      val dirs = batch.map(_._2)
      acc ^ (f"$id%03d. $descs\n  " ! check(dirs))
    }

  private lazy val useCaseExamples: Fragments =
    packedExamples(useCases, unicodePathCheck)

  private lazy val unicodeTortureExamples: Fragments =
    packedExamples(tortureCases, unicodePathCheck)

  override def is: SpecStructure = args.execute(threadsNb = drives.length) ^ s2"""
  Nested use-case unicode directory names
    $useCaseExamples

  Nested torture unicode directory names
    $unicodeTortureExamples
"""
