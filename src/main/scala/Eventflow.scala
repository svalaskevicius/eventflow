
import Cqrs.InMemoryDb._
import Cqrs.Aggregate._
import scala.language.higherKinds

object Eventflow {

  def actions1 = {
    import Domain.Counter._
    import counterAggregate._
    for {
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
      _ <- handleCommand(Increment)
    } yield (())
  }

  def actions2 = {
    import Domain.Counter._
    import counterAggregate._
    for {
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
      _ <- handleCommand(Decrement)
 //     _ <- handleCommand(Decrement)
    } yield (())
  }

  def doorActions1 = {
    import Domain.Door._
    import  doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield (())
  }
  def doorActions2 = {
    import Domain.Door._
    import  doorAggregate._
    for {
      _ <- handleCommand(Close)
      _ <- handleCommand(Lock("my secret"))
      _ <- handleCommand(Unlock("my secret"))
      _ <- handleCommand(Open)
    } yield (())
  }

  sealed trait HList

  final case class HCons[H, T <: HList](head : H, tail : T) extends HList {
    def ::[A](v : A) = HCons(v, this)
  }

  trait HNil extends HList
  object HNil extends HNil {
    def ::[T](v : T) = HCons(v, this)
  }

  // aliases for building HList types and for pattern matching
  object HList {
    type ::[H, T <: HList] = HCons[H, T]
    val :: = HCons




    sealed trait Mapper[S, HL <: HList, B, Out <: HList] {
      def apply(hl: HL, f: S => B): Out
    }


    object Mapper extends LowPrioMapper {
      implicit def typeMatchedMapper[S, H, T <: HList, B, tOut <: HList](implicit ev: H =:= S, iTail: Mapper[S, T, B, tOut]): Mapper[S, H :: T, B, B :: tOut] =
        new Mapper[S, H :: T, B, B::tOut] {
          def apply(hc: H :: T, f: S => B) = {
            println("apply")
            HCons(f(ev(hc.head)) , iTail(hc.tail, f))
          }
        }
    }
    trait LowPrioMapper extends LowestPrioMapper {
      implicit def iteratedMapper[S, H, T <: HList, B, tOut <: HList](implicit iTail: Mapper[S, T, B, tOut]): Mapper[S, H :: T, B, H :: tOut] =
        new Mapper[S, H :: T, B, H :: tOut] {
          def apply(hc: H :: T, f: S => B) = {
            println("recurse")
            HCons(hc.head, iTail(hc.tail, f))
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
            HCons(f(hc.head), iTail(hc.tail, f))
          }
        }
    }
    trait LowPrioKindMapper extends LowestPrioKindMapper {
      implicit def iteratedKindMapperNotMatched[S[_], TT[_], H, T <: HList, tOut <: HList](implicit iTail: KindMapper[S, TT, T, tOut]): KindMapper[S, TT, H :: T, H :: tOut] =
        new KindMapper[S, TT, H :: T, H :: tOut] {
          def apply(hc: H :: T, f: FF[S, TT]) = {
            println("recurse")
            HCons(hc.head, iTail(hc.tail, f))
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
    sealed trait ApplyUpdate1[S, HL <: HList, B] {
      def apply(hl: HL, f: S => (S, B)): (HL, B)
    }

    object ApplyUpdate1 extends LowPrioApplyUpdate1 {
      implicit def typeMatchedApplyUpdate1[S, H, T <: HList, B](implicit evSH: S =:= H, evHS: H =:= S): ApplyUpdate1[S, H :: T, B] =
        new ApplyUpdate1[S, H :: T, B] {
          def apply(hc: H :: T, f: S => (S, B)) = {
            println("apply")
            val r = f(evHS(hc.head))
            (HCons(evSH(r._1), hc.tail), r._2)
          }
        }
    }
    trait LowPrioApplyUpdate1 {
      implicit def iteratedApplyUpdate1[S, H, T <: HList, B](implicit iTail: ApplyUpdate1[S, T, B]): ApplyUpdate1[S, H :: T, B] =
        new ApplyUpdate1[S, H :: T, B] {
          def apply(hc: H :: T, f: S => (S, B)) = {
            println("recurse")
            val r = iTail(hc.tail, f)
            (HCons(hc.head, r._1), r._2)
          }
        }
    }
    def applyUpdate1[S, B, T <:HList](hc: T, f: S=>(S, B))(implicit ev: ApplyUpdate1[S, T, B]) = ev(hc, f)
  }


  def main(args: Array[String])
  {
    import Cqrs.Projection
    import Domain.Counter
    import Domain.Door
    import Domain.CounterProjection._
    import Domain.DoorProjection._

    object DbRunner {
      def empty = DbRunner[HNil, HNil.type](HNil, HNil)
    }

    final case class DbRunner[DBS <: HList, PROJS <: HList](dbs: DBS, projections: PROJS) {
      type ePROJS = PROJS
      import cats.data.Xor
      import cats.state.StateT
      import HList._

      type DbActions[A] = StateT[(DbRunner[DBS, PROJS], Error) Xor ?, DbRunner[DBS, PROJS], A]

      def addDb[E](db: DbBackend[E]): DbRunner[DbBackend[E] :: DBS, PROJS] = copy(dbs = HCons(db, dbs))

      def addProjection[D](proj: Projection[D]): DbRunner[DBS, Projection[D] :: PROJS] = copy(projections = HCons(proj, projections))


      def applyInDb[E, A](actions: EventDatabaseWithFailure[E, A])(db: DbBackend[E]) : (DbBackend[E], Error Xor A) = {
        val r = runInMemoryDb(db)(actions)
        r.fold(e => (db, Xor.left(e)), res => (res._1, Xor.right(res._2)))
      }

      def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor A]): DbActions[A] =
        new DbActions[A](
          Xor.right((runner: DbRunner[DBS, PROJS]) => {
                      val r = applyUpdate1(dbs, applyInDb(actions))
                      r._2.fold(e => Xor.left((runner, e)), a => Xor.right((runner.copy(dbs = r._1), a)))
                    }))

      def db[E, A, S, AA](prev: (AggregateState[S], AA), aggregate: AggregateDef[E, S, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor (AggregateState[S], A)]): DbActions[(AggregateState[S], A)] = {
        val actions = aggregate.run(prev._1)
        db(actions)
      }

      def runProjections[E](runner: DbRunner[DBS, PROJS])(implicit _extr: Extractor[DbBackend[E], DBS]) = {
        val db = extract[DbBackend[E], DBS](runner.dbs)
      }

      def run[A](actions: DbActions[A]) = actions.run(this)
    }

