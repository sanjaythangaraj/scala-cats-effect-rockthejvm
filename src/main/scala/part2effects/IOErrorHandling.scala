package part2effects

import cats.effect.IO

import scala.util.{Failure, Success, Try}

object IOErrorHandling {

  // IO: pure, delay, defer
  // create failed effects

  val aFailedCompute: IO[Int] = IO.delay(throw new RuntimeException("A Failure"))
  val aFailure: IO[Int] = IO.raiseError(new RuntimeException("A proper fail"))

  // handle exceptions

  val dealWithIt = aFailure.handleErrorWith {
    case _: RuntimeException => IO { println("I'm still here") }
  }

  // turn into Either
  val effectAsEither: IO[Either[Throwable, Int]] = aFailure.attempt
  // reedem: transform the failure and the success in one go
  val resultAsString: IO[String] = aFailure.redeem(ex => s"FAIL: $ex", value => s"SUCCESS: $value")
  // reedemWith
  val resultAsEffect: IO[Unit] = aFailure.redeemWith(
    ex => IO(println(s"FAIL : $ex")),
    value => IO(println(s"SUCCESS: $value"))
  )

  /*
    Exercises
      1. construct potentially failed IOs from standard data types (Option, Try, Either)
      2. handleError, handleErrorWith
   */

  def option2IO[A](option: Option[A])(ifEmpty: Throwable): IO[A] = option match {
    case Some(value) => IO(value)
    case None => IO.raiseError(ifEmpty)
  }

  def try2IO[A](aTry: Try[A]): IO[A] = aTry match
    case Failure(exception) => IO.raiseError(exception)
    case Success(value) => IO(value)

  def either2IO[A](anEither: Either[Throwable, A]): IO[A] = anEither match
    case Left(ex) => IO.raiseError(ex)
    case Right(value) => IO(value)

  def handleIOError[A](io: IO[A])(handler: Throwable => A): IO[A] =
    io.redeem(handler, identity)

  def handleIOErrorWith[A](io: IO[A])(handler: Throwable => IO[A]): IO[A] =
    io.redeemWith(handler, IO.pure)

  def main(args: Array[String]): Unit =

    import cats.effect.unsafe.implicits.global
    resultAsEffect.unsafeRunSync()

}
