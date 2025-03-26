package concurrency

import cats.effect.{IO, IOApp}

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import utils.debugLog

import scala.language.postfixOps

object AsyncIOs extends IOApp.Simple{

  // IOs can run asynchronously on fibers, without having to manually manage the fiber lifecycle

  val threadPool = Executors.newFixedThreadPool(8)
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(threadPool)

  def computeMeaningOfLife: Int = {
    Thread.sleep(1000)
    println(s"[${Thread.currentThread().getName}] computing the meaning of life on some other thread...")
    42
  }

  def computeMeaningOfLifeEither: Either[Throwable, Int] = Try {
   computeMeaningOfLife
  }.toEither

  type Callback[A] = Either[Throwable, A] => Unit

  def computeMolOnThreadPool(): Unit = {
    threadPool.execute(() => computeMeaningOfLifeEither)
  }

  // lift computation to an IO
  // async is a FFI
  val asyncMolIO: IO[Int] = IO.async_ { cb => // CE thread blocks (semantically) until cb is invoked
    threadPool.execute { () => // computation not managed by CE
      val result = computeMeaningOfLifeEither
      cb(result) // CE thread is notified with the result
    }
  }
  // debugLogs:
  //  [pool-1-thread-1] computing the meaning of life on some other thread...
  //  [io-compute-0] 42

  /*
    Exercise
      1. lift an async computation on ec to an IO
      2. lift an async computation as a Future, to an IO
      3. a never-ending IO
   */

  // 1
  def asyncToIO[A](computation: () => A)(implicit executionContext: ExecutionContext): IO[A] =
    IO.async_ { cb =>
      executionContext.execute { () =>
        val result = Try{
          computation()
        }.toEither
        cb(result)
      }
    }

  lazy val molFuture = Future { computeMeaningOfLife }

  // 2
  def convertFutureToIO[A](future: => Future[A]): IO[A] =
    IO.async_ {cb => future.onComplete { tryResult =>
      cb(tryResult.toEither)
    }}

  // 3
  val neverEndingIO = IO.async_(_ => ())
  val neverEndingIO_v2 = IO.never

  /*
    FULL ASYNC CALL
   */

  import scala.concurrent.duration._

  def demoAsyncCancellation = {
    val asyncIO = IO.async { cb =>
      /*
        finalizer in case computation gets canceled
        finalizers are of type IO[Unit]
        not specifying finalizer => Option[IO[Unit]]
        creating option is an effect => IO[Option[IO[Unit]]]
       */
      // return IO[Option[IO[Unit]]]
      IO {
        threadPool.execute { () =>
          val result = computeMeaningOfLifeEither
          cb(result)
        }
      }.as(Some(IO("Cancelled").debugLog.void))

    }

    for {
      fib <- asyncIO.start
      _ <- IO.sleep(500 millis) >> IO("canceling").debugLog >> fib.cancel
      _ <- fib.join
    } yield ()
  }
  // debugLogs:
  //  [io-compute-0] canceling
  //  [io-compute-4] Cancelled
  //  [pool-1-thread-1] computing the meaning of life on some other thread...


//  override def run: IO[Unit] = asyncToIO[Int] {() => computeMeaningOfLife}.debugLog >> IO(threadPool.shutdown())

//  override def run: IO[Unit] = convertFutureToIO(molFuture).debugLog >> IO(threadPool.shutdown())

//  override def run: IO[Unit] = IO.fromFuture(IO(molFuture)).debugLog >> IO(threadPool.shutdown())

  override def run: IO[Unit] = demoAsyncCancellation >> IO(threadPool.shutdown())


}
