
import Cqrs.InMemoryDb._
import Cqrs.Aggregate._

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

    sealed trait Mapper[S, HL <: HList, B] {
      type Out <: HList
      def apply(hl: HL, f: S => B): Out
    }


    object Mapper extends LowPrioMapper {
      implicit def typeMatchedMapper[S, H, T <: HList, B](implicit ev: H =:= S, iTail: Mapper[S, T, B]): Mapper[S, H :: T, B] =
        new Mapper[S, H :: T, B] {
          type Out = B :: iTail.Out
          def apply(hc: H :: T, f: S => B) = {
            println("apply")
            HCons(f(ev(hc.head)) , iTail(hc.tail, f))
          }
        }
    }
    trait LowPrioMapper extends LowestPrioMapper {
      implicit def iteratedMapper[S, H, T <: HList, B](implicit iTail: Mapper[S, T, B]): Mapper[S, H :: T, B] =
        new Mapper[S, H :: T, B] {
          type Out = H :: iTail.Out
          def apply(hc: H :: T, f: S => B) = {
            println("recurse")
            HCons(hc.head, iTail(hc.tail, f))
          }
        }
    }
    trait LowestPrioMapper {
      implicit def tipNotFound[S,  HC <: HNil, B]: Mapper[S, HC, B] =
              new Mapper[S, HC, B] {
                type Out = HNil
                def apply(hc:  HC, f: S => B) = {
                  println("end.")
                  HNil
                }
          }
    }
    def map[S, B, T <: HList](hc: T, f: S => B)(implicit ev: Mapper[S, T, B]) = ev(hc, f)

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
    import Domain.Counter
    import Domain.Door
    import Domain.CounterProjection._
    import Domain.DoorProjection._

    object DbRunner {
      final case class Info[E](db: DbBackend[E])
      def empty = DbRunner[HNil](HNil)
    }

    final case class DbRunner[DBS <: HList](dbs: DBS) {
      import cats.data.Xor
      import cats.state.StateT
      import HList._
      import DbRunner._

      type DbActions[A] = StateT[(DBS, Error) Xor ?, DBS, A]

      def addDb[E](db: DbBackend[E]): DbRunner[Info[E] :: DBS] = DbRunner(HCons(Info(db), dbs))

      def applyInDb[E, A](actions: EventDatabaseWithFailure[E, A])(dbinfo: Info[E]) : (Info[E], Error Xor A) = {
        val r = runInMemoryDb(dbinfo.db)(actions)
        r.fold(e => (dbinfo, Xor.left(e)), res => (dbinfo.copy(db = res._1), Xor.right(res._2)))
      }

      def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit ev: ApplyUpdate1[Info[E], DBS, Error Xor A]): DbActions[A] =
        new DbActions[A](
          Xor.right((dbs: DBS) => {
                      val r = applyUpdate1(dbs, applyInDb(actions))
                      r._2.fold(e => Xor.left((r._1, e)), a => Xor.right((r._1, a)))
                    }))

      def db[E, A, S, AA](prev: (AggregateState[S], AA), aggregate: AggregateDef[E, S, A])(implicit ev: ApplyUpdate1[Info[E], DBS, Error Xor (AggregateState[S], A)]): DbActions[(AggregateState[S], A)] = {
        val actions = aggregate.run(prev._1)
        db(actions)
      }

      def run[A](actions: DbActions[A]) = actions.run(dbs)
    }

    // newAggregate(actions) - (label, state)
    // continueAgg(label, actions) - (state)
    // have map[label, aggstate] as part of the runner state
    // but agg state types are different...
    // map[label, aggstate1] :: map... :: HNil ?

    val runner = DbRunner.empty .
      addDb(newDb[Counter.Event]) .
      addDb(newDb[Door.Event])

    {
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
  }
}

