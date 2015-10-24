package lib
import scala.language.higherKinds

sealed trait HList

final case class HCons[H, T <: HList](head : H, tail : T) extends HList

trait HNil extends HList
object HNil extends HNil

// aliases for building HList types and for pattern matching
object HList {
  type ::[H, T <: HList] = HCons[H, T]
  val :: = HCons

  implicit class HListOps[HL <: HList](val hl: HL) {
    def ::[H](v : H) = HCons(v, hl)
  }




  sealed trait Mapper[S, HL <: HList, B, Out <: HList] {
    def apply(hl: HL, f: S => B): Out
  }


  object Mapper extends LowPrioMapper {
    implicit def typeMatchedMapper[S, H, T <: HList, B, tOut <: HList](implicit ev: H =:= S, iTail: Mapper[S, T, B, tOut]): Mapper[S, H :: T, B, B :: tOut] =
      new Mapper[S, H :: T, B, B::tOut] {
        def apply(hc: H :: T, f: S => B) = {
          println("apply")
          f(ev(hc.head)) :: iTail(hc.tail, f)
        }
      }
  }

  trait LowPrioMapper extends LowestPrioMapper {
    implicit def iteratedMapper[S, H, T <: HList, B, tOut <: HList](implicit iTail: Mapper[S, T, B, tOut]): Mapper[S, H :: T, B, H :: tOut] =
      new Mapper[S, H :: T, B, H :: tOut] {
        def apply(hc: H :: T, f: S => B) = {
          println("recurse")
          hc.head :: iTail(hc.tail, f)
        }
      }
  }

  trait LowestPrioMapper {
    implicit def tipNotFound[S,  HC <: HNil, B]: Mapper[S, HC, B, HNil.type] =
      new Mapper[S, HC, B, HNil.type] {
        def apply(hc:  HC, f: S => B) = {
          println("end.")
          HNil
        }
      }
  }

  def map[S, B, T <: HList, tOut <: HList](hc: T, f: S => B)(implicit ev: Mapper[S, T, B, tOut]): tOut = ev(hc, f)
  // --
  trait FF[S[_], T[_]] {
    def apply[A](a: S[A]): T[A]
  }
  sealed trait KindMapper[S[_], T[_], HL <: HList, tOut <: HList] {
    def apply(hl: HL, f: FF[S, T]): tOut
  }


  object KindMapper extends LowPrioKindMapper {
    implicit def iteratedKindMapper[A, S[_], TT[_], T <: HList, tOut <: HList](implicit iTail: KindMapper[S, TT, T, tOut]): KindMapper[S, TT, S[A] :: T, TT[A] :: tOut] =
      new KindMapper[S, TT, S[A] :: T, TT[A] :: tOut] {
        def apply(hc: S[A] :: T, f: FF[S, TT]) = {
          println("apply & recurse")
          f(hc.head) :: iTail(hc.tail, f)
        }
      }
  }
  trait LowPrioKindMapper extends LowestPrioKindMapper {
    implicit def iteratedKindMapperNotMatched[S[_], TT[_], H, T <: HList, tOut <: HList](implicit iTail: KindMapper[S, TT, T, tOut]): KindMapper[S, TT, H :: T, H :: tOut] =
      new KindMapper[S, TT, H :: T, H :: tOut] {
        def apply(hc: H :: T, f: FF[S, TT]) = {
          println("recurse")
          hc.head :: iTail(hc.tail, f)
        }
      }
  }
  trait LowestPrioKindMapper {
    implicit def tipNotFound[S[_], TT[_], HC <: HNil]: KindMapper[S, TT, HC, HNil.type] =
      new KindMapper[S, TT, HC, HNil.type] {
        def apply(hc:  HC, f: FF[S, TT]) = {
          println("end.")
          HNil
        }
      }
  }

  def kmap[S[_], TT[_], T <: HList, tOut <: HList](hc: T, f: FF[S, TT])(implicit ev: KindMapper[S, TT, T, tOut]): tOut = ev(hc, f)
  // --
  trait FF2[S[_, _ <: HList], T[_, _ <: HList]] {
    def apply[A, B <: HList](a: S[A, B]): T[A, B]
  }
  sealed trait KindMapper2[S[_, _ <: HList], T[_, _ <: HList], HL <: HList, tOut <: HList] {
    def apply(hl: HL, f: FF2[S, T]): tOut
  }


