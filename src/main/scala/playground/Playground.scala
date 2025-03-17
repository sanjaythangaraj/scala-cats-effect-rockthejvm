package playground

import cats.effect.{IO, IOApp}

object Playground extends IOApp.Simple {

  override def run: IO[Unit] =
    IO.println("Hello World from Cats Effect!")
}