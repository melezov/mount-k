import sbt.*
import sbt.Keys.*

import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport.*

object Build extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    val unusedDrivesForTest = settingKey[Seq[Char]](
      "Candidate drive letters the tests may use as virtual drives. The test JVM probes these at startup " +
      "and enters the actually-free letters into its shared drive pool.")
    val existingRootAccessDriveForTest = settingKey[Seq[Char]](
      "Candidate real hard drives the tests may place a mount-x.bat on. The test JVM picks the first " +
      "writable one at startup; tests needing a physical disk root skip when none of these is writable.")
  }
  import autoImport.*

  val unusedDrivesRangeProp = "mountk.test.unusedDrivesRange"
  val realDrivesRangeProp = "mountk.test.realDrivesRange"
  val globalTimeoutSecProp = "mountk.test.globalTimeoutSec"
  val perExampleTimeoutSecProp = "mountk.test.perExampleTimeoutSec"
  val defaultGlobalTimeoutSec: Int = 5 * 60
  val defaultPerExampleTimeoutSec: Int = 10

  override def projectSettings: Seq[Def.Setting[?]] = BuildInfoPlugin.projectSettings ++ Seq(
    version := readVersionFromBat((ThisBuild / baseDirectory).value / ".." / "mount-k.bat"),
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "com.github.melezov.mountk",
    Test / javaOptions ++= {
      val mount = unusedDrivesForTest.value.map(_.toUpper).distinct
      val real = existingRootAccessDriveForTest.value.map(_.toUpper).distinct
      val overlap = mount.toSet intersect real.toSet
      if (overlap.nonEmpty)
        sys.error(
          s"${unusedDrivesForTest.key.label} and ${existingRootAccessDriveForTest.key.label} must not overlap " +
          s"(shared: ${overlap.toSeq.sorted.mkString(", ")})")
      // Wall-clock kill switch for the test JVM. Overridable via `sbt -Dmountk.test.globalTimeoutSec=NNN`;
      // default 5 min. Guarantees a deadlock can never lock up the whole harness.
      val globalTimeoutSec = sys.props.getOrElse(globalTimeoutSecProp, defaultGlobalTimeoutSec)
      // Per-example timeout enforced via specs2 AroundEach. Default 10s; override on command line.
      val perExampleTimeoutSec = sys.props.getOrElse(perExampleTimeoutSecProp, defaultPerExampleTimeoutSec)
      Seq(
        s"-D$unusedDrivesRangeProp=${mount.mkString}",
        s"-D$realDrivesRangeProp=${real.mkString}",
        s"-D$globalTimeoutSecProp=$globalTimeoutSec",
        s"-D$perExampleTimeoutSecProp=$perExampleTimeoutSec",
      )
    },
    Test / sourceGenerators += Def.task {
      val winApiBody = "package com.github.melezov.mountk\n\n" +
        IO.read(baseDirectory.value / "project" / "WinApi.scala")
      val file = (Test / sourceManaged).value /
        "com" / "github" / "melezov" / "mountk" / "WinApi.scala"
      IO.write(file, winApiBody)
      Seq(file)
    }.taskValue,
  )

  override def globalSettings: Seq[Def.Setting[?]] =
    Seq(excludeLintKeys ++= Set(unusedDrivesForTest, existingRootAccessDriveForTest))

  private val VersionPattern = """set "VERSION=(.+)"""".r

  private def readVersionFromBat(bat: File): String =
    IO.readLines(bat).collectFirst { case VersionPattern(v) => v }
      .getOrElse(sys.error(s"Could not find VERSION in ${bat.getAbsolutePath}"))
}