  object KindMapper2 extends LowPrioKindMapper2 {
    implicit def iteratedKindMapper[A, B <: HList, S[_, _ <: HList], TT[_, _ <:HList], T <: HList, tOut <: HList](implicit iTail: KindMapper2[S, TT, T, tOut]): KindMapper2[S, TT, S[A, B] :: T, TT[A, B] :: tOut] =
      new KindMapper2[S, TT, S[A, B] :: T, TT[A, B] :: tOut] {
        def apply(hc: S[A, B] :: T, f: FF2[S, TT]) = {
          println("apply & recurse")
          f(hc.head) :: iTail(hc.tail, f)
        }
      }
  }
  trait LowPrioKindMapper2 extends LowestPrioKindMapper2 {
    implicit def iteratedKindMapperNotMatched[S[_, _ <: HList], TT[_, _ <: HList], H, T <: HList, tOut <: HList](implicit iTail: KindMapper2[S, TT, T, tOut]): KindMapper2[S, TT, H :: T, H :: tOut] =
      new KindMapper2[S, TT, H :: T, H :: tOut] {
        def apply(hc: H :: T, f: FF2[S, TT]) = {
          println("recurse")
          hc.head :: iTail(hc.tail, f)
        }
      }
  }
  trait LowestPrioKindMapper2 {
    implicit def tipNotFound[S[_, _ <: HList], TT[_, _ <: HList], HC <: HNil]: KindMapper2[S, TT, HC, HNil.type] =
      new KindMapper2[S, TT, HC, HNil.type] {
        def apply(hc:  HC, f: FF2[S, TT]) = {
          println("end.")
          HNil
        }
      }
  }

  def kmap2[S[_, _ <: HList], TT[_, _ <: HList], T <: HList, tOut <: HList](hc: T, f: FF2[S, TT])(implicit ev: KindMapper2[S, TT, T, tOut]): tOut = ev(hc, f)
  // --

  sealed trait Extractor[S, HL <: HList] {
    def apply(hl: HL): S
  }
  object Extractor extends LowPrioExtractor {
    implicit def typeMatchedExtractor[S, H, T <: HList](implicit ev: H =:= S): Extractor[S, H :: T] =
      new Extractor[S, H :: T] {
        def apply(hc: H :: T) = {
          println("apply")
          ev(hc.head)
        }
      }
  }
  trait LowPrioExtractor {
    implicit def iteratedExtractor[S, H, T <: HList](implicit iTail: Extractor[S, T]): Extractor[S, H :: T] =
      new Extractor[S, H :: T] {
        def apply(hc: H :: T) = {
          println("recurse")
          iTail(hc.tail)
        }
      }
  }
  def extract[S, T <: HList](hc: T)(implicit ev: Extractor[S, T]) = ev(hc)
  // --

  sealed trait Extractor2[S, -HL <: HList] {
    def apply(hl: HL): Option[S]
  }
  object Extractor2 extends LowPrioExtractor2 {
    implicit def typeMatchedExtractor[S, H, T <: HList](implicit ev: H =:= S): Extractor2[S, H :: T] =
      new Extractor2[S, H :: T] {
        def apply(hc: H :: T) = {
          println("apply e2")
          Some(ev(hc.head))
        }
      }
  }
  trait LowPrioExtractor2 extends LowestPrioExtractor2 {
    implicit def iteratedExtractor[S, H, T <: HList](implicit iTail: Extractor2[S, T]): Extractor2[S, H :: T] =
      new Extractor2[S, H :: T] {
        def apply(hc: H :: T) = {
          println("recurse e2")
          iTail(hc.tail)
        }
      }
  }
  trait LowestPrioExtractor2 {
    implicit def missingExtractor[S, HC <: HNil]: Extractor2[S, HC] =
      new Extractor2[S, HC] {
        def apply(hc: HC): Option[S] = {
          println("missing e2 " + hc)
          None
        }
      }
  }
  def extract2[S, T <: HList](hc: T)(implicit ev: Extractor2[S, T]) = ev(hc)
  // --
  sealed trait ApplyUpdate1[S, HL <: HList, B] {
    def apply(hl: HL, f: S => (S, B)): (HL, B)
  }

  object ApplyUpdate1 extends LowPrioApplyUpdate1 {
    implicit def typeMatchedApplyUpdate1[S, H, T <: HList, B](implicit evSH: S =:= H, evHS: H =:= S): ApplyUpdate1[S, H :: T, B] =
      new ApplyUpdate1[S, H :: T, B] {
        def apply(hc: H :: T, f: S => (S, B)) = {
          println("apply")
          val r = f(evHS(hc.head))
          (evSH(r._1) :: hc.tail, r._2)
        }
      }
  }
  trait LowPrioApplyUpdate1 {
    implicit def iteratedApplyUpdate1[S, H, T <: HList, B](implicit iTail: ApplyUpdate1[S, T, B]): ApplyUpdate1[S, H :: T, B] =
      new ApplyUpdate1[S, H :: T, B] {
        def apply(hc: H :: T, f: S => (S, B)) = {
          println("recurse")
          val r = iTail(hc.tail, f)
          (hc.head :: r._1, r._2)
        }
      }
  }
  def applyUpdate1[S, B, T <:HList](hc: T, f: S=>(S, B))(implicit ev: ApplyUpdate1[S, T, B]) = ev(hc, f)
}


