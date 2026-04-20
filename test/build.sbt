name := "mount-k-test"

scalaVersion := "3.8.3"
scalacOptions ++= Seq(
  "-encoding", "utf-8",
  "-deprecation",
  "-feature",
  "-Wall",
  "-Wconf:msg=is not declared infix:silent",
)

libraryDependencies ++= Seq(
  "net.java.dev.jna" %  "jna-platform" % "5.18.1" % Test,
  "org.specs2"       %% "specs2-core"  % "5.9.0"  % Test,
)

// Candidate drive letters the tests may subst to. The test JVM probes these at startup and enters
// the actually-free ones into a shared pool; each spec reserves between its `minDrives` and
// `maxDrives` at beforeAll and releases at afterAll, so cross-spec parallelism is automatic.
unusedDrivesForTest := 'K' to 'Z'

// Real hard drives the tests may put the mount-k.bat into.
// E.g. D:\mount-k.bat - this allows us to verify mounting the entire disk.
// Tests use the first letter they can write to, with C: being deliberately excluded (it's usually write reserved).
existingRootAccessDriveForTest := 'D' to 'J'

Test / fork := true
Test / parallelExecution := true

Global / onChangedBuildSource := ReloadOnSourceChanges
