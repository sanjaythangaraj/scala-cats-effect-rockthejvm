package part2effects

import cats.effect.IO

import scala.io.StdIn

object IOIntroduction {

  // IO
  val ourFirstIO: IO[Int] = IO.pure(42) // arg that should not have side effects
  val aDelayedIO: IO[Int] = IO.delay {
    println("I'm producing an integer")
    54
  }

  val aDelayedIO_v2: IO[Int] = IO { // apply == delay
    println("I'm producing an integer")
    54
  }

//  val shouldNotDoThis: IO[Int] = IO.pure {
//    println("I'm producing an integer")
//    54
//  }

  // map, flatMap
  val improvedMeaningOfLife: IO[Int] = ourFirstIO.map(_ * 2)
  val printedMeaningOfLife: IO[Unit] = ourFirstIO.flatMap(x => IO.delay(println(x)))

  def smallProgram: IO[Unit] = for {
    line1 <- IO(StdIn.readLine())
    line2 <- IO(StdIn.readLine())
    _ <- IO.delay(println(line1 + line2))
  } yield ()

  // mapN - combine IO effects as tuples

  import cats.syntax.apply._

  val combineMeaningOfLife: IO[Int] = (ourFirstIO, improvedMeaningOfLife).mapN(_ + _)

  def smallProgram_v2(): IO[Unit] =
    (IO(StdIn.readLine()), IO(StdIn.readLine())).mapN(_ + _).map(println)

  /*
    Exercises
      1. sequence two IOs and take the result of the last one
      2. sequence two IOs and take the result of the first one
      3. repeat an IO effect forever
      4. convert an IO to a different type
      5. discard value inside an IO, just return Unit
      6. fix stack recursion
      7. write a fibonacci IO that does not crash on recursion
   */

  def sequenceTakeLast[A, B](ioa: IO[A], iob: IO[B]): IO[B] = for {
    _ <- ioa
    b <- iob
  } yield b

  def sequenceTakeLast_v2[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa.flatMap(_ => iob)

  def sequenceTakeLast_v3[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa *> iob // andThen

  def sequenceTakeLast_v4[A, B](ioa: IO[A], iob: IO[B]): IO[B] =
    ioa >> iob // andThen with by-name call

  def sequenceTakeFirst[A, B](ioa: IO[A], iob: IO[B]): IO[A] = for {
    a <- ioa
    _ <- iob
  } yield a

  def sequenceTakeFirst_v2[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    ioa.flatMap(a => iob.map(_ => a))

  def sequenceTakeFirst_v3[A, B](ioa: IO[A], iob: IO[B]): IO[A] =
    ioa <* iob

  def forever[A](io: IO[A]): IO[A] = io.flatMap(_ => forever(io))

  def forever_v2[A](io: IO[A]): IO[A] =
    io >> forever_v2(io)

  def forever_v3[A](io: IO[A]): IO[A] =
    io *> forever_v3(io) // crashes

  def forever_v4[A](io: IO[A]): IO[A] =
    io.foreverM // with tail recursion

  def convert[A,B](ioa: IO[A], value: B): IO[B] = ioa.map(_ => value)

  def convert_v2[A,B](ioa: IO[A], value: B): IO[B] = ioa.as(value)

  def asUnit[A](ioa: IO[A]): IO[Unit] = ioa.map(_ => ())

  def asUnit_v2[A](ioa: IO[A]): IO[Unit] = ioa.as(()) // don't use

  def asUnit_v3[A](ioa: IO[A]): IO[Unit] = ioa.void

  def sum(n: Int): Int = {
    if (n <= 0) 0
    else n + sum(n-1)
  }

  def sumIO(n: Int): IO[Int] = {
    if (n <= 0) IO(0)
    else for {
      num <- IO(n)
      sum <- sumIO(n - 1)
    } yield sum + num
  }

  def fibonacci(n: Int): IO[BigInt] = {
    if (n == 0 || n == 1) IO(n)
    else for {
      last <- fibonacci(n-1)
      prev <- fibonacci(n-2)
    } yield last + prev

  }


  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global // "platform"

    // "end of the world"
    println(aDelayedIO.unsafeRunSync())
    printedMeaningOfLife.unsafeRunSync()

    // smallProgram.unsafeRunSync()

    println(combineMeaningOfLife.unsafeRunSync())

    // smallProgram_v2().unsafeRunSync()

//    println(sequenceTakeLast(IO { println("IO A"); 42 }, IO { println("IO B"); 17 }).unsafeRunSync()) // 17
//    println(sequenceTakeFirst(IO { println("IO A"); 42 }, IO { println("IO B"); 17 }).unsafeRunSync()) // 42

//    println(forever_v2(IO {println("forever"); Thread.sleep(100)}).unsafeRunSync())

//      println(sumIO(20_000).unsafeRunSync()) // 200010000
//      println(sum(20_000)) // StackOverflow!

//      (1 to 100).foreach(i => println(fibonacci(i).unsafeRunSync()))

  }
}
