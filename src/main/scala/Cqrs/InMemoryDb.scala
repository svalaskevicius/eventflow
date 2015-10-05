package Cqrs

import cats.data.Xor
import cats.Monad
import cats.arrow.NaturalTransformation
import cats._
import cats.free.Free
import cats.state._
import cats.free.Free.{pure, liftF}

import cats.std.all._

import scala.collection.immutable.TreeMap

object InMemoryDb {

  import Aggregate._

  type DbBackend = TreeMap[AggregateId, TreeMap[Int, List[Event]]]
  type Db[A] = State[DbBackend, A]

  def newDb(): DbBackend = new TreeMap()

  def readFromDb(database: DbBackend, id: AggregateId, fromVersion: Int): Error Xor List[VersionedEvents] = {
    database.get(id).fold[Error Xor List[VersionedEvents]](
      Xor.left(ErrorDoesNotExist(id))
    )(
      (evs: TreeMap[Int, List[Event]]) => Xor.right(
        evs.from(fromVersion + 1).toList.map(v => VersionedEvents(v._1, v._2))
      )
    )
  }

  def readExistanceFromDb(database: DbBackend, id: AggregateId): Error Xor Boolean = {
    val doesNotExist = readFromDb(database, id, 0).
      map { _ => false }.
      recover({case ErrorDoesNotExist(_) => true})
    doesNotExist.map[Boolean](!_)
  }

  def addToDb(database: DbBackend, id: AggregateId, events: VersionedEvents): Error Xor DbBackend = {
    val currentEvents = database.get(id)
    val currentVersion = currentEvents.fold(0)(e => if (e.isEmpty) 0 else e.lastKey)
    if (currentVersion != events.version - 1) {
      Xor.left(ErrorUnexpectedVersion(id, currentVersion, events.version))
    } else {
      Xor.right(
        database.updated(
          id,
          currentEvents.fold(new TreeMap[Int, List[Event]])(
            _.updated(events.version, events.events)
          )
        )
      )
    }
  }

  def runInMemoryDb_ = new (EventDatabaseOp ~> Db) {
    def apply[A](fa: EventDatabaseOp[A]): Db[A] = fa match {
      case ReadAggregateExistance(id) => State(database => {
        println("reading existance from DB: '" + fa + "'... "+database)
        val exists = readExistanceFromDb(database, id);
        println("result: " + exists)
        (database, exists)
      })
      case ReadAggregate(id, version) => State(database => {
        println("reading from DB: '" + fa + "'... "+database)
        val d = readFromDb(database, id, version)
        println("result: " + d)
        (database, d)
      })
      case AppendAggregateEvents(id, events) => State((database: DbBackend) => {
        println("writing to DB: '" + fa + "'... "+database)
        val d = addToDb(database, id, events)
        println("result: " + d)
        d.fold[(DbBackend, Error Xor Unit)](
          err => (database, Xor.left[Error, Unit](err)),
          db => (db, Xor.right[Error, Unit](()))
        )
      })
    }
  }


  def runInMemoryDb[A](database: DbBackend)(actions: EventDatabaseWithFailure[A]): Error Xor A =
    actions.value.foldMap[Db](runInMemoryDb_).runA(database).run
}

