package com.github.melezov.mountk

import scribe.*

import java.io.File
import java.nio.file.{Files, Path}
import scala.util.chaining.*

private val loggerInit: Logger = {
  import scribe.format.*
  import scribe.output.{Color, ColoredOutput, TextOutput}

  Logger.root
    .clearHandlers()
    .withHandler(formatter = Formatter.fromBlocks(
      openBracket,
      (record: LogRecord) => new ColoredOutput(
        Color.Cyan, // doesn't work in SBT? :/
        new TextOutput(record.level.name.toLowerCase)
      ),
      closeBracket,
      space,
      time,
      space,
      messages,
    )).replace()
}

val version: String = BuildInfo.version

val projectRoot: String =
  new File(classOf[ScriptSpec].getProtectionDomain.getCodeSource.getLocation.toURI)
    .getCanonicalPath
    .replace('\\', '/')
    .pipe { thisPath =>
      thisPath
        .replaceFirst("(.*/test/)target/.*", "$1")
        .ensuring(_ != thisPath, "Could not locate project root via test/target folder")
    }

/** Path to mount-k.bat - one level up from test project root */
val scriptPath: Path =
  Path.of(projectRoot).getParent.resolve("mount-k.bat")

lazy val scriptContent: String = Files.readString(scriptPath)

/** HKCU subpath under which every spec writes its own private registry subtree. Individual spec roots are
  * `s"$TestRegSubPath\\<SpecName>"`, so parallel specs never share a key and there's no contention on the
  * `regDeleteKey` / `regSetValue` calls the tests make. */
val TestRegSubPath: String = "Software\\mount-k-test"

/** Full `reg.exe`-style path to the per-spec test subtree -- shared by `ScriptSpec` (lease.regKey
  * composition) and `Cleanup` (top-level `reg delete` of the whole subtree). */
val TestRegRoot: String = s"HKCU\\$TestRegSubPath"

/** HKLM subpath the production script writes persistent subst entries to. Shared by `ScriptSpec.
  * patchForTest` (which redirects writes off this key into HKCU for tests) and `Cleanup` (which
  * scans this key during the elevated cleanup pass). */
val HklmDosDevicesSubPath: String = "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\DOS Devices"

/** Strict drive-key form, e.g. `K:`. Used wherever the codebase needs to recognize a registry value
  * name or projected map key as a drive letter. Cleanup uses a separate `[A-Za-z]` pattern because
  * HKLM may legitimately hold lowercase entries written by external tools. */
val DriveKey = """^([A-Z]):$""".r

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
