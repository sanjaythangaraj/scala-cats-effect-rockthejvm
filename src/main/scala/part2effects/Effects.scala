package part2effects

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn

object Effects {

  // pure functional programming
  // substitution
  def combine(a: Int, b: Int): Int = a + b
  val five = combine(2, 3)
  val five_v2 = 2 + 3
  val five_v3 = 5

  // referential transparency = can replace an expression with its value
  // as many times as we want without changing behavior

  // example: print to the console
  val printSomething: Unit = println("Cats Effect")
  val printSomething_v2: Unit = () // not the same

  // example: modifying a variable
  var a = 0
  val changingVar: Unit = a += 1
  val changingVar_v2: Unit = () // not the same

  // side effects are inevitable for useful programs

  // Effect Types Properties:
  // - type signature describes the kind of calculation
  // - type signature describes the value that wil be calculated
  // - when side effects are needed, effect construction is separate from effect execution

  // example: Option
  // - possibly absent value
  // - computes a value of type A, if it exists
  // - side effects not needed
  val anOption: Option[Int] = Option(42)

  // example: Future
  // - an asynchronous computation
  // - computes a value of type A, if successful
  // side effect required (thread allocation/scheduling), execution is not separate from construction
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
  val aFuture: Future[Int] = Future(42)

  // example: MyIO
  // - any computation that might produce side effects
  // - computes a value of type A, if successful
  // - side effects might be required for the evaluation of () => A,
  //   the creation of MyIO does the produce the side effects on construction
  case class MyIO[A](unsafeRun: () => A) {
    def map[B](f: A => B): MyIO[B] =
      MyIO(() => f(unsafeRun()))

    def flatMap[B](f: A => MyIO[B]): MyIO[B] =
      MyIO(() => f(unsafeRun()).unsafeRun())
  }

  val anIO: MyIO[Int] = MyIO(() => {
    println("welcome")
    40
  }).map(_ + 2).flatMap(x =>
    MyIO(() => {
      println("bye")
      x
    })
  )

  /*
    Exercise
      1. An IO which returns the current time of the system
      2. An IO which measures the duration of a computation
      3. An IO which prints something to the console
      4. An IO which reads a line (string) from the std input
   */

  // 1
  val clock: MyIO[Long] = MyIO(() => System.currentTimeMillis())

  // 2
//  def measure[A](computation: MyIO[A]): MyIO[Long] = MyIO(() => {
//    val startTime = clock.unsafeRun()
//    val result = computation.unsafeRun()
//    val endTime = clock.unsafeRun()
//    endTime - startTime
//  })

  def measure[A](computation: MyIO[A]): MyIO[Long] = for {
    startTime <- clock
    _ <- computation
    endTime <- clock
  } yield endTime - startTime

  /*
    clock.flatMap(startTime => computation.flatMap(_ => clock.map(endTime => endTime - startTime)))

    clock.map(endTime => endTime - startTime)
      = clock.map(f)
      = MyIO(() => f(clock.unsafeRun()))
      = MyIO(() => clock.unsafeRun() - startTime)
      = MyIO(() => System.currentTimeMillis() - startTime)

    computation.flatMap(_ => clock.map(endTime - startTime))
      = computation.flatMap(f)
      = MyIO(() => f(computation.unsafeRun()).unsafeRun())
      = MyIO(() => f(___COMPUTATION_RESULT___).unsafeRun())
      = MyIO(() => MyIO(() => System.currentTimeMillis - startTime).unsafeRun())
      = MyIO(() => System.currentTimeMillis_after_computation() - startTime)

    clock.flatMap(startTime => computation.flatMap(_ => clock.map(endTime => endTime - startTime)))
      = clock.flatMap(f)
      = MyIO(() => f(clock.unsafeRun()).unsafeRun())
      = MyIO(() => MyIO(() =>  System.currentTimeMillis_after_computation() - System.currentTimeMillis()).unsafeRun())
      = MyIO(() => System.currentTimeMillis_after_computation() - System.currentTimeMillis())
      = MyIO(() => timeTakenInMillis)
   */

  def testTimeIO(): Unit = {
    val test = measure(MyIO(() => Thread.sleep(1000)))
    println(test.unsafeRun())
  }

  // 3
  def putLine(line: String): MyIO[Unit] = MyIO(() => println(line))

  // 4
  val read: MyIO[String] = MyIO(() => StdIn.readLine())

  def testConsole(): Unit = {
    val program: MyIO[Unit] = for {
      line1 <- read
      line2 <- read
      _ <- putLine(line1 + line2)
    } yield ()

    program.unsafeRun()
  }

  def main(args: Array[String]): Unit = {
    println(anIO.unsafeRun())

    testTimeIO()
    testConsole()
  }
}
