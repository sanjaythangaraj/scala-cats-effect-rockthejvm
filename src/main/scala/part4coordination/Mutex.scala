package part4coordination

import cats.effect.kernel.Deferred
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Canceled
import cats.effect.{IO, IOApp, Ref}
import cats.syntax.parallel.*

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Random
import utils.debugLog

import scala.collection.immutable.Queue

abstract class Mutex {
  def acquire: IO[Unit]
  def release: IO[Unit]
}

object Mutex {

  type Signal = Deferred[IO, Unit]
  case class State(locked: Boolean, waiting: Queue[Signal])

  val unlocked: State = State(locked = false, Queue())

  def createSignal: IO[Signal] = Deferred[IO, Unit]

  def createMutexWithCancellation(state: Ref[IO, State]) = new Mutex {

    override def acquire: IO[Unit] = IO.uncancelable { poll =>

      createSignal flatMap { signal =>

        val cleanup = state.modify {
          case State(locked, queue) =>
            val newQueue = queue.filterNot(_ eq signal)
            State(locked, newQueue) -> release
        }.flatten

        state.modify {
          case State(false, _) => (State(locked = true, Queue()), IO.unit)
          case State(true, queue) => (State(locked = true, queue.enqueue(signal)), poll(signal.get).onCancel(cleanup))
        }.flatten
      }
    }

    override def release: IO[Unit] = state.modify {
      case State(false, _) => unlocked -> IO.unit
      case State(true, queue) =>
        if (queue.isEmpty) unlocked -> IO.unit
        else {
          val (signal, rest) = queue.dequeue
          State(locked = true, rest) -> signal.complete(()).void
        }
    }.flatten
  }

  def createSimpleMutex(state: Ref[IO, State]): Mutex = new Mutex {

    /*
      Change the State of the Ref:
      - if the mutex is currently unlocked, state becomes (true, [])
      - if the mutex is locked, state becomes (true, queue + new signal) AND WAIT ON THAT SIGNAL
     */
    override def acquire: IO[Unit] = createSignal flatMap { signal =>
      state.modify {
        case State(false, _) => (State(locked = true, Queue()), IO.unit)
        case State(true, queue) => (State(locked=true, queue.enqueue(signal)), signal.get)
      }.flatten // modify returns IO[B], our B is IO[Unit], so modify returns IO[IO[Unit]], we need to flatten
    }

    /*
      Change the State of the Ref:
        - if the mutex is unlocked, leave the state unchanged
        - if the mutex is locked,
            - if the queue is empty, unlock the mutex, i.e. state becomes (false, [])
            - if the queue is not empty, take a signal out of the queue and complete it (thereby unblocking a fiber waiting on it)
     */
    override def release: IO[Unit] = state.modify {
      case State(false, _) => unlocked -> IO.unit
      case State(true, queue) =>
        if (queue.isEmpty) unlocked -> IO.unit
        else {
          val (signal, rest) = queue.dequeue
          State(locked = true, rest) -> signal.complete(()).void
        }
    }.flatten
  }

  def create: IO[Mutex] = Ref[IO].of(unlocked).map(createMutexWithCancellation)
}

object MutexPlayground extends IOApp.Simple {

  def criticalTask: IO[Int] = IO.sleep(1 second) >> IO(Random.nextInt(100))

  def createNonLockingTask(id: Int): IO[Int] = for {
    _ <- IO(s"[task $id] working...").debugLog
    res <- criticalTask
    _ <- IO(s"[task $id] got result: $res").debugLog
  } yield res

  def demoNonLockingTasks: IO[List[Int]] = (1 to 10).toList.parTraverse(id => createNonLockingTask(id))

  def createLockingTask(id: Int, mutex: Mutex): IO[Int] = for {
    _ <- IO(s"[task $id] waiting for permission...").debugLog
    _ <- mutex.acquire // blocks if the mutex has been acquired by some other fiber
    // critical section
    _ <- IO(s"[task $id] working...").debugLog
    res <- criticalTask
    _ <- IO(s"[task $id] got result: $res").debugLog
    // critical section end
    _ <- mutex.release
    _ <- IO(s"[task $id] lock removed.").debugLog
  } yield res

  def demoLockingTasks: IO[List[Int]] = for {
    mutex <- Mutex.create
    results <- (1 to 10).toList.parTraverse(id => createLockingTask(id, mutex))
  } yield results
  // only one task will proceed at one time

  def createCancellingTask(id: Int, mutex: Mutex): IO[Int] =
    if (id % 2 == 0) createLockingTask(id, mutex)
    else for {
      fib <- createLockingTask(id, mutex).onCancel(IO(s"[task $id] received cancellation!").debugLog.void).start
      _ <- IO.sleep(2 seconds) >> fib.cancel
      out <- fib.join
      result <- out match {
        case Succeeded(effect) => effect
        case Errored(_) => IO(-1)
        case Canceled() => IO(-2)
      }
    } yield result

  def demoCancellingTasks = for {
    mutex <- Mutex.create
    results <- (1 to 10).toList.parTraverse(id => createCancellingTask(id, mutex))
  } yield results

  override def run: IO[Unit] = demoCancellingTasks.debugLog.void
}
