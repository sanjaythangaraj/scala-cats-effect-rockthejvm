package part2effects

import cats.Traverse
import cats.effect.{IO, IOApp}

import java.util.concurrent.Executors
import scala.util.Random
import scala.concurrent.{ExecutionContext, Future}

object IOTraversal extends IOApp.Simple {

  given ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

  def heavyComputation(string: String): Future[Int] = Future {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").length
  }

  val workLoad: List[String] = List("I quite like CE", "Scala is great", "looking forward to some awesome stuff")

  def clunkyFutures(): Unit = {
    val futures: List[Future[Int]] = workLoad.map(heavyComputation)
    // Future[List[Int]] would be hard to obtain
    futures.foreach(_.foreach(println))
  }

  def traverseFutures(): Unit = {
    // traverse
    import cats.Traverse
    val singleFuture: Future[List[Int]] = Traverse[List].traverse(workLoad)(heavyComputation)
    singleFuture.foreach(println)
  }

  import utils._

  def computeAsIO(string: String): IO[Int] = IO {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").length
  }.debugLog

  val ios: List[IO[Int]] = workLoad.map(computeAsIO)
  val singleIOs: IO[List[Int]] = Traverse[List].traverse(workLoad)(computeAsIO)

  // parallel traversal
  import cats.syntax.parallel._

  val parallelSingleIO: IO[List[Int]] = workLoad.parTraverse(computeAsIO)

  /*
    Exercises
   */

  import cats.syntax.traverse._

  def sequence[A](listOfIOs: List[IO[A]]): IO[List[A]] = listOfIOs.traverse(identity)

  // hard version
  def sequence_v2[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] = ios.traverse(identity)

  // parallel version
  def parSequence[A](listOfIOs: List[IO[A]]): IO[List[A]] = listOfIOs.parTraverse(identity)

  // hard version
  def parSequence_v2[F[_]: Traverse, A](ios: F[IO[A]]): IO[F[A]] = ios.parTraverse(identity)

  // existing sequence API
  val singleIOs_v2: IO[List[Int]] = ios.sequence
  val parallelSingleIO_v2: IO[List[Int]] = ios.parSequence


  override def run: IO[Unit] = parallelSingleIO_v2.map(_.sum).debugLog.void
}
