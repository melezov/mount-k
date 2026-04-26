package com.github.melezov.mountk
package test

import org.specs2.Specification
import org.specs2.specification.core.SpecStructure

import java.nio.file.{Files, Path}

/** Cross-doc consistency checks: everything user-facing about `mount-k.bat` must match what's
  * written in `README.md`. Starts with version-string match; extend as new drifts between code
  * and docs come up (error-glossary parity, usage-banner quote, flag coverage, etc.) */
class ReadMeSpec extends Specification with TestTimeouts:

  private val readmePath: Path = scriptPath.getParent.resolve("README.md")
  private lazy val readmeContent: String = Files.readString(readmePath)

  private val VersionInReadme = """v(\d+\.\d+\.\d+)""".r

  override def is: SpecStructure = s2"""
  README.md cross-doc consistency
    every vX.Y.Z in README matches BuildInfo.version $readmeVersionsMatch
"""

  private def readmeVersionsMatch =
    val readmeVersions = VersionInReadme.findAllMatchIn(readmeContent).map(_.group(1)).toSet
    readmeVersions must not(beEmpty) and
      (readmeVersions must beEqualTo(Set(BuildInfo.version)))
