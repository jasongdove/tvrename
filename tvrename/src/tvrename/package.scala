import cats.effect.{IO, Resource}

package object tvrename {

  implicit class IOExtensions[A](io: IO[A]) {
    def asResource: Resource[IO, A] = Resource.liftF(io)
  }
}
