package Cqrs

import cats.data.Xor
import cats.Monad
import cats.arrow.NaturalTransformation
import cats._
import cats.free.Free
import cats.state._
import cats.free.Free.{ pure, liftF }

import cats.std.all._

import scala.collection.immutable.TreeMap

object InMemoryDb {

  import Aggregate._

  type DbBackend[E] = TreeMap[AggregateId, TreeMap[Int, List[E]]]
  type Db[E, A] = State[DbBackend[E], A]

  def newDb[E]: DbBackend[E] = new TreeMap()

  def readFromDb[E](database: DbBackend[E], id: AggregateId, fromVersion: Int): Error Xor List[VersionedEvents[E]] = {
    database.get(id).fold[Error Xor List[VersionedEvents[E]]](
      Xor.left(ErrorDoesNotExist(id))
    )(
        (evs: TreeMap[Int, List[E]]) => Xor.right(
          evs.from(fromVersion + 1).toList.map(v => VersionedEvents[E](v._1, v._2))
        )
      )
  }

  def readExistenceFromDb[E](database: DbBackend[E], id: AggregateId): Error Xor Boolean = {
    val doesNotExist = readFromDb[E](database, id, 0).
      map { _ => false }.
      recover({ case ErrorDoesNotExist(_) => true })
    doesNotExist.map[Boolean](!_)
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
          currentEvents.fold(new TreeMap[Int, List[E]])(
            _.updated(events.version, events.events)
          )
        )
      )
    }
  }

  def runInMemoryDb_[E]: EventDatabaseOp[E, ?] ~> Db[E, ?] = new (EventDatabaseOp[E, ?] ~> Db[E, ?]) {
    def apply[A](fa: EventDatabaseOp[E, A]): Db[E, A] = fa match {
      case ReadAggregateExistence(id) => State(database => {
        println("reading existence from DB: '" + fa + "'... " + database)
        val exists = readExistenceFromDb(database, id)
        println("result: " + exists)
        (database, exists)
      })
      case ReadAggregate(id, version) => State(database => {
        println("reading from DB: '" + fa + "'... " + database)
        val d = readFromDb[E](database, id, version)
        println("result: " + d)
        (database, d)
      })
      case AppendAggregateEvents(id, events) => State((database: DbBackend[E]) => {
        println("writing to DB: '" + fa + "'... " + database)
        val d = addToDb[E](database, id, events)
        println("result: " + d)
        d.fold[(DbBackend[E], Error Xor Unit)](
          err => (database, Xor.left[Error, Unit](err)),
          db => (db, Xor.right[Error, Unit](()))
        )
      })
    }
  }

  def runInMemoryDb[E, A](database: DbBackend[E])(actions: EventDatabaseWithFailure[E, A]): Error Xor (DbBackend[E], A) = {
    val (db, r) = actions.value.foldMap[Db[E, ?]](runInMemoryDb_).run(database).run
    r map ((db, _))
  }
}

