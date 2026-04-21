import com.sun.jna.platform.win32.*

import scala.collection.immutable.TreeMap
import scala.util.Try

object WinApi {
  private val DefaultHive = WinReg.HKEY_CURRENT_USER

  private val DosDeviceBufferSize = 65536

  // ---------------------------------------------------------------------------
  //  Registry
  // ---------------------------------------------------------------------------

  def regDeleteValue(subPath: String, name: String): Unit =
    Try(Advapi32Util.registryDeleteValue(DefaultHive, subPath, name)).getOrElse(())

  def regSetValue(subPath: String, name: String, value: String): Unit =
    Advapi32Util.registrySetStringValue(DefaultHive, subPath, name, value)

  def regDeleteKey(subPath: String): Unit =
    Try(Advapi32Util.registryDeleteKey(DefaultHive, subPath)).getOrElse(())

  def regQueryValues(subPath: String): Map[String, String] = regQueryValues(DefaultHive, subPath)

  /** Read every value under `hive\subPath` into a plain `Map` */
  def regQueryValues(hive: WinReg.HKEY, subPath: String): Map[String, String] =
    Try {
      val values = Advapi32Util.registryGetValues(hive, subPath)
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
  private def queryNtTarget(letter: Char): Option[String] = {
    val buf = new Array[Char](DosDeviceBufferSize)
    val written = Kernel32.INSTANCE.QueryDosDevice(s"$letter:", buf, buf.length)
    if (written > 0) Some(new String(buf, 0, written).takeWhile(_ != '\u0000'))
    else None
  }

  def queryDosDevices(): TreeMap[Char, String] = {
    val builder = TreeMap.newBuilder[Char, String]
    for (c <- 'A' to 'Z'; target <- queryNtTarget(c) if target.startsWith("\\??\\"))
      builder += c -> target.stripPrefix("\\??\\")
    builder.result()
  }

  /** Returns whether `DefineDosDevice` actually removed the mapping. `false` typically
   * means access-denied because the subst belongs to another LUID (e.g. an elevated shell). */
  def substDelete(letter: Char): Boolean =
    Kernel32.INSTANCE.DefineDosDevice(WinNT.DDD_REMOVE_DEFINITION, s"$letter:", null)

  /** True when `letter` currently has a subst mapping (NT target starts with `\??\`). */
  def isSubst(letter: Char): Boolean =
    queryNtTarget(letter).exists(_.startsWith("\\??\\"))

  /** True when `letter` points at a fixed (non-removable, non-network, non-subst) volume. */
  def isFixedDrive(letter: Char): Boolean =
    Kernel32.INSTANCE.GetDriveType(s"$letter:\\") == WinBase.DRIVE_FIXED

  /** Probe whether `letter` can be subst-mapped right now by attempting the mapping and tearing it
   *  down on success. Returns true when the add succeeded (and was then removed). */
  def canSubst(letter: Char, target: String): Boolean = {
    val added = Kernel32.INSTANCE.DefineDosDevice(0, s"$letter:", target)
    if (added) substDelete(letter): Unit
    added
  }
}
