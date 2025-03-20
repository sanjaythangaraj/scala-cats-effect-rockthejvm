package concurrency

import cats.effect.Outcome
import cats.effect.{Fiber, FiberIO, IO, IOApp}
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Canceled

import scala.language.postfixOps
import scala.concurrent.duration._

object Fibers extends IOApp.Simple {

  val meaningOfLife = IO.pure(42)
  val favLang = IO.pure("scala")

  import utils._

  def sameThreadIOs: IO[Unit] = for {
    _ <- meaningOfLife.debugLog
    _ <- favLang.debugLog
  } yield ()
  // debugLogs:
  // [io-compute-1] 42
  // [io-compute-1] scala

  // introduce the fiber
  def createFiber: Fiber[IO, Throwable, String] = ??? // almost impossible to create fibers manually

  // the fiber is not actually started, but the fiber allocation is wrapped in another effect.
  val aFiber: IO[Fiber[IO, Throwable, Int]] = meaningOfLife.debugLog.start

  def differentThreadIOs = for {
    _ <- aFiber
    _ <- favLang.debugLog
  } yield ()
  //  debugLogs:
  //  [io-compute-2] 42
  //  [io-compute-0] scala

  // joining a fiber
  def runOnSomeOtherThread[A](io: IO[A]): IO[Outcome[IO, Throwable, A]] = for {
    fib <- io.start
    result <- fib.join // an effect which wait for the fiber to terminate
  } yield result
  // debugLog: [io-compute-6] Succeeded(IO(42))

  val someIOOnAnotherThread = runOnSomeOtherThread(meaningOfLife)
  val someResultFromAnotherThread: IO[Int] = someIOOnAnotherThread flatMap {
    case Succeeded(effect) => effect
    case Errored(e) => IO(0)
    case Canceled() => IO(0)
  }

  def throwOnAnotherThread = for {
    fib <- IO.raiseError[Int](new RuntimeException("no number for you")).start
    result <- fib.join
  } yield result
  // debugLog:
  // [io-compute-4] Errored(java.lang.RuntimeException: no number for you)

  def testCancel = {
    val task = IO("starting").debugLog >> IO.sleep(1 second) >> IO("done").debugLog
    val taskWithCancellationHandler = task.onCancel(IO("I'm being cancelled!").debugLog.void)
    for {
      fib <- taskWithCancellationHandler.start // on a seperate thread
      _ <- IO.sleep(500 millis) >> IO("cancelling").debugLog // running on the calling thread
      _ <- fib.cancel
      result <- fib.join
    } yield result

    /*
      debugLogs for task:
        [io-compute-1] starting
        [io-compute-5] cancelling
        [io-compute-5] Canceled()

      debugLogs for taskWithCancellationHandler
        [io-compute-5] starting
        [io-compute-5] cancelling
        [io-compute-5] I'm being cancelled!
        [io-compute-6] Canceled()
     */
  }

  /*
    Exercises
      1. write a function that runs an IO on another thread, and, depending on the result of the fiber
        - return the result in an IO
        - if errored or cancelled, return a failed IO

      2. Write a function that takes two IOs,
        runs them at different fibers and returns an IO with a tuple containing both results
          - if both IOs complete successfully, tuple their results
          - if the first IO returns an error, raise that error (ignoring the second IO's result/error)
          - if the first IO doesn't return error but second IO returns an error, raise that error
          - if one (or both) canceled raise a runtime exception

      3. Write a function that adds a timeout to an IO
        - IO runs on a fiber
        - if the timeout duration passes, then the fiber is canceled
        - the method returns an IO[A] which contains
            - the original value if the computation is successful before the timeout signal
            - the exception if the failed before the timeout signal
            - a RuntimeException if it times out (i.e. canceled by the timeout)
   */

  // 1
  def processResultsFromFiber[A](io: IO[A]): IO[A] =  {
    val res = for {
      fiber <- io.debugLog.start
      result <- fiber.join
    } yield result

    res flatMap {
      case Succeeded(effect) => effect
      case Errored(e) => IO.raiseError[A](e)
      case Canceled() => IO.raiseError[A](new RuntimeException("Computation canceled."))
    }
  }

  def testEx1 = {
    val aComputation = IO("starting").debugLog >> IO.sleep(1 second) >> IO("done").debugLog >> IO(42)
    processResultsFromFiber(aComputation).void
  }

  // 2
  def tupleIOs[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] = {
    val res = for {
      fiberA <- ioa.start
      fiberB <- iob.start
      resultA <- fiberA.join
      resultB <- fiberB.join
    } yield (resultA, resultB)

    res flatMap {
      case (Succeeded(fa), Succeeded(fb)) => for {
        a <- fa
        b <- fb
      } yield (a, b)
      case (Errored(e), _) => IO.raiseError(e)
      case (_, Errored(e)) => IO.raiseError(e)
      case _ => IO.raiseError(new RuntimeException("Some computation canceled"))
    }
  }

  def testEx2 = {
    val firstIO= IO.sleep(2 seconds) >> IO(1).debugLog
    val secondIO = IO.sleep(3 seconds) >> IO(2).debugLog
    tupleIOs(firstIO, secondIO).debugLog.void
  }

  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] = {
    val computation = for {
      fib <- io.start
      _ <- IO.sleep(duration) >> fib.cancel // calling cancel if completed fiber is no-op
      result <- fib.join
    } yield result

    // computation takes at least duration of time (even if fib completes way sooner than duration)

    computation flatMap {
      case Succeeded(fa) => fa
      case Errored(e) => IO.raiseError(e)
      case Canceled() => IO.raiseError(new RuntimeException("Computation canceled"))
    }
  }

  def timeout_v2[A](io: IO[A], duration: FiniteDuration): IO[A] = {
    val computation = for {
      fib <- io.start
      _ <- (IO.sleep(duration) >> fib.cancel).start // careful - fibers can leak!
      result <- fib.join
    } yield result

    // computation returns as soon as fiber completes if not timeout

    computation flatMap {
      case Succeeded(fa) => fa
      case Errored(e) => IO.raiseError(e)
      case Canceled() => IO.raiseError(new RuntimeException("Computation canceled"))
    }
  }

  def testEx3 = {
    val aComputation = IO("starting").debugLog >> IO.sleep(1 second) >> IO("done").debugLog >> IO(42)
    timeout_v2(aComputation, 3 millis).debugLog.void
  }

  override def run: IO[Unit] = testEx3
}

/*
  possible outcomes:
    - success with an IO
    - failure with an exception
    - cancelled
 */
