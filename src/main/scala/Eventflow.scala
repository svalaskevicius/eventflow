
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

    sealed trait APHandlers[E, PROJS <: HList] {
      def apply(db: DbBackend[E], pr: PROJS): PROJS
    }
    object APHandlers extends ALPPHandlers {
      implicit def collectHandlers[E, D, T <: HList](implicit h: Projection.Handler[E, D], iTail: APHandlers[E, T]): APHandlers[E, Projection[D] :: T] = {
        println(">>> ! AA1!")
        new APHandlers[E, Projection[D] :: T] {
          import HList._
          def apply(db: DbBackend[E], pr: Projection[D] :: T) = pr.head.applyNewEventsFromDb(db) :: iTail(db, pr.tail)
        }
      }
    }
    trait ALPPHandlers extends AVLPPHandlers {
      implicit def collectSkipHandlers[E, D, T <: HList](implicit iTail: APHandlers[E, T]): APHandlers[E, Projection[D] :: T] =
        new APHandlers[E, Projection[D] :: T] {
          def apply(db: DbBackend[E], pr: Projection[D] :: T) = pr.head :: iTail(db, pr.tail)
        }
    }
    trait AVLPPHandlers {
      implicit def collectEndHandlers[E, T <: HNil]: APHandlers[E, HNil.type] =
        new APHandlers[E, HNil.type] {
          def apply(db: DbBackend[E], pr: HNil.type) = pr
        }
    }

    final case class DbRunner[DBS <: HList, PROJS <: HList](dbs: DBS, projections: PROJS) {
      import cats.data.Xor
      import cats.state.StateT
      import HList._


      type DbActions[A] = StateT[(DbRunner[DBS, PROJS], Error) Xor ?, DbRunner[DBS, PROJS], A]

      def addDb[E](db: DbBackend[E]): DbRunner[DbBackend[E] :: DBS, PROJS] = copy(dbs = db :: dbs)

      def addProjection[D](proj: Projection[D]): DbRunner[DBS, Projection[D] :: PROJS] = copy(projections = proj :: projections)


      def applyInDb[E, A](actions: EventDatabaseWithFailure[E, A])(db: DbBackend[E]) : (DbBackend[E], Error Xor A) = {
        val r = runInMemoryDb(db)(actions)
        r.fold(e => (db, Xor.left(e)), res => (res._1, Xor.right(res._2)))
      }

      def db[E, A](actions: EventDatabaseWithFailure[E, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor A], _extr: Extractor[DbBackend[E], DBS], _projHandlers: APHandlers[E, PROJS]): DbActions[A] =
        new DbActions[A](
          Xor.right((runner: DbRunner[DBS, PROJS]) => {
                      val r = applyUpdate1(runner.dbs, applyInDb(actions))
                      r._2.fold(e => Xor.left((runner, e)), a => Xor.right((runner.copy(dbs = r._1).runProjections, a)))
                    }))

      def db[E, A, S, AA](prev: (AggregateState[S], AA), aggregate: AggregateDef[E, S, A])(implicit ev: ApplyUpdate1[DbBackend[E], DBS, Error Xor (AggregateState[S], A)], _extr: Extractor[DbBackend[E], DBS], _projHandlers: APHandlers[E, PROJS]): DbActions[(AggregateState[S], A)] = {
        val actions = aggregate.run(prev._1)
        db(actions)
      }

      def runProjections[E](implicit _extr: Extractor[DbBackend[E], DBS], _projHandlers: APHandlers[E, PROJS]) = {
        val db = extract[DbBackend[E], DBS](dbs)
        copy(projections = _projHandlers(db, projections))
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
          c1 <- db(c1, actions2)
          d1 <- db(d1, doorActions2)
        } yield (())) .
        fold(err => {println("Error occurred: " + err._2); err._1}, r => {println("OK"); r._1})
      println(db_)
    }
  }
}

