package part2effects

import cats.Parallel
import cats.effect.{IO, IOApp}

object IOParallelism extends IOApp.Simple {

  // IOs are usually sequential
  val aniIO = IO(s"[${Thread.currentThread().getName}] Ani")
  val kamranIO = IO(s"[${Thread.currentThread().getName}] Kamran")

  val composedIO = for {
    ani <- aniIO
    kamran <- kamranIO
  } yield s"$ani and $kamran love Rock the JVM"

  // debug extension method
  import utils.debugLog

  val meaningOfLife: IO[Int] = IO(42)
  val favLang: IO[String] = IO("scala")

  import cats.syntax.apply._

  val goalInLife: IO[String] = (meaningOfLife.debugLog, favLang.debugLog).mapN((num, string) => s"my goal in life is $num and $string")

  // parallelism on IOs
  // convert a sequential IO to parallel IO
  val parIO1: IO.Par[Int] = Parallel[IO].parallel(meaningOfLife.debugLog)
  val parIO2: IO.Par[String] = Parallel[IO].parallel(favLang.debugLog)

  import cats.effect.implicits._

  val goalInLifeParallel: IO.Par[String] = (parIO1, parIO2).mapN((num, string) => s"my goal in life is $num and $string")
  // turn back to sequential
  val goalInLife_v2: IO[String] = Parallel[IO].sequential(goalInLifeParallel)

  // shorthand
  import cats.syntax.parallel._

  val goalInLife_v3: IO[String] = (meaningOfLife.debugLog, favLang.debugLog).parMapN((num, string) => s"my goal in life is $num and $string")

  // regarding failure
  val aFailure: IO[String] = IO.raiseError(new RuntimeException("I can't do this"))
  // compose success + failure
  val parallelWithFailure: IO[String] = (meaningOfLife.debugLog, aFailure.debugLog).parMapN(_ + _)

  // compose failure + failure
  val anotherFailure: IO[String] = IO.raiseError(new RuntimeException("second failure"))
  // the first effect to fail gives failure of result
  val twoFailures: IO[String] = (aFailure.debugLog, anotherFailure.debugLog).parMapN(_ + _)

  override def run: IO[Unit] = goalInLife_v3.debugLog.void
}
