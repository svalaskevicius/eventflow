package Cqrs.DbAdapters

import Cqrs.Aggregate._
import Cqrs.Database.FoldableDatabase.{ EventDataConsumer, RawEventData }
import Cqrs.Database.{ Error, _ }
import Cqrs.{ Projection, ProjectionRunner }
import cats._
import cats.data.State
//import cats.std.all._
import cats.implicits._
import lib.foldM

import scala.collection.immutable.TreeMap
import scala.concurrent.Future
import scala.reflect.ClassTag

object InMemoryDb {

  final case class StoredSnapshot(version: Int, data: String)

  final case class DbBackend(
    data:            Map[String, Map[String, TreeMap[Int, String]]], // tag -> aggregate id -> version -> event data
    log:             TreeMap[Long, (String, String, Int)], // operation nr -> tag, aggregate id, aggregate version
    lastOperationNr: Long,
    projections:     List[ProjectionRunner],
    snapshots:       Map[String, Map[String, StoredSnapshot]] // tag -> id -> data
  )

  private type Db[A] = State[DbBackend, A]

  private def readFromDb[E](database: DbBackend, tag: EventTagAux[E], id: AggregateId, fromVersion: Int): Error Either ReadAggregateEventsResponse[E] = {

    def getById(id: AggregateId)(t: Map[String, TreeMap[Int, String]]) = t.get(id)
    def decode(d: String) = decodeEvent(d)(tag.eventSerialiser)
    def decodeEvents(d: List[String])(implicit t: Traverse[List]): Error Either List[E] = t.sequence[Either[Error, ?], E](d map decode)

    (database.data.get(tag.name) flatMap getById(id)).fold[Error Either ReadAggregateEventsResponse[E]](
      Right(ReadAggregateEventsResponse(NewAggregateVersion, List.empty, true))
    )(
        (evs: TreeMap[Int, String]) => {
          val newEvents = evs.from(fromVersion + 1)
          val newVersion = if (newEvents.isEmpty) fromVersion else newEvents.lastKey
          decodeEvents(newEvents.values.toList).map(evs => ReadAggregateEventsResponse(newVersion, evs, true))
        }
      )
  }

  private def addToDb[E](database: DbBackend, tag: EventTagAux[E], id: AggregateId, expectedVersion: Int, events: List[E]): Error Either DbBackend = {
    val currentTaggedEvents = database.data.get(tag.name)
    val currentEvents = currentTaggedEvents flatMap (_.get(id))
    val previousVersion = currentEvents.fold(-1)(e => if (e.isEmpty) 0 else e.lastKey)
    if (previousVersion != expectedVersion) {
      Left(ErrorUnexpectedVersion(id, s"Aggregate version expectation failed: $previousVersion != $expectedVersion"))
    }
    else {
      val operationStartNumber = database.lastOperationNr + 1
      val indexedEvents = events.zipWithIndex
      Right(
        database.copy(
          data            = database.data.updated(
            tag.name,
            currentTaggedEvents.getOrElse(TreeMap.empty[String, TreeMap[Int, String]]).updated(
              id,
              indexedEvents.foldLeft(currentEvents.getOrElse(TreeMap.empty[Int, String])) { (db, ev) =>
                db.updated(previousVersion + 1 + ev._2, tag.eventSerialiser.toString(ev._1))
              }
            )
          ),
          log             = indexedEvents.foldLeft(database.log) { (log, ev) =>
            log + ((operationStartNumber + ev._2, (tag.name, id, previousVersion + 1 + ev._2)))
          },
          lastOperationNr = operationStartNumber + indexedEvents.length,
          projections     = database.projections.map { projection =>
            if (projection.listeningFor.exists(_.name == tag.name)) {
              indexedEvents.foldLeft(projection)((p, e) => p.accept(EventData(tag, id, previousVersion + 1 + e._2, e._1)))
            }
            else {
              projection
            }
          }
        )
      )
    }
  }

  private def readDbSnapshot[E, S: Serializable](database: DbBackend, tag: EventTagAux[E], id: AggregateId): Error Either ReadSnapshotResponse[S] = {
    database.snapshots.get(tag.name).flatMap(_.get(id)).fold[Error Either ReadSnapshotResponse[S]](
      Left(ErrorDbFailure(s"No snapshot for ${tag.name} :: $id"))
    ) { snapshot =>
        val data = implicitly[Serializable[S]].fromString(snapshot.data)
        data.fold[Error Either ReadSnapshotResponse[S]](
          Left(ErrorDbFailure(s"Cannot unserialise snapshot data for ${tag.name} :: $id"))
        )(unserialisedData =>
            Right(ReadSnapshotResponse(snapshot.version, unserialisedData)))
      }
  }

