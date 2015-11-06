package Cqrs.DbAdapters

import Cqrs.Aggregate
import Cqrs.Database._

import cats.data.Xor
import cats.Monad
import cats.arrow.NaturalTransformation
import cats._
import cats.free.Free
import cats.state._
import cats.free.Free.{ pure, liftF }

import cats.std.all._
import lib.foldM

import scala.collection.immutable.TreeMap

object InMemoryDb {

  import Aggregate._

  final case class DbBackend(
    data: TreeMap[String, TreeMap[String, TreeMap[Int, List[String]]]], // tag -> aggregate id -> version -> event data
    log: TreeMap[Int, (String, String, Int)],                           // operation nr -> tag, aggregate id, aggregate version
    lastOperationNr: Int
  )
  type Db[A] = State[DbBackend, A]

  def newInMemoryDb: DbBackend = DbBackend(TreeMap.empty, TreeMap.empty, 0)

  def readFromDb[E: EventSerialisation](database: DbBackend, tag: Tag, id: AggregateId, fromVersion: Int): Error Xor List[VersionedEvents[E]] = {

    def getById(id: AggregateId)(t: TreeMap[String, TreeMap[Int, List[String]]]) = t.get(id.v)
    def decode(d: String) = implicitly[EventSerialisation[E]].decode(d)
    def decodeEvents(d: List[String])(implicit t: Traverse[List]): Error Xor List[E] = t.sequence[Xor[Error, ?], E](d map decode)
    def decodeToVersionedEvents(dbrec: (Int, List[String])): Error Xor VersionedEvents[E] = {
      val evs = decodeEvents(dbrec._2)
      evs.map(v => VersionedEvents[E](dbrec._1, v))
    }

    (database.data.get(tag.v) flatMap getById(id)).fold[Error Xor List[VersionedEvents[E]]](
      Xor.left(ErrorDoesNotExist(id))
    )(
        (evs: TreeMap[Int, List[String]]) =>
          implicitly[Traverse[List]].sequence[Xor[Error, ?], VersionedEvents[E]](evs.from(fromVersion + 1).toList.map(decodeToVersionedEvents))
      )
  }

  def readExistenceFromDb[E](database: DbBackend, tag: Tag, id: AggregateId)(implicit eventSerialiser: EventSerialisation[E]): Error Xor Boolean = {
    val doesNotExist = readFromDb[E](database, tag, id, 0).
      map { _ => false }.
      recover({ case ErrorDoesNotExist(_) => true })
    doesNotExist.map[Boolean](!_)
  }

  def addToDb[E](database: DbBackend, tag: Tag, id: AggregateId, events: VersionedEvents[E])(implicit eventSerialiser: EventSerialisation[E]): Error Xor DbBackend = {
    val currentTaggedEvents = database.data.get(tag.v)
    val currentEvents = currentTaggedEvents flatMap (_.get(id.v))
    val previousVersion = currentEvents.fold(0)(e => if (e.isEmpty) 0 else e.lastKey)
    if (previousVersion != events.version - 1) {
      Xor.left(ErrorUnexpectedVersion(id, previousVersion, events.version))
    } else {
      val operationNumber = database.lastOperationNr + 1
      Xor.right(
        DbBackend(
          database.data.updated(
            tag.v,
            currentTaggedEvents.getOrElse(TreeMap.empty[String, TreeMap[Int, List[String]]]).updated(
              id.v,
              currentEvents.getOrElse(TreeMap.empty[Int, List[String]]).updated(events.version, events.events map eventSerialiser.encode)
            )
          ),
          database.log + ((operationNumber, (tag.v, id.v, events.version))),
          operationNumber
        )
      )
    }
  }

  def transformDbOpToDbState[E](implicit eventSerialiser: EventSerialisation[E]): EventDatabaseOp[E, ?] ~> Db =
    new (EventDatabaseOp[E, ?] ~> Db) {
      def apply[A](fa: EventDatabaseOp[E, A]): Db[A] = fa match {
        case ReadAggregateExistence(tag, id) => State(database => {
          println("reading existence from DB: '" + fa + "'... " + database)
          val exists = readExistenceFromDb(database, tag, id)(eventSerialiser)
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

  implicit def dbBackend: Backend[DbBackend] = new Backend[DbBackend] {
    def runDb[E: EventSerialisation, A](database: DbBackend, actions: EventDatabaseWithFailure[E, A]): Error Xor (DbBackend, A) = {
      val (db, r) = actions.value.foldMap[Db](transformDbOpToDbState).run(database).run
      r map ((db, _))
    }

    def consumeDbEvents[D](database: DbBackend, fromOperation: Int, initData: D, query: EventDataConsumerQuery[D]): Error Xor (Int, D) = {

      def findData(tag: String, id: String, version: Int): Error Xor List[String] = {
        val optionalRet = database.data.get(tag) flatMap (_.get(id)) flatMap (_.get(version))
        optionalRet.map(Xor.right).getOrElse(Xor.left(ErrorDbFailure("Cannot find requested data: " + tag + " " + id + " " + version)))
      }

      def applyLogEntryData(logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D])(data: List[String]): Error Xor D =
        foldM[D, String, Xor[Error, ?]](d => v => consumer(d, RawEventData(Tag(logEntry._1), AggregateId(logEntry._2), logEntry._3, v)))(d)(data)

      def applyQueryToLogEntry(logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D]): Error Xor D =
        findData(logEntry._1, logEntry._2, logEntry._3) flatMap applyLogEntryData(logEntry, d, consumer)

      def checkAndApplyDataLogEntry(initDataForLogEntries: D, logEntry: (String, String, Int)): Error Xor D =
        foldM[D, (Tag, EventDataConsumer[D]), Xor[Error, ?]](
          d => q => if (q._1.v == logEntry._1) applyQueryToLogEntry(logEntry, d, q._2) else Xor.right(d)
        )(initDataForLogEntries)(query)

      val newData = foldM[D, (Int, (String, String, Int)), Xor[Error, ?]](
        d => el => checkAndApplyDataLogEntry(d, el._2)
      )(
          initData
        )(
          database.log.from(fromOperation + 1)
        )

      newData.map((database.lastOperationNr, _))
    }
  }
}

