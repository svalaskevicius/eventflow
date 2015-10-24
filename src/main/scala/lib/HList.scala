package lib

sealed trait HList

final case class HCons[H, T <: HList](head: H, tail: T) extends HList

trait HNil extends HList
object HNil extends HNil

object HList {
  type ::[H, T <: HList] = HCons[H, T]
  val :: = HCons

  implicit class HListOps[HL <: HList](val hl: HL) {
    def ::[H](v: H) = HCons(v, hl)
  }

  sealed trait Mapper[S, HL <: HList, B, Out <: HList] {
    def apply(hl: HL, f: S => B): Out
  }

  object Mapper extends LowPrioMapper {
    implicit def typeMatchedMapper[S, H, T <: HList, B, tOut <: HList](implicit ev: H =:= S, iTail: Mapper[S, T, B, tOut]): Mapper[S, H :: T, B, B :: tOut] =
      new Mapper[S, H :: T, B, B :: tOut] {
        def apply(hc: H :: T, f: S => B) = f(ev(hc.head)) :: iTail(hc.tail, f)
      }
  }

  trait LowPrioMapper extends LowestPrioMapper {
    implicit def iteratedMapper[S, H, T <: HList, B, tOut <: HList](implicit iTail: Mapper[S, T, B, tOut]): Mapper[S, H :: T, B, H :: tOut] =
      new Mapper[S, H :: T, B, H :: tOut] {
        def apply(hc: H :: T, f: S => B) = hc.head :: iTail(hc.tail, f)
      }
  }

  trait LowestPrioMapper {
    implicit def tipNotFound[S, HC <: HNil, B]: Mapper[S, HC, B, HNil.type] =
      new Mapper[S, HC, B, HNil.type] {
        def apply(hc: HC, f: S => B) = HNil
      }
  }

  def map[S, B, T <: HList, tOut <: HList](hc: T, f: S => B)(implicit ev: Mapper[S, T, B, tOut]): tOut = ev(hc, f)
  // --

  sealed trait Extractor[S, HL <: HList] {
    def apply(hl: HL): S
  }
  object Extractor extends LowPrioExtractor {
    implicit def typeMatchedExtractor[S, H, T <: HList](implicit ev: H =:= S): Extractor[S, H :: T] =
      new Extractor[S, H :: T] {
        def apply(hc: H :: T) = ev(hc.head)
      }
  }
  trait LowPrioExtractor {
    implicit def iteratedExtractor[S, H, T <: HList](implicit iTail: Extractor[S, T]): Extractor[S, H :: T] =
      new Extractor[S, H :: T] {
        def apply(hc: H :: T) = iTail(hc.tail)
      }
  }
  def extract[S, T <: HList](hc: T)(implicit ev: Extractor[S, T]) = ev(hc)
  // --
  sealed trait ApplyUpdate1[S, HL <: HList, B] {
    def apply(hl: HL, f: S => (S, B)): (HL, B)
  }

  object ApplyUpdate1 extends LowPrioApplyUpdate1 {
    implicit def typeMatchedApplyUpdate1[S, H, T <: HList, B](implicit evSH: S =:= H, evHS: H =:= S): ApplyUpdate1[S, H :: T, B] =
      new ApplyUpdate1[S, H :: T, B] {
        def apply(hc: H :: T, f: S => (S, B)) = {
          val r = f(evHS(hc.head))
          (evSH(r._1) :: hc.tail, r._2)
        }
      }
  }
  trait LowPrioApplyUpdate1 {
    implicit def iteratedApplyUpdate1[S, H, T <: HList, B](implicit iTail: ApplyUpdate1[S, T, B]): ApplyUpdate1[S, H :: T, B] =
      new ApplyUpdate1[S, H :: T, B] {
        def apply(hc: H :: T, f: S => (S, B)) = {
          val r = iTail(hc.tail, f)
          (hc.head :: r._1, r._2)
        }
      }
  }
  def applyUpdate1[S, B, T <: HList](hc: T, f: S => (S, B))(implicit ev: ApplyUpdate1[S, T, B]) = ev(hc, f)
}