    // newAggregate(actions) - (label, state)
    // continueAgg(label, actions) - (state)
    // have map[label, aggstate] as part of the runner state
    // but agg state types are different...
    // map[label, aggstate1] :: map... :: HNil ?

    val runner = DbRunner.empty.
      addDb(newDb[Counter.Event]).
      addDb(newDb[Door.Event]).
      addProjection(emptyCounterProjection).
      addProjection(emptyDoorProjection)

    import HList._

    val xdxa = new FF[Projection, Projection] {
      def apply[A](x: Projection[A]) = {
        println(x)
        x
      }
    }
    val xx = kmap(runner.projections, xdxa)
   println(xx)
    val xx2 = kmap(xx, xdxa)
    println(xx2)
    ;
//    println(map(runner.projections, (x: Projection[Domain.CounterProjection.Data]) => {println(x); x}))
   // println(map(xx, (x: Projection[Domain.CounterProjection.Data]) => {println(x); x}))
    {
      val xa = map(runner.projections, (x: Projection[Domain.CounterProjection.Data]) => {println(x); x})
 //     println(map(xa, (x: Projection[Domain.CounterProjection.Data]) => {println(x);}))
    }
    {
      type X = Int :: String :: Int :: HNil.type
      val x: X = 123 :: "ASD" :: 1 :: HNil
      val x2: X  = map(x, (a:Int)  => a*2)
      println(x2)
      val x3 = map(x2, (a:Int)=> a*2)
      println(x3)
    }
        ;
  /*  {
      import runner._
      val db_ = run(
        for {
          c1 <- db(Counter.startCounter("test counter"))
          c1 <- db(c1, actions1)
          d1 <- db(Door.registerDoor("golden gate"))
          d1 <- db(d1, doorActions1)
          // dp1 = doorProj.applyNewEventsFromDb(dr2._1)
          // cp1 = counterProj.applyNewEventsFromDb(cr2._1)
          c1 <- db(c1, actions2)
          // cp2 = cp1.applyNewEventsFromDb(cr3._1)
          d1 <- db(d1, doorActions2)
          // dp2 = dp1.applyNewEventsFromDb(dr3._1)
        } yield (())) .
        fold(err => {println("Error occurred: " + err._2); err._1}, r => {println("OK"); r._1})
      println(db_)
      println("=============")
    }
    //   def runInDb[E, A](actions: EventDatabaseWithFailure[E, A])(db: DbBackend[E]): DbBackend[E] = {
      // ret type is not matching... will need to add traverse / fold instead of map
   //   ???
   // }

    val counterProj = emptyCounterProjection
    val doorProj = emptyDoorProjection
    val ret = for {
    // todo: add custom structure to keep DB conn, projections, and binding to run things automatically w/o much typing here:
    // this might need to extract a typeclass for DB and Projection
      cr1 <- runInMemoryDb(newDb)(Counter.startCounter("test counter"))
      cr2 <- runInMemoryDb(cr1._1)(actions1.run(cr1._2._1))
      dr1 <- runInMemoryDb(newDb)(Door.registerDoor("golden gate"))
      dr2 <- runInMemoryDb(dr1._1)(doorActions1.run(dr1._2._1))
      dp1 = doorProj.applyNewEventsFromDb(dr2._1)
      cp1 = counterProj.applyNewEventsFromDb(cr2._1)
      cr3 <- runInMemoryDb(cr2._1)(actions2.run(cr2._2._1))
      cp2 = cp1.applyNewEventsFromDb(cr3._1)
      dr3 <- runInMemoryDb(dr2._1)(doorActions2.run(dr2._2._1))
      dp2 = dp1.applyNewEventsFromDb(dr3._1)
    } yield (())
    ret fold(err => println("Error occurred: " + err), _ => println("OK"))

    */
  }
}

