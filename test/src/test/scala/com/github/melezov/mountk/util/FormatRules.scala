package com.github.melezov.mountk
package util

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.nio.file.{Files, Path}
import scala.sys.process.*

/** Single source of truth for repository-wide format rules. Both `FormatSpec` (the validator) and
  * `Reformat` (the in-place fixer) read from here so a new file extension or a tightened rule lands
  * in one place and immediately governs both checks and rewrites. */
object FormatRules:
  private val repoRoot: Path = scriptPath.getParent

  private val rulesByExt: Map[String, (String, Charset)] = Map(
    "bat" -> ("\r\n", US_ASCII),
    "md" -> ("\n", US_ASCII),
    "properties" -> ("\n", US_ASCII),
    "sbt" -> ("\n", US_ASCII),
    "scala" -> ("\n", US_ASCII),
    "txt" -> ("\n", UTF_8),
    "xml" -> ("\n", US_ASCII),
  )

  private val rulesByName: Map[String, (String, Charset)] = Map(
    ".editorconfig" -> ("\n", US_ASCII),
    ".gitignore" -> ("\n", US_ASCII),
    "LICENSE" -> ("\n", UTF_8),
  )

  // Binary blobs that git tracks but text-format rules don't apply to
  private val binaryExts: Set[String] = Set("png")

  private def isBinary(p: Path): Boolean =
    binaryExts.contains(p.getFileName.toString.split('.').last.toLowerCase)

  val rulesFor: (p: Path) => Option[(String, Charset)] = (p: Path) =>
    val name = p.getFileName.toString
    rulesByName.get(name).orElse {
      name.lastIndexOf('.') match
        case -1 => None
        case dot => rulesByExt.get(name.drop(dot + 1))
    }

  // `--cached --others --exclude-standard` = tracked + untracked-but-not-ignored
  def listFilesByRecognition: Seq[(Path, Boolean)] =
    Process(Seq("git", "ls-files", "--cached", "--others", "--exclude-standard"), repoRoot.toFile)
      .lazyLines.iterator
      .map(line => repoRoot.resolve(line))
      .filter(Files.isRegularFile(_))
      .filterNot(isBinary)
      .map(p => p -> rulesFor(p).isDefined)
      .toSeq

  def listRecognized: Seq[Path] =
    listFilesByRecognition.collect { case (p: Path, true) => p }

  def rel(p: Path): String = repoRoot.relativize(p).toString.replace('\\', '/')
