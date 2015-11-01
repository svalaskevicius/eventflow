package Cqrs.DbAdapters

import Cqrs.Aggregate

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

  final case class EventSerialisation[E](writer: upickle.default.Writer[E], reader: upickle.default.Reader[E])

  implicit def defaultEventSerialisation[E](implicit w: upickle.default.Writer[E], r: upickle.default.Reader[E]): EventSerialisation[E] = EventSerialisation(w, r)

  import Aggregate._

  // tag -> aggregate id -> version -> data
  type DbBackend = TreeMap[String, TreeMap[String, TreeMap[Int, List[upickle.Js.Value]]]]
  type Db[A] = State[DbBackend, A]

  def newDb: DbBackend = new TreeMap()


  def readFromDb[E](database: DbBackend, tag: Tag, id: AggregateId, fromVersion: Int)(implicit eventSerialiser: EventSerialisation[E]): Error Xor List[VersionedEvents[E]] = {

    def getById(id: AggregateId)(t: TreeMap[String, TreeMap[Int, List[upickle.Js.Value]]]) = t.get(id.v)

    (database.get(tag.v) flatMap getById(id)).fold[Error Xor List[VersionedEvents[E]]](
      Xor.left(ErrorDoesNotExist(id))
    )(
      (evs: TreeMap[Int, List[upickle.Js.Value]]) => Xor.right(
        evs.from(fromVersion + 1).toList.map(v => VersionedEvents[E](v._1, v._2 map eventSerialiser.reader.read))
      )
    )
  }

  def readExistenceFromDb[E](database: DbBackend, tag: Tag, id: AggregateId)(implicit eventSerialiser: EventSerialisation[E]): Error Xor Boolean = {
    val doesNotExist = readFromDb[E](database, tag, id, 0).
      map { _ => false }.
      recover({ case ErrorDoesNotExist(_) => true })
    doesNotExist.map[Boolean](!_)
  }

  def addToDb[E](database: DbBackend, tag: Tag, id: AggregateId, events: VersionedEvents[E])(implicit eventSerialiser: EventSerialisation[E]): Error Xor DbBackend = {
    val currentTaggedEvents = database.get(tag.v)
    val currentEvents = currentTaggedEvents flatMap (_.get(id.v))
    val currentVersion = currentEvents.fold(0)(e => if (e.isEmpty) 0 else e.lastKey)
    if (currentVersion != events.version - 1) {
      Xor.left(ErrorUnexpectedVersion(id, currentVersion, events.version))
    } else {
      Xor.right(
        database.updated(
          tag.v,
          currentTaggedEvents.getOrElse(TreeMap.empty[String, TreeMap[Int, List[upickle.Js.Value]]]).updated(
            id.v,
            currentEvents.fold(new TreeMap[Int, List[upickle.Js.Value]])(
              _.updated(events.version, events.events map eventSerialiser.writer.write)
            )
          )
        )
      )
    }
  }

  def transformDbOpToDbState[E](implicit eventSerialiser: EventSerialisation[E]): EventDatabaseOp[E, ?] ~> Db =
    new (EventDatabaseOp[E, ?] ~> Db) {
      def apply[A](fa: EventDatabaseOp[E, A]): Db[A] = fa match {
        case ReadAggregateExistence(tag, id) => State(database => {
          println("reading existence from DB: '" + fa + "'... " + database)
          val exists = readExistenceFromDb(database, tag, id)
          println("result: " + exists)
          (database, exists)
        })
        case ReadAggregate(tag, id, version) => State(database => {
          println("reading from DB: '" + fa + "'... " + database)
          val d = readFromDb[E](database, tag, id, version)
          println("result: " + d)
          (database, d)
        })
        case AppendAggregateEvents(tag, id, events) => State((database: DbBackend) => {
          println("writing to DB: '" + fa + "'... " + database)
          val d = addToDb[E](database, tag, id, events)
          println("result: " + d)
          d.fold[(DbBackend, Error Xor Unit)](
          err => (database, Xor.left[Error, Unit](err)),
          db => (db, Xor.right[Error, Unit](()))
        )
        })
      }
    }

  // rename to runDb, move to generic
  def runInMemoryDb[E, A](database: DbBackend, actions: EventDatabaseWithFailure[E, A])(implicit eventSerialiser: EventSerialisation[E]): Error Xor (DbBackend, A) = {
    val (db, r) = actions.value.foldMap[Db](transformDbOpToDbState).run(database).run
    r map ((db, _))
  }
}

