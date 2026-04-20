package com.github.melezov.mountk

import org.specs2.Specification
import org.specs2.execute.{Result, Skipped}
import org.specs2.specification.core.SpecStructure

import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.{Files, Path}
import scala.sys.process.*

/** Validates repository-wide text-file formatting: line endings, terminal newlines,
 *  and ASCII discipline for source/doc files. The file set comes from `git ls-files`
 *  so the exclusion list stays in sync with .gitignore automatically -- no hardcoded
 *  denylist to drift. Each file is classified by its extension or fixed name before
 *  applying the right checks. */
class FormatSpec extends Specification:

  private val repoRoot: Path = scriptPath.getParent

  // (needsCrlf, checkAscii). LICENSE is excluded from ASCII checking because legal boilerplate
  // sometimes carries non-ASCII ((c), typographic quotes). .txt is excluded because the unicode
  // test-data fixtures are pure non-ASCII by design.
  private val rulesByExt: Map[String, (Boolean, Boolean)] = Map(
    "bat"        -> (true,  true),
    "md"         -> (false, true),
    "scala"      -> (false, true),
    "sbt"        -> (false, true),
    "txt"        -> (false, false),
    "properties" -> (false, true),
  )
  private val rulesByName: Map[String, (Boolean, Boolean)] = Map(
    ".gitignore"    -> (false, true),
    ".editorconfig" -> (false, true),
    "LICENSE"       -> (false, false),
  )

  // Binary blobs that git tracks but text-format rules don't apply to. Skipped entirely so
  // they don't show up as `unrecognized` either.
  private val binaryExts: Set[String] = Set("png")

  private def isBinary(p: Path): Boolean =
    binaryExts.contains(p.getFileName.toString.split('.').last.toLowerCase)

  private def rulesFor(p: Path): Option[(Boolean, Boolean)] =
    val name = p.getFileName.toString
    rulesByName.get(name).orElse {
      val dot = name.lastIndexOf('.')
      if dot < 0 then None else rulesByExt.get(name.substring(dot + 1))
    }

  // `--cached --others --exclude-standard` = tracked + untracked-but-not-ignored, i.e. every
  // file a human would expect the checks to cover. Delegating to git means every .gitignore
  // nuance (nesting, negations, globs) is honoured without reimplementing the parser here.
  private val allFiles: List[Path] =
    Process(Seq("git", "ls-files", "--cached", "--others", "--exclude-standard"), repoRoot.toFile)
      .lazyLines.iterator
      .map(line => repoRoot.resolve(line))
      .filter(Files.isRegularFile(_))
      .filterNot(isBinary)
      .toList

  private val (recognized, unrecognized) =
    allFiles.partition(p => rulesFor(p).isDefined)

  private def rel(p: Path): String = repoRoot.relativize(p).toString.replace('\\', '/')

  println(s"FormatSpec: ${recognized.length} recognized, ${unrecognized.length} unrecognized")

  override def is: SpecStructure = s2"""
  repository file format
    every recognized file ends with a newline                   $endsWithNewline
    .bat uses CRLF; all other recognized files use LF only      $correctLineEndings
    non-.txt/non-LICENSE recognized files are ASCII-only        $asciiOnly
    no line has trailing whitespace before its newline          $noTrailingWhitespace
    unrecognized file types (informational -- not a failure)    $reportUnrecognized
  """

  private def endsWithNewline =
    val offenders = recognized.filter { p =>
      val bytes = Files.readAllBytes(p)
      val needsCrlf = rulesFor(p).get._1
      if bytes.isEmpty then true
      else if needsCrlf then
        !(bytes.length >= 2 && bytes(bytes.length - 2) == '\r'.toByte && bytes.last == '\n'.toByte)
      else bytes.last != '\n'.toByte
    }
    offenders.map(rel) must beEmpty

  private def correctLineEndings =
    val offenders = recognized.filter { p =>
      val needsCrlf = rulesFor(p).get._1
      val bytes = Files.readAllBytes(p)
      if needsCrlf then
        // Every \n must be preceded by \r.
        val s = new String(bytes, ISO_8859_1)
        s != s.replace("\r", "").replace("\n", "\r\n")
      else
        // No \r anywhere for LF files.
        bytes.contains('\r': Byte)
    }
    offenders.map(rel) must beEmpty

  private def asciiOnly =
    val offenders = recognized.filter { p =>
      val (_, checkAscii) = rulesFor(p).get
      if !checkAscii then false
      else Files.readAllBytes(p).exists(b => (b & 0xff) > 0x7f)
    }
    offenders.map(rel) must beEmpty

  // Only ASCII horizontal whitespace -- asciiOnly already bars non-ASCII in the files that
  // matter, and the remaining .txt/LICENSE are either pure-ASCII or deliberately non-ASCII
  // fixtures where a Unicode trailing-space check would produce noise rather than signal.
  private val trailingWs = """[ \t\x0B\f]+$""".r

  private def noTrailingWhitespace =
    val offenders = recognized.flatMap { p =>
      val text = new String(Files.readAllBytes(p), ISO_8859_1)
      text.split('\n').iterator.zipWithIndex.flatMap { case (raw, idx) =>
        val line = if raw.endsWith("\r") then raw.dropRight(1) else raw
        if trailingWs.findFirstIn(line).isDefined then Some(s"${rel(p)}:${idx + 1}")
        else None
      }
    }
    offenders must beEmpty

  private def reportUnrecognized: Result =
    if unrecognized.isEmpty then
      org.specs2.execute.Success("no unrecognized files")
    else
      Skipped(
        "found file types not classified by FormatSpec; add them to rulesByExt/rulesByName " +
        "or confirm they should be ignored:\n  " + unrecognized.map(rel).sorted.mkString("\n  ")
      )
