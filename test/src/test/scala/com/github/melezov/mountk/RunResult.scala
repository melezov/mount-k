package com.github.melezov.mountk

import org.specs2.execute.Result
import org.specs2.matcher.MustMatchers.*

import scala.language.implicitConversions
import scala.util.matching.Regex

case class RunResult(
                      exitCode: Int,
                      stdout: RunResult.Output,
                      stderr: RunResult.Output,
                      persisted: RunResult.Registry,
                      live: RunResult.Registry,
                    ):
  infix def was(e: ExitCode): Result = exitCode must beEqualTo(e.code)

object RunResult:

  /** Compile-time wrapper around a captured stdout/stderr stream. Opaque so the test code reads as
    * `out has "foo"` / `out has pattern.r` without paying for an allocation -- at runtime an Output
    * IS its underlying String. */
  opaque type Output = String

  object Output:
    def apply(s: String): Output = s

    extension (o: Output)
      def value: String = o
      infix def has(s: String): Result = (o: String) must contain(s)
      infix def has(r: Regex): Result = r.findFirstIn(o) must beSome
      infix def hasNot(s: String): Result = (o: String) must not(contain(s))
      infix def hasNot(r: Regex): Result = r.findFirstIn(o) must beNone
      def isEmpty: Result = (o: String) must beEmpty

  /** Compile-time wrapper around a `Char -> String` drive-keyed map. Two production sources feed it:
    * the per-lease registry-subtree snapshot (via `parse`, which projects raw value names like `"K:"`
    * to `'K'` and drops siblings that don't fit the drive-letter shape, e.g. `"_sentinel"`), and the
    * `queryDosDevices()` snapshot (already `Map[Char, String]`, passed through `apply` directly).
    * Tests that need to probe non-drive registry siblings should query `lease.regQueryValues`. */
  opaque type Registry = Map[Char, String]

  object Registry:
    def apply(m: Map[Char, String]): Registry = m

    /** Strict projection: every key MUST match `<letter>:`; an unrecognized key is a test-bug
      * signal (e.g. a stale sentinel that the per-spec wipe missed) and explodes loudly rather
      * than silently dropping it. Tests that intentionally seed non-drive siblings should project
      * inline with `Registry(map.collect { ... })` and inspect siblings via `lease.regQueryValues`. */
    def parse(m: Map[String, String]): Registry =
      m.map {
        case (DriveKey(d), v) => d.head -> v
        case (k, _) => sys.error(s"Registry.parse: non-drive key '$k' (expected '<letter>:')")
      }

    extension (reg: Registry)
      def underlying: Map[Char, String] = reg
      infix def has(c: Char): Result = (reg: Map[Char, String]) must haveKey(c)
      infix def hasNot(c: Char): Result = (reg: Map[Char, String]) must not(haveKey(c))
      def apply(c: Char): Option[String] = reg.get(c)
