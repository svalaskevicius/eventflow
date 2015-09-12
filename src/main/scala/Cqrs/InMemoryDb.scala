package Cqrs

import cats.data.{Xor, XorT}
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.{pure, liftF}

import cats.std.all._
import cats.syntax.flatMap._

import scala.collection.immutable.SortedMap
import scala.collection.immutable.TreeMap

object InMemoryDb {

  import Aggregate._

  type DbBackend[E] = SortedMap[AggregateId, SortedMap[Int, List[E]]]

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

  def runInMemoryDb_[A, E](database: DbBackend[E])(actions: EventDatabase[Error Xor A]): Error Xor A =
    actions.fold(
      identity,
      {
        case a: ReadAggregateExistance[t] => {
          println("reading existance from DB: '" + a + "'... "+database)
          val doesNotExist = readFromDb[E](database, a.id, 0).fold(_.isInstanceOf[ErrorDoesNotExist], _ => false)
          println("result: " + (if (doesNotExist) "does not exist" else "aggregate exists"))
          runInMemoryDb_[A, E](database)(a.cont(!doesNotExist))
        }
        case a: ReadAggregate[t, E] => {
          println("reading from DB: '" + a + "'... "+database)
          val d = readFromDb[E](database, a.id, a.fromVersion)
          println("result: " + d)
          d >>= (evs => runInMemoryDb_[A, E](database)(a.onEvents(evs)))
        }
        case a: WriteAggregate[t, E] => {
          println("writing to DB: '" + a + "'... "+database)
          val d = addToDb(database, a.id, a.events)
          println("result: " + d)
          d >>= (db => runInMemoryDb_(db)(a.cont))
        }
      }
    )

  def runInMemoryDb[A, E](database: DbBackend[E])(actions: EventDatabaseWithFailure[A]): Error Xor A =
    runInMemoryDb_(database)(actions.value)
}

