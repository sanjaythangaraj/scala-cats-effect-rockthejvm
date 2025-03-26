package concurrency

import cats.effect.{IO, IOApp}

import scala.concurrent.duration.*
import scala.language.postfixOps
import utils.debugLog

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object BlockingIOs extends IOApp.Simple{

  val someSleep = for {
    _ <- IO.sleep(1 second).debugLog // SEMANTIC BLOCKING
    _ <- IO.sleep(1 second).debugLog
  } yield ()

  // really blocking IOs
  val aBlockingIO = IO.blocking {
    Thread.sleep(1000)
    println(s"[${Thread.currentThread().getName}] computed a blocking code")
    42
  } // will evaluate on a thread from ANOTHER thread pool specific for blocking calls
  // [io-compute-blocker-6] computed a blocking code

  // yielding
  val isOnManyThreads = for {
    _ <- IO("first").debugLog
    _ <- IO.cede // a signal to yield control over the thread
    _ <- IO("second").debugLog // the rest of this may run on another thread (not necessarily)
    _ <- IO.cede
    _ <- IO("third").debugLog
  } yield ()

  def testThousandEffectsSwitch = {
    val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))
    (1 to 1000).map(IO.pure).reduce(_.debugLog >> IO.cede >> _.debugLog).evalOn(ec)
  }

  override def run: IO[Unit] = testThousandEffectsSwitch.void
}