  private def saveDbSnapshot[E, S: Serializable](database: DbBackend, tag: EventTagAux[E], id: AggregateId, version: Int, snapshot: S): Error Either DbBackend = {
    val data = StoredSnapshot(version, implicitly[Serializable[S]].toString(snapshot))
    Right(
      database.copy(
        snapshots = database.snapshots.updated(tag.name, database.snapshots.getOrElse(tag.name, Map.empty).updated(id, data))
      )
    )
  }

  private def transformDbOpToDbState[E]: EventDatabaseOp[E, ?] ~> Db =
    new (EventDatabaseOp[E, ?] ~> Db) {
      def apply[A](fa: EventDatabaseOp[E, A]): Db[A] = fa match {
        case ReadAggregateEvents(tag, id, version) => State(database => {
          val d = readFromDb[E](database, tag, id, version)
          (database, d)
        })
        case AppendAggregateEvents(tag, id, expectedVersion, events) => State((database: DbBackend) => {
          val d = addToDb[E](database, tag, id, expectedVersion, events)
          setterAsResult(d, database)
        })
        case rsReq @ ReadSnapshot(tag, id) => State { database =>
          val d = readDbSnapshot(database, tag, id)(rsReq.serializer)
          (database, d)
        }
        case ssReq @ SaveSnapshot(tag, id, version, data) => State { database =>
          val d = saveDbSnapshot(database, tag, id, version, data)(ssReq.serializer)
          setterAsResult(d, database)
        }
      }

      private def setterAsResult(ret: Error Either DbBackend, initialDb: DbBackend) =
        ret.fold[(DbBackend, Error Either Unit)](
          err => (initialDb, Left[Error, Unit](err)),
          db => (db, Right[Error, Unit](()))
        )
    }

  def newInMemoryDb(projections: ProjectionRunner*) = new Backend with FoldableDatabase {
    var db = DbBackend(TreeMap.empty, TreeMap.empty, 0, projections.toList, Map.empty);

    def runDb[E, A](actions: EventDatabaseWithFailure[E, A]): Future[Error Either A] = synchronized {
      val (newDb, r) = actions.value.foldMap[Db](transformDbOpToDbState).run(db).value
      db = newDb
      Future.successful(r)
    }

    def getProjectionData[D: ClassTag](projection: Projection[D]): Option[D] = synchronized {
      db.projections.foldLeft(None: Option[D])((ret, p) => ret.orElse(p.getProjectionData[D](projection)))
    }

    def consumeDbEvents[D](fromOperation: Long, initData: D, queries: List[EventDataConsumer[D]]): Error Either (Long, D) = synchronized {

      def findData(tag: String, id: String, version: Int): Error Either String = {
        val optionalRet = db.data.get(tag) flatMap (_.get(id)) flatMap (_.get(version))
        optionalRet.map(Right(_)).getOrElse(Left(ErrorDbFailure("Cannot find requested data: " + tag + " " + id + " " + version)))
      }

      def applyLogEntryData(tag: EventTag, logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D])(data: String): Error Either D =
        consumer(d, RawEventData(tag, logEntry._2, logEntry._3, data))

      def applyQueryToLogEntry(tag: EventTag, logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D]): Error Either D =
        findData(logEntry._1, logEntry._2, logEntry._3) flatMap applyLogEntryData(tag, logEntry, d, consumer)

      def checkAndApplyDataLogEntry(initDataForLogEntries: D, logEntry: (String, String, Int)): Error Either D =
        foldM[D, EventDataConsumer[D], Either[Error, ?]](
          d => q => if (q.tag.name == logEntry._1) applyQueryToLogEntry(q.tag, logEntry, d, q) else Right(d)
        )(initDataForLogEntries)(queries)

      val newData = foldM[D, (Long, (String, String, Int)), Either[Error, ?]](
        d => el => checkAndApplyDataLogEntry(d, el._2)
      )(
          initData
        )(
          db.log.from(fromOperation + 1)
        )

      newData.map((db.lastOperationNr, _))
    }
  }
}

