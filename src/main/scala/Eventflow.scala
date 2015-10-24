
import Cqrs.InMemoryDb._
import Cqrs.Aggregate._
import lib._

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

  def main(args: Array[String])
  {
    import Cqrs.Projection
    import Domain.Counter
    import Domain.Door
    import Domain.CounterProjection._
    import Domain.DoorProjection._
    import lib.HList._

    object DbRunner {
      def empty = DbRunner[HNil, HNil.type](HNil, HNil)
    }

    sealed trait X[E, Q] {
      def apply: Projection.Handler[E, Q]
    }
    sealed trait PHandlers[E, PROJS <: HList, HND <: HList] {
      def x[Q]: X[E, Q]
   //   def handlers: HND
    }

    object PHandlers extends LPPHandlers {
      implicit def collectHandlers[E, D, T <: HList, HNDT <: HList](implicit h: Projection.Handler[E, D], iTail: PHandlers[E, T, HNDT]): PHandlers[E, Projection[D] :: T, Projection.Handler[E, D] :: HNDT] = {
        println(">>> ! AA1!")
        trait x1 extends PHandlers[E, Projection[D] :: T, Projection.Handler[E, D] :: HNDT] {
     //     def handlers = HCons(h, iTail.handlers)
          implicit def bxx[Q]: X[E, Q] = {
            println("imp rec1")
            iTail.x[Q]
          }
        }
        new x1 {
          def x[Q] = implicitly[X[E, Q]]
 //         implicit def bx[Q >: D <: D]: X[E, Q] = new X[E, Q] {
 //           implicit def bx: X[E, D] = new X[E, D] {
            implicit def bx[Q]: X[E, Q] = new X[E, Q] {
   //implicit def bx[Q](implicit ev: D=:= Q): X[E, Q] = new X[E, Q] {
            println("imp rec2")
            def apply = h.asInstanceOf[Projection.Handler[E, Q]]
          }
        }
      }
    }
    trait LPPHandlers extends VLPPHandlers {
      implicit def collectSkipHandlers[E, D, T <: HList, HNDT <: HList](implicit iTail: PHandlers[E, T, HNDT]): PHandlers[E, Projection[D] :: T, HNDT] =
        new PHandlers[E, Projection[D] :: T, HNDT] {
 //         def handlers = iTail.handlers
          def x[Q] = {
            println("rec2")
            iTail.x[Q]
          }
        }
    }
    trait VLPPHandlers {
      implicit def collectEndHandlers[E, T <: HNil, HNDT <: HNil]: PHandlers[E, T, HNil.type] =
        new PHandlers[E, T, HNil.type] {
 //         def handlers = HNil
  //        def x[Q] = ???
  def x[Q] = {
   println("rec 3")
   ???
 }
        }
    }

    final case class ProjectionWithHandler[E, D](p: Projection[D], h: Option[Projection.Handler[E, D]])

    sealed trait APHandlers[E, PROJS <: HList] {
      def update(db: DbBackend[E], pr: PROJS): PROJS
    }
    object APHandlers extends ALPPHandlers {
      implicit def collectHandlers[E, D, T <: HList](implicit h: Projection.Handler[E, D], iTail: APHandlers[E, T]): APHandlers[E, Projection[D] :: T] = {
        println(">>> ! AA1!")
        new APHandlers[E, Projection[D] :: T] {
          def update(db: DbBackend[E], pr: Projection[D] :: T) = HCons(pr.head.applyNewEventsFromDb(db), iTail.update(db, pr.tail))
        }
      }
    }
    trait ALPPHandlers extends AVLPPHandlers {
      implicit def collectSkipHandlers[E, D, T <: HList](implicit iTail: APHandlers[E, T]): APHandlers[E, Projection[D] :: T] =
        new APHandlers[E, Projection[D] :: T] {
          def update(db: DbBackend[E], pr: Projection[D] :: T) = HCons(pr.head, iTail.update(db, pr.tail))
        }
    }
    trait AVLPPHandlers {
      implicit def collectEndHandlers[E, T <: HNil]: APHandlers[E, HNil.type] =
        new APHandlers[E, HNil.type] {
          def update(db: DbBackend[E], pr: HNil.type) = pr
        }
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

      def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor A], _extr: Extractor[DbBackend[E], DBS], _kmapper: KindMapper[Projection, Projection, PROJS, PROJS], _projHandlers: APHandlers[E, PROJS]): DbActions[A] =
        new DbActions[A](
          Xor.right((runner: DbRunner[DBS, PROJS]) => {
                      val r = applyUpdate1(runner.dbs, applyInDb(actions))
                      r._2.fold(e => Xor.left((runner, e)), a => Xor.right((runner.copy(dbs = r._1).runProjections, a)))
                    }))

      def db[E, A, S, AA](prev: (AggregateState[S], AA), aggregate: AggregateDef[E, S, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor (AggregateState[S], A)], _extr: Extractor[DbBackend[E], DBS], _kmapper: KindMapper[Projection, Projection, PROJS, PROJS], _projHandlers: APHandlers[E, PROJS]): DbActions[(AggregateState[S], A)] = {
        val actions = aggregate.run(prev._1)
        db(actions)
      }

      def runProjections[E](implicit _extr: Extractor[DbBackend[E], DBS], _kmapper: KindMapper[Projection, Projection, PROJS, PROJS], _projHandlers: APHandlers[E, PROJS]) = {
 //       println("!! > _projHandlers "+ _projHandlers.handlers)
        val db = extract[DbBackend[E], DBS](dbs)
        /*
        val updater = new FF[Projection, Projection] {
          def apply[D](x: Projection[D]) = {
            println(x)
            x.applyNewEventsFromDb(db)(_projHandlers.x.apply)
          }
        }
        copy(projections = kmap[Projection, Projection, PROJS, PROJS](projections, updater))
        */
        copy(projections = _projHandlers.update(db, projections))
      }

      def run[A](actions: DbActions[A]) = actions.run(this)
    }

    val runner = DbRunner.empty.
      addDb(newDb[Counter.Event]).
      addDb(newDb[Door.Event]).
      addProjection(emptyCounterProjection).
      addProjection(emptyDoorProjection)

    {
      import runner._
      val db_ = run(
        for {
          c1 <- db(Counter.startCounter("test counter"))
          c1 <- db(c1, actions1)
          d1 <- db(Door.registerDoor("golden gate"))
          d1 <- db(d1, doorActions1)
          // dp1 = doorProj.applyNewEventsFromDb(dr2._1)
          c1 <- db(c1, actions2)
          d1 <- db(d1, doorActions2)
        } yield (())) .
        fold(err => {println("Error occurred: " + err._2); err._1}, r => {println("OK"); r._1})
      println(db_)
      println("=============")
    }
    //   def runInDb[E, A](actions: EventDatabaseWithFailure[E, A])(db: DbBackend[E]): DbBackend[E] = {
      // ret type is not matching... will need to add traverse / fold instead of map
   //   ???
   // }
  /*
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

