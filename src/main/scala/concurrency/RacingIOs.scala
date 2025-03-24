package concurrency

import cats.effect.kernel.Outcome
import cats.effect.kernel.Outcome.{Succeeded, Canceled, Errored}
import cats.effect.{Fiber, IO, IOApp}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*
import scala.language.postfixOps

object RacingIOs extends IOApp.Simple{

  import utils.debugLog

  def runWithSleep[A](value: A, duration: FiniteDuration): IO[A] =
    (
      IO(s"starting computation: $value").debugLog >> IO.sleep(duration) >>
      IO(s"computation for $value: done") >> IO(value)
    ).onCancel(IO(s"computation CANCELED for $value").debugLog.void)

  def testRace = {
    val meaningOfLife = runWithSleep(42, 1 second)
    val favLang = runWithSleep("Scala", 2 seconds)

    val first: IO[Either[Int, String]] = IO.race(meaningOfLife, favLang)

    /*
      both IOs run on separate fibers
      the first one to finish will complete the result
      the loser will be canceled
     */

    first flatMap {
      case Left(meaningOfLife) => IO(s"meaning of life won: $meaningOfLife").debugLog
      case Right(favLang) => IO(s"fav language won: $favLang").debugLog
    }
  }
  // debugLogs:
  //  [io-compute-1] starting computation: 42
  //  [io-compute-0] starting computation: Scala
  //  [io-compute-1] computation CANCELED for Scala
  //  [io-compute-1] meaning of life won: 42

  def testRacePair = {
    val meaningOfLife = runWithSleep(42, 1 second)
    val favLang = runWithSleep("Scala", 2 seconds)

    val raceResult: IO[Either[
      (Outcome[IO, Throwable, Int], Fiber[IO, Throwable, String]),
      (Fiber[IO, Throwable, Int], Outcome[IO, Throwable, String])
    ]] =
      IO.racePair(meaningOfLife, favLang)

    raceResult flatMap {
      case Left((outMol, fibLang)) => fibLang.cancel >> IO("MOL won").debugLog >> IO(outMol).debugLog
      case Right((fibMol, outLang)) => fibMol.cancel >> IO("Language won").debugLog >> IO(outLang).debugLog
    }
  }
  // debugLogs:
  //  [io-compute-6] starting computation: 42
  //  [io-compute-1] starting computation: Scala
  //  [io-compute-6] computation CANCELED for Scala
  //  [io-compute-6] MOL won
  //  [io-compute-6] Succeeded(IO(42))

  /*
    Exercises
      1. implement a timeout pattern with race
      2. a method to return a LOSING effect from a race (hint: use racePair)
      3. implement race in terms of racePair
   */

  // 1
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] =
    val first = IO.race(IO.sleep(duration), io)
    first flatMap {
      case Left(_) => IO.raiseError(new RuntimeException("computation timed out"))
      case Right(value) => IO(value)
    }

  val importantTask = IO.sleep(2 seconds) >> IO(42).debugLog

  val testTimeout: IO[Int] = timeout(importantTask, 3 seconds)
  // debugLog:
  //  [io-compute-5] 42
  //  or
  //  java.lang.RuntimeException: computation timed out

  val testTimeout_v2 = importantTask.timeout(3 seconds)
  // debugLog:
  //  [io-compute-7] 42
  // or
  // java.util.concurrent.TimeoutException

  // 2
  def unrace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob) flatMap {
      case Left((_, fibB)) => fibB.join flatMap {
        case Succeeded(resultEffect) => resultEffect.map(result => Right(result))
        case Errored(e) => IO.raiseError(e)
        case Canceled() => IO.raiseError(new RuntimeException("Loser Canceled"))
      }
      case Right((fibA, _)) => fibA.join flatMap {
        case Succeeded(resultEffect) => resultEffect.map(result => Left(result))
        case Errored(e) => IO.raiseError(e)
        case Canceled() => IO.raiseError(new RuntimeException("Loser Canceled"))
      }
    }

  def testUnrace = {
    val meaningOfLife = runWithSleep(42, 1 second)
    val favLang = runWithSleep("Scala", 2 seconds)

    val last: IO[Either[Int, String]] = unrace(meaningOfLife, favLang)

    last flatMap {
      case Left(meaningOfLife) => IO(s"meaning of life won: $meaningOfLife").debugLog
      case Right(favLang) => IO(s"fav language won: $favLang").debugLog
    }
  }
  // debugLogs:
  //  [io-compute-0] starting computation: 42
  //  [io-compute-7] starting computation: Scala
  //  [io-compute-7] fav language won: Scala

  // 3
  def simpleRace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob) flatMap {
      case Left((outA, fibB)) => outA match
        case Succeeded(effectA) => fibB.cancel >> effectA.map(a => Left(a))
        case Errored(e) => fibB.cancel >> IO.raiseError(e)
        case Canceled() => fibB.join flatMap {
          case Succeeded(effectB) => effectB.map(b => Right(b))
          case Errored(e) => IO.raiseError(e)
          case Canceled() => IO.raiseError(new RuntimeException("Both computations canceled."))
        }
      case Right((fibA, outB)) => outB match
        case Succeeded(effectB) => fibA.cancel >> effectB.map(b => Right(b))
        case Errored(e) => fibA.cancel >> IO.raiseError(e)
        case Canceled() => fibA.join flatMap {
          case Succeeded(effectA) => effectA.map(a => Left(a))
          case Errored(e) => IO.raiseError(e)
          case Canceled() => IO.raiseError(new RuntimeException("Both computations canceled."))
        }
    }

  val testSimpleRace = {
    val meaningOfLife = runWithSleep(42, 1 second)
    val favLang = runWithSleep("Scala", 2 seconds)

    val first: IO[Either[Int, String]] = simpleRace(meaningOfLife, favLang)

    first flatMap {
      case Left(meaningOfLife) => IO(s"meaning of life won: $meaningOfLife").debugLog
      case Right(favLang) => IO(s"fav language won: $favLang").debugLog
    }
  }
  // debugLogs:
  //  [io-compute-0] starting computation: 42
  //  [io-compute-3] starting computation: Scala
  //  [io-compute-4] computation CANCELED for Scala
  //  [io-compute-4] meaning of life won: 42

  override def run: IO[Unit] = testSimpleRace.void
}
