package com.github.melezov.mountk

import org.specs2.execute.{AsResult, Failure, Result}
import org.specs2.specification.AroundEach
import scribe.*

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ExecutionException, Executors, TimeUnit, TimeoutException}
import scala.concurrent.duration.*

/** Per-example timeout via `AroundEach`. Wraps each example body so any single fragment gets at most
  * `mountk.test.perExampleTimeoutMs` (default 30 sec). On timeout the worker is interrupted and the
  * example fails with a clear message, the rest of the spec keeps running. Layered with the
  * JVM-level `TestTimeouts.startGlobalTimeoutWatchdog` (5 min) so a lone runaway fails cleanly while
  * a systemic hang can't lock up the harness either. */
trait TestTimeouts extends AroundEach:
  private val timeoutMs = sys.props.get("mountk.test.perExampleTimeoutMs").map(_.toLong).getOrElse(30000L)

  def around[R: AsResult](r: => R): Result =
    val exec = Executors.newSingleThreadExecutor(
      new Thread(_, "mountk-example-worker") {
        setDaemon(true)
      }
    )
    try {
      val fut = exec.submit(() => AsResult(r))
      try fut.get(timeoutMs, TimeUnit.MILLISECONDS)
      catch
        case _: TimeoutException =>
          fut.cancel(true)
          Failure(s"example exceeded ${timeoutMs}ms wall-clock timeout")
        case e: ExecutionException =>
          val cause = Option(e.getCause).getOrElse(e)
          Failure(s"example threw ${cause.getClass.getSimpleName}: ${cause.getMessage}")
    } finally exec.shutdownNow(): Unit

/** JVM-wide kill switch for the test harness. `startGlobalTimeoutWatchdog` is called once per JVM
  * (idempotent via `started`); subsequent calls are noops. If the forked test JVM is still alive
  * after `mountk.test.globalTimeoutMs` (default 5 min), dump every thread's stack to stderr and
  * `Runtime.halt` the JVM. Guarantees the harness is never locked up by a deadlock in the drive
  * pool, a hung `subst`, or a `Process.!` that never returns. `ScriptSpec.beforeSpec` calls this;
  * standalone entry points like `Cleanup` don't, so they never spawn the timer. */
object TestTimeouts:
  private val started = new AtomicBoolean(false)

  def startGlobalTimeoutWatchdog(): Unit =
    if started.compareAndSet(false, true) then
      val timeout = sys.props.get("mountk.test.globalTimeoutMs").map(_.toLong.millis).getOrElse(5.minutes)
      new Thread(
        () =>
          try
            Thread.sleep(timeout.toMillis)
            error(s"!!! GLOBAL TEST TIMEOUT (${timeout.toString}) - thread dump follows:")
            import scala.jdk.CollectionConverters.*
            Thread.getAllStackTraces.asScala.toSeq.sortBy(_._1.getName).foreach { (th, stack) =>
              error(s"--- ${th.getName} (${th.getState.toString}) daemon=${th.isDaemon} ---")
              stack.foreach(f => error(s"  at ${f.toString}"))
            }
            Runtime.getRuntime.halt(124)
          catch case _: InterruptedException => (),
        "mountk-global-watchdog",
      ) {
        setDaemon(true)
      }.start()
