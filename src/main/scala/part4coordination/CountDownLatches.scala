package part4coordination

import cats.effect.kernel.Resource
import cats.effect.std.CountDownLatch
import cats.effect.{IO, IOApp}

import scala.concurrent.duration.*
import scala.language.postfixOps
import utils.debugLog
import cats.syntax.parallel.*

import java.io.{File, FileWriter}
import scala.io.Source
import cats.syntax.traverse.*

import scala.util.Random

object CountDownLatches extends IOApp.Simple{

  /*
    CountDownLatches are a coordination primitive initialized with a count
    All fibers calling await() on the CountDownLatch are semantically blocked.
    when the internal count of the latch reaches 0 (via release() calls from other fibers), all waiting fibers are unblocked
   */

  def announcer(latch: CountDownLatch[IO]): IO[Unit] = for {
    _ <- IO("Starting race shortly...").debugLog >> IO.sleep(2 seconds)
    _ <- IO("5...").debugLog >> IO.sleep(1 second)
    _ <- latch.release
    _ <- IO("4...").debugLog >> IO.sleep(1 second)
    _ <- latch.release
    _ <- IO("3...").debugLog >> IO.sleep(1 second)
    _ <- latch.release
    _ <- IO("2...").debugLog >> IO.sleep(1 second)
    _ <- latch.release
    _ <- IO("1...").debugLog >> IO.sleep(1 second)
    _ <- latch.release
    _ <- IO("GO GO GO!").debugLog
  } yield ()

  def createRunner(id: Int, latch: CountDownLatch[IO]): IO[Unit] = for {
    _ <- IO(s"[runner $id] waiting for signal...").debugLog
    _ <- latch.await // block this fiber until the count reaches 0
    _ <- IO(s"[runner $id] RUNNING!").debugLog
  } yield ()

  def sprint(): IO[Unit] = for {
    latch <- CountDownLatch[IO](5)
    announcerFib <- announcer(latch).start
    _ <- (1 to 10).toList.parTraverse(id => createRunner(id, latch))
    _ <- announcerFib.join
  } yield ()

  /*
    Exercise: simulate a file downloader on multiple threads
   */

  object FileServer {
    val fileChunksList = Array (
      "I love scala",
      "Cats Effect seems quite fun",
      "Never would I have thought I would do low-level concurrency with pure FP"
    )

    def getNumChunks: IO[Int] = IO(fileChunksList.length)
    def getFileChunk(n : Int): IO[String] = IO(fileChunksList(n))
  }

  def writeToFile(path: String, contents: String): IO[Unit] = {
    val fileResource = Resource.make(IO(new FileWriter(new File(path))))(writer => IO(writer.close()))
    fileResource.use { writer =>
      IO(writer.write(contents))
    }
  }

  def appendFileContents(fromPath: String, toPath: String): IO[Unit] = {
    val compositeResource = for {
      reader <- Resource.make(IO(Source.fromFile(fromPath)))(source => IO(source.close()))
      writer <- Resource.make(IO(FileWriter(new File(toPath), true)))(writer => IO(writer.close()))
    } yield (reader, writer)

    compositeResource.use {
      case (reader, writer) => IO(reader.getLines().foreach(writer.write))
    }
  }

  def createFileDownloaderTask(id: Int, latch: CountDownLatch[IO], filename: String, destFolder: String): IO[Unit] = for {
    _ <- IO(s"[task $id] downloading chunk...").debugLog
    _ <- IO.sleep((Random().nextDouble() * 1000).toInt millis)
    chunk <- FileServer.getFileChunk(id)
    _ <- writeToFile(s"$destFolder/$filename.part$id", chunk)
    _ <- IO(s"[task $id] chunk download chunk").debugLog
    _ <- latch.release
  } yield ()

  /*
    - call the file server API and get the number of chunks (n)
    - start a CountDownLatch(n)
    - start n fibers which download a chunk of file (use the file's server's download chunk API)
    - block on the latch until each task has finished
    - after all chunks are done, stitch the file together under the same file on disk
   */

  def downloadFile(filename: String, destFolder: String): IO[Unit] = for {
    n <- FileServer.getNumChunks
    latch <- CountDownLatch[IO](n)
    _ <- IO(s"Download started on $n fibers.").debugLog
    contentsList <- (0 until n).toList.parTraverse { id => createFileDownloaderTask(id, latch, filename, destFolder) }
    _ <- latch.await
    _ <- (0 until n).toList.traverse { id => appendFileContents(s"$destFolder/$filename.part$id", s"$destFolder/$filename") }
  } yield ()

  override def run: IO[Unit] = downloadFile("myScalafile.txt", "src/main/resources")
}
