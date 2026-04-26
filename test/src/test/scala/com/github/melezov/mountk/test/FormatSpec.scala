package com.github.melezov.mountk
package test

import com.github.melezov.mountk.util.FormatRules
import com.github.melezov.mountk.util.FormatRules.{rel, rulesFor}
import org.specs2.Specification
import org.specs2.execute.{Result, Skipped}
import org.specs2.specification.core.SpecStructure
import scribe.*

import java.nio.charset.StandardCharsets.{ISO_8859_1, US_ASCII}
import java.nio.file.Files

class FormatSpec extends Specification with TestTimeouts:

  lazy val (recognized, unrecognized) =
    val (rec, unr) = FormatRules.listFilesByRecognition.partition(_._2)
    (rec.map(_._1), unr.map(_._1))

  info(s"FormatSpec: ${recognized.length} recognized, ${unrecognized.length} unrecognized")

  override def is: SpecStructure =
    s2"""
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
      val lineEnding = rulesFor(p).get._1
      if bytes.isEmpty then true
      else if lineEnding == "\r\n" then
        !(bytes.length >= 2 && bytes(bytes.length - 2) == '\r'.toByte && bytes.last == '\n'.toByte)
      else bytes.last != '\n'.toByte
    }
    offenders.map(rel) must beEmpty

  private def correctLineEndings =
    val offenders = recognized.filter { p =>
      val lineEnding = rulesFor(p).get._1
      val bytes = Files.readAllBytes(p)
      if lineEnding == "\r\n" then
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
      val (_, charset) = rulesFor(p).get
      if charset != US_ASCII then false
      else Files.readAllBytes(p).exists(b => (b & 0xff) > 0x7f)
    }
    offenders.map(rel) must beEmpty

  // asciiOnly already assures that these are the only whitespaces that could sneak in
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
        "found file types not classified by FormatRules; add them to rulesByExt/rulesByName " +
          "or confirm they should be ignored:\n  " + unrecognized.map(rel).sorted.mkString("\n  ")
      )
