package Cqrs

import cats.Id
import cats.data.{Xor, XorT}
import cats.Monad
import cats.arrow.NaturalTransformation
import cats._
import cats.free.Free
import cats.state._
import cats.free.Free.{pure, liftF}

import cats.std.all._
import cats.syntax.flatMap._

import scala.collection.immutable.SortedMap
import scala.collection.immutable.TreeMap

object InMemoryDb {

  import Aggregate._

  type DbBackend[E] = SortedMap[AggregateId, SortedMap[Int, List[E]]]
  type Db[E, A] = State[DbBackend[E], A]

  def newDb[E](): DbBackend[E] = new TreeMap()

  def readFromDb[E](database: DbBackend[E], id: AggregateId, fromVersion: Int): Error Xor List[VersionedEvents[E]] = {
    database.get(id).fold[Error Xor List[VersionedEvents[E]]](
      Xor.left(ErrorDoesNotExist(id))
    )(
      (evs: SortedMap[Int, List[E]]) => Xor.right(
        evs.from(fromVersion + 1).toList.map(v => VersionedEvents[E](v._1, v._2))
      )
    )
  }

  def addToDb[E](database: DbBackend[E], id: AggregateId, events: VersionedEvents[E]): Error Xor DbBackend[E] = {
    val currentEvents = database.get(id)
    val currentVersion = currentEvents.fold(0)(e => if (e.isEmpty) 0 else e.lastKey)
    if (currentVersion != events.version - 1) {
      Xor.left(ErrorUnexpectedVersion(id, currentVersion, events.version))
    } else {
      Xor.right(
        database.updated(
          id,
          currentEvents.fold(
            (new TreeMap[Int, List[E]]()).asInstanceOf[SortedMap[Int, List[E]]]
          )(
            _.updated(events.version, events.events)
          )
        )
      )
    }
  }

  def runInMemoryDb_[A, E]: EventDatabaseOp ~> Db[E, ?] = new (EventDatabaseOp ~> Db[E, ?]) {
    def apply[A](fa: EventDatabaseOp[A]): Db[E, A] = fa match {
      case ReadAggregateExistance(id) => State(database => {
        println("reading existance from DB: '" + fa + "'... "+database)
        val doesNotExist = readFromDb[E](database, id, 0).map(_ => false).recover({case ErrorDoesNotExist(_) => true})
        println("result: " + doesNotExist)
        (database, doesNotExist.map(!_).asInstanceOf[A])
      })
      case ReadAggregate(id, version) => State(database => {
        println("reading from DB: '" + fa + "'... "+database)
        val d = readFromDb[E](database, id, version)
        println("result: " + d)
        (database, d.asInstanceOf[A]) // TODO: why is this hack needed for scala? looks to be because of the type E as scala just forgets to match it :/
      })
      case WriteAggregate(id, events) => State((database: DbBackend[E]) => {
        println("writing to DB: '" + fa + "'... "+database)
        val d = addToDb(database, id, events)
        println("result: " + d)
        d.fold[(DbBackend[E], Error Xor Unit)](
          (err: Error) => (database, Xor.left[Error, Unit](err)),
          (db: DbBackend[Any]) => (db.asInstanceOf[DbBackend[E]], Xor.right[Error, Unit](())) // another scala's quirk?
        )
      })
    }
  }


  def runInMemoryDb[A, E](database: DbBackend[E])(actions: EventDatabaseWithFailure[A]): Error Xor A =
    actions.value.foldMap[Db[E, ?]](runInMemoryDb_).runA(database).run
}
