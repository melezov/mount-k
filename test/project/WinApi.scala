import com.sun.jna.platform.win32.*

import scala.collection.immutable.TreeMap
import scala.util.Try

object WinApi {
  private val Hive = WinReg.HKEY_CURRENT_USER

  // ---------------------------------------------------------------------------
  //  Registry
  // ---------------------------------------------------------------------------

  def regDeleteValue(subPath: String, name: String): Unit =
    Try(Advapi32Util.registryDeleteValue(Hive, subPath, name)).getOrElse(())

  def regSetValue(subPath: String, name: String, value: String): Unit =
    Advapi32Util.registrySetStringValue(Hive, subPath, name, value)

  def regDeleteKey(subPath: String): Unit =
    Try(Advapi32Util.registryDeleteKey(Hive, subPath)).getOrElse(())

  def regQueryValues(subPath: String): Map[String, String] =
    Try {
      val values = Advapi32Util.registryGetValues(Hive, subPath)
      val result = Map.newBuilder[String, String]
      values.forEach { (k, v) => result += k -> v.toString }
      result.result()
    }.getOrElse(Map.empty)

  // ---------------------------------------------------------------------------
  //  subst
  // ---------------------------------------------------------------------------

  /** All current subst mappings, keyed by drive letter. Enumerates every DOS letter via `QueryDosDevice`
   *  and keeps only those whose NT target starts with `\??\` (i.e. a subst, not a real volume or network
   *  share); the `\??\` prefix is stripped so values are plain filesystem paths. */
  def queryDosDevices(): TreeMap[Char, String] = {
    val buf = new Array[Char](65536)
    val builder = TreeMap.newBuilder[Char, String]
    for (c <- 'A' to 'Z') {
      val written = Kernel32.INSTANCE.QueryDosDevice(s"$c:", buf, buf.length)
      if (written > 0) {
        val target = new String(buf, 0, written).takeWhile(_ != '\u0000')
        if (target.startsWith("\\??\\"))
          builder += c -> target.stripPrefix("\\??\\")
      }
    }
    builder.result()
  }

  def substAdd(letter: Char, target: String): Unit =
    Kernel32.INSTANCE.DefineDosDevice(0, s"$letter:", target): Unit

  def substDelete(letter: Char): Unit =
    Kernel32.INSTANCE.DefineDosDevice(WinNT.DDD_REMOVE_DEFINITION, s"$letter:", null): Unit

  /** True when `letter` currently has a subst mapping (NT target starts with `\??\`). */
  def isSubst(letter: Char): Boolean = {
    val buf = new Array[Char](1024)
    val written = Kernel32.INSTANCE.QueryDosDevice(s"$letter:", buf, buf.length)
    written > 0 && new String(buf, 0, written).takeWhile(_ != '\u0000').startsWith("\\??\\")
  }

  /** True when `letter` points at a fixed (non-removable, non-network, non-subst) volume. */
  def isFixedDrive(letter: Char): Boolean =
    Kernel32.INSTANCE.GetDriveType(s"$letter:\\") == WinBase.DRIVE_FIXED

  /** Probe whether `letter` can be subst-mapped right now by attempting the mapping and tearing it
   *  down on success. Returns true when the add succeeded (and was then removed). */
  def canSubst(letter: Char, target: String): Boolean = {
    val added = Kernel32.INSTANCE.DefineDosDevice(0, s"$letter:", target)
    if (added) substDelete(letter)
    added
  }

  // ---------------------------------------------------------------------------
  //  Path utilities
  // ---------------------------------------------------------------------------

  /** 8.3 short path for `path` via `GetShortPathNameW`. Returns the long form unchanged when the
   *  volume has 8.3 generation disabled (short == long), which lets callers detect that case with
   *  a plain string compare instead of a separate API call. */
  def shortPath(path: String): String = {
    val buf = new Array[Char](260)
    val written = Kernel32.INSTANCE.GetShortPathName(path, buf, buf.length)
    if (written <= 0) path else new String(buf, 0, written)
  }
}
