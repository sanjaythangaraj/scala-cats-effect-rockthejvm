package concurrency

import cats.effect.{IO, IOApp}

import scala.concurrent.duration._

import scala.language.postfixOps

object CancelingIOs extends IOApp.Simple{

  import utils.debugLog

  /*
    Cancelling IOs
      - fib.cancel
      - IO.race & other APIs
      - manual cancellation
   */
    val chainOfIOs: IO[Int] = IO("waiting").debugLog >> IO.canceled >> IO(42).debugLog

    // uncancelable
    // example: online store, payment processor
    // payment process must NOT be canceled
    val specialPaymentSystem: IO[String] = (
      IO("Payment running, don't cancel me...").debugLog >>
      IO.sleep(1 second) >>
      IO("Payment completed").debugLog
    ).onCancel(IO("MEGA CANCEL OF DOOM!").debugLog.void)

    val cancellationOfDoom: IO[Unit] = for {
      fib <- specialPaymentSystem.start
      _ <- IO.sleep(500 millis) >> fib.cancel
      _ <- fib.join
    } yield ()
    // debugLogs:
    //  [io-compute-1] Payment running, don't cancel me...
    //  [io-compute-0] MEGA CANCEL OF DOOM!

    val atomicPayment = IO.uncancelable(_ => specialPaymentSystem) // "making"
    val atomicPayment_v2 = specialPaymentSystem.uncancelable // same

    val noCancellationOfDoom: IO[Unit] =  for {
      fib <- atomicPayment.start
      _ <- IO.sleep(500 millis) >> IO("attempting cancellation...").debugLog >> fib.cancel
      _ <- fib.join
    } yield ()
    // debugLogs:
    //  [io-compute-1] Payment running, don't cancel me...
    //  [io-compute-5] attempting cancellation...
    //  [io-compute-1] Payment completed


    /*
      The uncancelable API is more complex and more general.
      It takes a function from Poll[IO] to IO.
      Ihe poll object can be used to mark sections within the returned effect which CAN BE CANCELED
     */

    /*
      Example: authentication service. Has two parts:
        - input password, can be canceled, because otherwise we might block indefinitely on user input
        - verify password, CANNOT be canceled once it's started
     */
    val inputPassword = IO("input password:").debugLog >> IO("(typing password)").debugLog >> IO.sleep(2 seconds) >> IO("RockTheJVM1!")
    val verifyPassword = (pw: String) => IO("verifying...").debugLog >> IO.sleep(2 seconds) >> IO(pw == "RockTheJVM1!")

    val authFlow: IO[Unit] = IO.uncancelable { poll =>
      for {
        pw <- poll(inputPassword).onCancel(IO("Authentication timed out. Try again later.").debugLog.void) // this is cancelable
        verified <- verifyPassword(pw) // this is NOT cancelable
        _ <- if (verified) IO("Authentication successful.").debugLog else IO("Authentication failed.").debugLog // this is NOT cancelable
      } yield ()
    }

    val authProgram = for {
      authFib <- authFlow.start
      _ <- IO.sleep(3 seconds) >> IO("Authentication timeout, attempting cancel...").debugLog >> authFib.cancel
      _ <-authFib.join
    } yield ()

    /* uncancelable calls are MASKS which suppress cancellation.
      poll calls are "gaps opened" in the uncancelable region.
     */

    /*
      Exercises
     */

    // 1
    val cancelBeforeMol = IO.canceled >> IO(42).debugLog
    val uncancelableMol = IO.uncancelable(_ => IO.canceled >> IO(42).debugLog) // [io-compute-1] 42
    // uncancelable will eliminate all cancel points

  // 2
  val invincibleAuthProgram = for {
    authFib <- IO.uncancelable(_ => authFlow).start
    _ <- IO.sleep(1 seconds) >> IO("Authentication timeout, attempting cancel...").debugLog >> authFib.cancel
    _ <- authFib.join
  } yield ()
  // debugLogs:
  //  [io-compute-3] input password:
  //  [io-compute-3] (typing password)
  //  [io-compute-5] Authentication timeout, attempting cancel...
  //  [io-compute-3] verifying...
  //  [io-compute-3] Authentication successful.

  // wrapping an uncancelable IO with cancelable regions in an uncancelable IO will make the cancelable regions uncancelable.

  // 3
  def threeStepProgram: IO[Unit] = {
    val sequence = IO.uncancelable { poll =>
      poll(IO("cancelable").debugLog >> IO.sleep(1 second) >> IO("cancelable end").debugLog) >>
      IO("uncancelable").debugLog >> IO.sleep(1 second) >> IO("uncancelable end").debugLog >>
      poll(IO("second cancelable").debugLog >> IO.sleep(1 second) >> IO("second cancelable end").debugLog)
    }

    for {
      fib <- sequence.start
      _ <- IO.sleep(1500 millis) >> IO("CANCELING").debugLog >> fib.cancel
      _ <- fib.join
    } yield ()
  }
  // debugLogs:
  // [io-compute-6] cancelable
  //  [io-compute-6] cancelable end
  //  [io-compute-6] uncancelable
  //  [io-compute-0] CANCELING
  //  [io-compute-6] uncancelable end

  // cancelation signal to an uncancelable region will be handled by the first cancelable region in sequence if present


  override def run: IO[Unit] = threeStepProgram
}
