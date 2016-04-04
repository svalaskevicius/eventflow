package lib

import cats.Monad

import scala.language.higherKinds

object foldM {
  //  (a -> b -> m a) -> a -> [b] -> m a
  def apply[A, B, M[_]: Monad](f: A => B => M[A])(a: A)(bs: TraversableOnce[B]): M[A] = {
    val ev = implicitly[Monad[M]]
    bs.foldLeft(ev.pure(a))((a_, b) => ev.flatMap(a_)(a__ => f(a__)(b)))
  }
}
