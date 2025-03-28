package part4coordination

import cats.effect.kernel.Outcome
import cats.effect.{Deferred, Fiber, IO, IOApp, Ref}
import utils.debugLog

import scala.concurrent.duration.*
import scala.language.postfixOps
import cats.syntax.traverse.*

object Defers extends IOApp.Simple{

  // deferred is a primitive for waiting for an effect, while some other effect completes with a value

  val aDeferred: IO[Deferred[IO, Int]] = Deferred[IO, Int]
  val aDeferred_v2: IO[Deferred[IO, Int]] = IO.deferred[Int] // same

  // get blocks the calling fiber (semantically) until some other fiber completes the Deferred with a value
  val reader: IO[Int] = aDeferred flatMap { signal =>
    signal.get
  }

  val writer: IO[Boolean] = aDeferred flatMap { signal =>
    signal.complete(42)
  }

  def demoDeferred: IO[Unit] = {
    def consumer(signal: Deferred[IO, Int]) = for {
      _ <- IO("[consumer] waiting for result...").debugLog
      meaningOfLife <- signal.get
      _ <- IO(s"[consumer] got the result: $meaningOfLife").debugLog
    } yield ()

    def producer(signal: Deferred[IO, Int]) = for {
      _ <- IO("[producer] crunching numbers...").debugLog
      _ <- IO.sleep(1 second)
      _ <- IO("[producer] complete: 42").debugLog
      meaningOfLife <- IO(42)
      _ <- signal.complete(meaningOfLife)
    } yield ()

    for {
      signal <- Deferred[IO, Int]
      fibConsumer <- consumer(signal).start
      fibProducer <- producer(signal).start
      _ <- fibConsumer.join
      _ <- fibProducer.join
    } yield ()
  }
  // debugLogs:
  //  [io-compute-6] [consumer] waiting for result...
  //  [io-compute-3] [producer] crunching numbers...
  //  [io-compute-3] [producer] complete: 42
  //  [io-compute-1] [consumer] got the result: 42

  // simulate downloading some content
  val fileParts = List("I ", "love S", "cala", "with Cat", "s Effect!<EOF>")

  def fileNotifierWithRef: IO[Unit] = {

    def downloadFile(contentRef: Ref[IO, String]): IO[Unit] =
      fileParts.map { part =>
        IO(s"got '$part'").debugLog >> IO.sleep(1 second) >> contentRef.update(content => content + part)
      }.sequence.void

    def notifyFileComplete(contentRef: Ref[IO, String]): IO[Unit] = for {
      file <- contentRef.get
      _ <- if (file.endsWith("<EOF>")) IO("File download complete").debugLog
          else IO("downloading...").debugLog >> IO.sleep(500 millis) >> notifyFileComplete(contentRef) // busy wait!
    } yield ()

    for {
      contentRef <- Ref[IO].of("")
      fibDownloader <- downloadFile(contentRef).start
      notifier <- notifyFileComplete(contentRef).start
      _ <- fibDownloader.join
      _ <- notifier.join
    } yield()
  }

  def fileNotifierWithDeferred: IO[Unit] = {
    def notifyFileComplete(signal: Deferred[IO, String]): IO[Unit] = for {
      _ <- IO("downloading...").debugLog
      _ <- signal.get // blocks until the signal is completed
      _ <- IO("File download complete").debugLog
    } yield ()

    def downloadFilePart(part: String, contentRef: Ref[IO, String], signal: Deferred[IO, String]): IO[Unit] = for {
      _ <- IO(s"got $part").debugLog
      _ <- IO.sleep(1 second)
      latestContent <- contentRef.updateAndGet(content => content + part)
      _ <- if (latestContent.endsWith("<EOF>")) signal.complete(latestContent) else IO.unit
    } yield ()

    for {
      contentRef <- Ref[IO].of("")
      signal <- Deferred[IO, String]
      notifierFib <- notifyFileComplete(signal).start
      fileTasksFib <- fileParts.map(part => downloadFilePart(part, contentRef, signal)).sequence.start
      _ <- notifierFib.join
      _ <- fileTasksFib.join
    } yield ()
  }

