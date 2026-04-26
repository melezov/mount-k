package com.github.melezov.mountk
package util

import scribe.*

import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.Files

/** In-place fixer for the format rules `FormatSpec` validates. Today it normalizes line endings
  * (CRLF for `.bat`, LF for everything else) and ensures every recognized file ends with exactly
  * one trailing newline; trailing whitespace and ASCII-only enforcement are deliberately left to
  * the spec for now since they're judgment calls (a tab in a .md table cell vs. an accidental
  * trailing space). Operates byte-wise via ISO-8859-1 so non-ASCII bytes in UTF-8 files like
  * LICENSE pass through untouched. */
object Reformat:
  def main(args: Array[String]): Unit =
    val files = FormatRules.listRecognized
    info(s"mount-k reformat (${files.length} files)")
    var fixed = 0
    var unchanged = 0
    files.foreach { p =>
      val (lineEnding, _) = FormatRules.rulesFor(p).get
      val original = Files.readAllBytes(p)
      val updated = reformat(original, lineEnding)
      if !java.util.Arrays.equals(original, updated) then
        Files.write(p, updated): Unit
        info(s"fixed ${FormatRules.rel(p)}")
        fixed += 1
      else
        unchanged += 1
    }
    info(s"reformatted $fixed file(s); $unchanged already conformant")

  /** Normalize all line endings to `lineEnding` and guarantee exactly one trailing `lineEnding`.
    * Empty inputs become a single-line-ending file so they pass `FormatSpec.endsWithNewline`. */
  private def reformat(bytes: Array[Byte], lineEnding: String): Array[Byte] =
    val s = new String(bytes, ISO_8859_1)
    val lf = s.replace("\r\n", "\n").replace("\r", "\n")
    val trimmed = lf.reverse.dropWhile(_ == '\n').reverse + "\n"
    val out = if lineEnding == "\r\n" then trimmed.replace("\n", "\r\n") else trimmed
    out.getBytes(ISO_8859_1)
