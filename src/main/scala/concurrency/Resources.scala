package concurrency

import cats.effect.kernel.Outcome.Canceled
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Errored
import cats.effect.{IO, IOApp, Resource}

import java.io.{File, FileReader}
import java.util.Scanner
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.language.postfixOps

object Resources extends IOApp.Simple{

  import utils._

  // use-case: manage a connection lifecycle
  class Connection(url: String) {
    def open: IO[String] = IO(s"opening connection to $url").debugLog
    def close: IO[String] = IO(s"closing a connection to $url").debugLog
  }

  val asyncFetchURL = for {
    fib <- (new Connection("rockthejvm.com").open *> IO.sleep((Int.MaxValue) seconds)).start
    _ <- IO.sleep(1 second) *> fib.cancel
  } yield ()
  // debugLogs:
  //  [io-compute-3] opening connection to rockthejvm.com
  // problem: leaking resources

  val correctAsyncFetchURL = for {
    conn <- IO(new Connection("rockthejvm.com"))
    fib <- (conn.open *> IO.sleep((Int.MaxValue) seconds)).onCancel(conn.close.void).start
    _ <- IO.sleep(1 second) *> fib.cancel
  } yield ()
  // debugLogs:
  //  [io-compute-0] opening connection to rockthejvm.com
  //  [io-compute-1] closing a connection to rockthejvm.com

  // bracket pattern: someIO.bracket(useResourceCb)(releaseResourceCb)
  val bracketFetchUrl: IO[Unit] = IO(new Connection("rockthejvm.com"))
    .bracket(conn => conn.open *> IO.sleep(Int.MaxValue seconds))(conn => conn.close.void)

  val bracketProgram: IO[Unit] = for {
    fib <- bracketFetchUrl.start
    _ <- IO.sleep(1 second) *> fib.cancel
  } yield ()
  // debugLogs:
  // [io-compute-5] opening connection to rockthejvm.com
  // [io-compute-0] closing a connection to rockthejvm.com

  /*
    Exercise: read the file with the bracket pattern
      - open a scanner
      - read the file line by line, every 100 millis
      - close the scanner
      - if cancelled/throws error, close the scanner
   */

  def openFileScanner(path: String): IO[Scanner] =
    IO(new Scanner(new FileReader(new File(path))))

  def bracketReadFile(path: String): IO[Unit] =
    IO(s"opening file at $path") >>
      openFileScanner(path).bracket { scanner =>
        def readLineByLine: IO[Unit] =
          if (scanner.hasNextLine) IO(scanner.nextLine()).debugLog >> IO.sleep(100 millis) >> readLineByLine
          else IO.unit

        readLineByLine
      } { scanner =>
        IO(s"closing file at $path").debugLog >> IO(scanner.close())
      }


  /* Resources */

  def connFromConfig(path: String): IO[Unit] =
    openFileScanner(path)
      .bracket { scanner =>
        IO(new Connection(scanner.nextLine())).bracket { conn =>
          conn.open.debugLog >> IO.never
        } { conn =>
          conn.close.debugLog.void
        }
      } { scanner =>
        IO("closing file").debugLog >> IO(scanner.close())
      }
  // nesting resources

  val connectionResource = Resource.make(IO(new Connection("rockthejvm.com")))(conn => conn.close.void)
  // ... at a later part of your code
  val resourceFetchUrl: IO[Unit] = for {
    fib <- connectionResource.use(conn => conn.open >> IO.never).start
    _ <- IO.sleep(1 second) >> fib.cancel
  } yield ()

  // resources are equivalent to brackets

  val simpleResource = IO("some resource")
  val usingResource: String => IO[String] = string => IO(s"using the string: $string").debugLog
  val releaseResource: String => IO[Unit] = string => IO(s"finalizing the string: $string").debugLog.void

  val usingResourceWithBracket = simpleResource.bracket(usingResource)(releaseResource)
  val usingResourceWithResource = Resource.make(simpleResource)(releaseResource).use(usingResource)

  /*
    Exercise
      1.read a text file with one Line every 100 millis, using Resource
   */

  def getResourceFromFile(path: String) = Resource.make(openFileScanner(path)) { scanner =>
    IO("closing file").debugLog >> IO(scanner.close())
  }

  def resourceReadFile(path: String): IO[Unit] = {
    IO(s"opening file at $path") >>
    getResourceFromFile(path).use { scanner =>
      def readLineByLine: IO[Unit] =
        if (scanner.hasNextLine) IO(scanner.nextLine()).debugLog >> IO.sleep(100 millis) >> readLineByLine
        else IO.unit

      readLineByLine
    }
  }

  def cancelReadFile(path: String) = for {
    fib <- resourceReadFile(path).start
    _ <- IO.sleep(2 seconds) >> fib.cancel
  } yield ()

  // nested resources

  def connectionFromConfigResource(path: String) =
    Resource.make(IO("opening file").debugLog >> openFileScanner(path))(scanner => IO("closing file").debugLog >> IO(scanner.close()))
      .flatMap(scanner => Resource.make(IO(new Connection(scanner.nextLine())))(conn => conn.close.void))

  def connectionFromConfigResourcesClean(path: String) = for {
    scanner <- Resource.make(IO("opening file").debugLog >> openFileScanner(path))(scanner => IO("closing file").debugLog >> IO(scanner.close()))
    conn <- Resource.make(IO(new Connection(scanner.nextLine())))(conn => conn.close.void)
  } yield conn

  val openConnection = connectionFromConfigResourcesClean("src/main/resources/connection.txt").use(conn => conn.open >> IO.never)

  val canceledConnection = for {
    fib <- openConnection.start
    _ <- IO.sleep(1 second) >> IO("cancelling!").debugLog >> fib.cancel
  } yield ()
  // connection and file will close automatically

  // finalizers to regular effects
  val ioWithFinalizer = IO("some resource").debugLog.guarantee(IO("freeing resource").debugLog.void)
  val ioWithFinalizer_v2 = IO("some resource").debugLog.guaranteeCase {
    case Succeeded(fa) => fa.flatMap(result => IO(s"releasing resources: $result").debugLog).void
    case Errored(e) => IO("nothing to release").debugLog.void
    case Canceled() => IO("resource got canceled, releasing what's left").debugLog.void
  }

//  override def run: IO[Unit] = bracketReadFile("src/main/scala/concurrency/Resources.scala")

//  override def run: IO[Unit] = cancelReadFile("src/main/scala/concurrency/Resources.scala")

//  override def run: IO[Unit] = canceledConnection

  override def run: IO[Unit] = ioWithFinalizer_v2.void
}