  /*
    Exercises:
      1. (medium) write a small alarm notification with two simultaneous IOs
        - one that increments a counter every second (a clock)
        - one that waits for the counter to become 10, then prints a message "time's up!"

      2. (mega hard) implement racePair with Deferred
        - use a Deferred which can hold an Either[outcome for ioa, outcome for iob]
        - start two fibers, one for each IO
        - on completion (with any status), each IO needs to complete that Deferred
          (hint: use a finalizer from Resources
          (hint2: use a guarantee call to make sure the fibers complete the Deferred)
        - what do you do in case of cancellation (the hardest part)?
   */

  // 1
  def countDownTimer: IO[Unit] = {

    def clock(ref: Ref[IO, Int], signal: Deferred[IO, Int]): IO[Unit] = for {
      _ <- IO.sleep(1 second)
      seconds <- ref.updateAndGet(_ - 1)
      _ <- IO(s"$seconds").debugLog
      _ <- if (seconds == 0) signal.complete(0) else clock(ref, signal)
    } yield ()

    def notifier(signal: Deferred[IO, Int]): IO[Unit] = for {
      _ <- signal.get
      _ <- IO("time's up").debugLog
    } yield ()

    for {
      ref <- Ref[IO].of(10)
      signal <- Deferred[IO, Int]
      clockFib <- clock(ref, signal).start
      notifierFib <- notifier(signal).start
      _ <- clockFib.join
      _ <- notifierFib.join
    } yield ()
  }

  // 2

  type RaceResult[A, B] = Either[
    (Outcome[IO, Throwable, A], Fiber[IO, Throwable, B]), // (winner result, loser fiber)
    (Fiber[IO, Throwable, A], Outcome[IO, Throwable, B]) // (loser fiber, winner result)
  ]

  type EitherOutcome[A, B] = Either[Outcome[IO, Throwable, A], Outcome[IO, Throwable, B]]

  def ourRacePair[A, B](ioa: IO[A], iob: IO[B]): IO[RaceResult[A, B]] = IO.uncancelable { poll =>
    for {
      signal <- Deferred[IO, EitherOutcome[A, B]]
      fibA <- ioa.guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void).start
      fibB <- iob.guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void).start
      result <- poll(signal.get).onCancel { // blocking call - should be cancelable
        for {
          cancelFibA <- fibA.cancel.start
          cancelFibB <- fibB.cancel.start
          _ <- cancelFibA.join
          _ <- cancelFibB.join
        } yield ()
      }
    } yield result match {
      case Left(outcomeA) => Left((outcomeA, fibB))
      case Right(outcomeB) => Right((fibA, outcomeB))
    }
  }

  def runWithSleep[A](value: A, duration: FiniteDuration): IO[A] =
    (
      IO(s"starting computation: $value").debugLog >> IO.sleep(duration) >>
        IO(s"computation for $value: done") >> IO(value)
      ).onCancel(IO(s"computation CANCELED for $value").debugLog.void)

  def testOurRacePair = {
    val meaningOfLife = runWithSleep(42, 1 second)
    val favLang = runWithSleep("Scala", 2 seconds)

    val raceResult: IO[Either[
      (Outcome[IO, Throwable, Int], Fiber[IO, Throwable, String]),
      (Fiber[IO, Throwable, Int], Outcome[IO, Throwable, String])
    ]] =
      ourRacePair(meaningOfLife, favLang)

    raceResult flatMap {
      case Left((outMol, fibLang)) => fibLang.cancel >> IO("MOL won").debugLog >> IO(outMol).debugLog
      case Right((fibMol, outLang)) => fibMol.cancel >> IO("Language won").debugLog >> IO(outLang).debugLog
    }
  }

  override def run: IO[Unit] = testOurRacePair.void
}
