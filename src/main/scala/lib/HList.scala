package lib

import cats.~>
import shapeless._

import scala.language.higherKinds

object HList {

  sealed trait KMapper[S[_], HL <: HList, B[_], Out <: HList] {
    def apply(hl: HL, f: S ~> B): Out
  }

  object KMapper extends LowPrioMapper {
    implicit def iteratedMapper[S[_], H, T <: HList, B[_], O, tOut <: HList](implicit iTail: KMapper[S, T, B, tOut]): KMapper[S, S[O] :: T, B, B[O] :: tOut] =
      new KMapper[S, S[O] :: T, B, B[O] :: tOut] {
        def apply(hc: S[O] :: T, f: S ~> B) = f(hc.head) :: iTail(hc.tail, f)
      }
  }

  trait LowPrioMapper {
    implicit def tipNotFound[S[_], HC <: HNil, B[_]]: KMapper[S, HC, B, HNil.type] =
      new KMapper[S, HC, B, HNil.type] {
        def apply(hc: HC, f: S ~> B) = HNil
      }
  }

  def kMap[S[_], B[_], T <: HList, tOut <: HList](hc: T, f: S ~> B)(implicit ev: KMapper[S, T, B, tOut]): tOut = ev(hc, f)
}

