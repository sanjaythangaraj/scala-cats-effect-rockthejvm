package part4coordination

import cats.effect.{IO, IOApp, Ref}

import scala.concurrent.duration.*
import utils.debugLog

import scala.language.postfixOps

object Refs extends IOApp.Simple{

  // ref = purely functional atomic reference
  val atomicMol:IO[Ref[IO, Int]] = Ref[IO].of(42)
  val atomicMol_v2: IO[Ref[IO, Int]] = IO.ref(42)

  // modifying is an effect
  val increasedMol: IO[Unit] = atomicMol.flatMap { ref =>
    ref.set(43) // thread-safe
  }

  // obtain a value
  val mol: IO[Int] = atomicMol flatMap { ref =>
    ref.get // thread-safe
  }

  val getAndSetMol: IO[Int] = atomicMol flatMap { ref =>
    ref.getAndSet(43)
  } // gets the old value, sets the new one

  // update with a function
  val fMol: IO[Unit] = atomicMol flatMap { ref =>
    ref.update(value => value * 10)
  }

  val updatedMol: IO[Int] = atomicMol flatMap { ref =>
    ref.updateAndGet(value => value * 10) // get the new value
  } // can also use getAndUpdate to get the OLD value

  // modifying with a function returning a different type
  val modifiedMol: IO[String] = atomicMol.flatMap { ref =>
    ref.modify(value => (value * 10, s"my current value is $value"))
  }
  // debugLog:
  //  [io-compute-3] my current value is 42

  // WHY: concurrent + thread safe reads/writes over shared values, in a purely functional way

  import cats.syntax.parallel._

  def demoConcurrentWorkImpure: IO[Unit] = {

    var count = 0

    def task(workload: String): IO[Unit] = {
      val wordCount = workload.split(" ").length
      for {
        _ <- IO(s"Counting words for '$workload': '$wordCount'").debugLog
        newCount = count + wordCount
        _ <- IO(s"New total: $newCount").debugLog
        _ = count = newCount
      } yield ()
    }

    List("I love Cats Effect", "This ref thing is useless", "Daniel writes a lot of code")
      .map(task)
      .parSequence.void
  }
  // debLogs:
  //  [io-compute-2] Counting words for 'This ref thing is useless': '5'
  //  [io-compute-3] Counting words for 'I love Cats Effect': '4'
  //  [io-compute-7] Counting words for 'Daniel writes a lot of code': '6'
  //  [io-compute-7] New total: 6
  //  [io-compute-3] New total: 4
  //  [io-compute-2] New total: 5

  /*
    Drawbacks
      - hard to read/debug
      - mix pure/impure code
      - NOT THREAD SAFE
   */

  def demoConcurrentWorkPure: IO[Unit] = {
    def task(workload: String, total: Ref[IO, Int]): IO[Unit] = {
      val wordCount = workload.split(" ").length
      for {
        _ <- IO(s"Counting words for '$workload': '$wordCount'").debugLog
        newCount <- total.updateAndGet(_ + wordCount)
        _ <- IO(s"New total: $newCount").debugLog
      } yield ()
    }

    for {
      initialCount <- Ref[IO].of(0)
      _ <- List("I love Cats Effect", "This ref thing is useless", "Daniel writes a lot of code")
        .map(string => task(string, initialCount))
        .parSequence
    } yield ()
  }
  // debugLogs:
  //  [io-compute-3] Counting words for 'I love Cats Effect': '4'
  //  [io-compute-4] Counting words for 'Daniel writes a lot of code': '6'
  //  [io-compute-7] Counting words for 'This ref thing is useless': '5'
  //  [io-compute-7] New total: 15
  //  [io-compute-3] New total: 4
  //  [io-compute-4] New total: 10

  /*
    Exercise
   */
  def tickingClockImpure: IO[Unit] = {
    var ticks: Long = 0L
    def tickingClock: IO[Unit] = for {
      _ <- IO.sleep(1 second)
      _ <- IO(System.currentTimeMillis()).debugLog
      _ <- IO(ticks += 1) // not thread safe
      _ <- tickingClock
    } yield ()

    def printTicks: IO[Unit] = for {
      _ <- IO.sleep(5 seconds)
      _ <- IO(s"TICKS: $ticks").debugLog
      _ <- printTicks
    } yield ()

    for {
      _ <- (tickingClock, printTicks).parTupled
    } yield ()
  }

  def tickingClockPure: IO[Unit] = {

    def tickingClock(ticks: Ref[IO, Int]): IO[Unit] = for {
      _ <- IO.sleep(1 second)
      _ <- IO(System.currentTimeMillis()).debugLog
      _ <- ticks.update(_ + 1)
      _ <- tickingClock(ticks)
    } yield ()

    def printTicks(ticks: Ref[IO, Int]): IO[Unit] = for {
      _ <- IO.sleep(5 seconds)
      t <- ticks.get
      _ <- IO(s"TICKS: $t").debugLog
      _ <- printTicks(ticks)
    } yield ()

    for {
      tickRef <- Ref[IO].of(0)
      _ <- (tickingClock(tickRef), printTicks(tickRef)).parTupled
    } yield ()
  }

  def tickingClockWeird: IO[Unit] = {
    val ticks: IO[Ref[IO, Int]] = Ref[IO].of(0)

    def tickingClock: IO[Unit] = for {
      t <- ticks // ticks will give you a NEW Ref
      _ <- IO.sleep(1 second)
      _ <- IO(System.currentTimeMillis()).debugLog
      _ <- t.update(_ + 1) // thread safe effect
      _ <- tickingClock
    } yield ()

    def printTicks: IO[Unit] = for {
      t <- ticks // ticks will give you a NEW Ref
      _ <- IO.sleep(5 seconds)
      currentTicks <- t.get
      _ <- IO(s"TICKS: $currentTicks").debugLog
      _ <- printTicks
    } yield ()

    for {
      _ <- (tickingClock, printTicks).parTupled
    } yield ()
  }

  override def run: IO[Unit] = tickingClockPure.timeout(30 seconds).handleErrorWith(_ => IO("Shutting Down Clock...")).debugLog.void
}
