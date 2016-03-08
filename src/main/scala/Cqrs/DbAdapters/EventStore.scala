package Cqrs.DbAdapters

import Cqrs.Aggregate._
import Cqrs.Database.{Error, _}
import akka.actor.ActorSystem
import cats._
import cats.data.Xor
import cats.state._
import cats.std.all._
import eventstore._
import lib.foldM

import scala.collection.immutable.TreeMap
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

import scala.concurrent.ExecutionContext.Implicits.global

object EventStore {

  final case class DbBackend(
                            system: ActorSystem,
                            connection: EsConnection,
    data: TreeMap[String, TreeMap[String, TreeMap[Int, String]]], // tag -> aggregate id -> version -> event data
    log: TreeMap[Int, (String, String, Int)], // operation nr -> tag, aggregate id, aggregate version
    lastOperationNr: Int
  )
  private type Db[A] = State[DbBackend, A]

  def newEventStoreConn: DbBackend = {
    val system = ActorSystem()
    val connection = EsConnection(system)
    DbBackend(system, connection, TreeMap.empty, TreeMap.empty, 0)
  }

  private def readFromDb[E: EventSerialisation](database: DbBackend, tag: Tag, id: AggregateId, fromVersion: Int): Error Xor ReadAggregateEventsResponse[E] = {

    def getById(id: AggregateId)(t: TreeMap[String, TreeMap[Int, String]]) = t.get(id.v)
    def decode(d: String) = implicitly[EventSerialisation[E]].decode(d)
    def decodeEvents(d: List[String])(implicit t: Traverse[List]): Error Xor List[E] = t.sequence[Xor[Error, ?], E](d map decode)

    //--
    val f = database.connection future ReadStreamEvents(
      EventStream.Id(tag.v+"+"+id.v),
      EventNumber.Exact((fromVersion - 1).max(0))
    )
    case class Response(lastVersion: Int, events: Error Xor List[E], endOfStream: Boolean)
    val events = f.map { response =>
      Response(
        response.lastEventNumber.value,
        decodeEvents(response.events.map(_.data.data.value.utf8String)),
        response.endOfStream
      )
    }
    //TODO: another recover should be for wrong expected version
    val noStreamHandled = events.recover { case e:StreamNotFoundException =>
      Response(
        NewAggregateVersion,
        Xor.right(List.empty),
        endOfStream = true
      )
    }
    //TODO: add batches support in db api, where aggregate can read more
    println("reading events: " + Try(Await.result(noStreamHandled, 10.seconds)))

    //--
    (database.data.get(tag.v) flatMap getById(id)).fold[Error Xor ReadAggregateEventsResponse[E]](
      Xor.right(ReadAggregateEventsResponse(NewAggregateVersion, List.empty, true))
    )(
      (evs: TreeMap[Int, String]) => {
        val newEvents = evs.from(fromVersion + 1)
        val newVersion = if (newEvents.isEmpty) fromVersion else newEvents.lastKey
        decodeEvents(newEvents.values.toList).map(evs => ReadAggregateEventsResponse(newVersion, evs, true))
      }
    )
  }

  private def addToDb[E](database: DbBackend, tag: Tag, id: AggregateId, expectedVersion: Int, events: List[E])(implicit eventSerialiser: EventSerialisation[E]): Error Xor DbBackend = {
    val currentTaggedEvents = database.data.get(tag.v)
    val currentEvents = currentTaggedEvents flatMap (_.get(id.v))
    val previousVersion = currentEvents.fold(-1)(e => if (e.isEmpty) 0 else e.lastKey)
    if (previousVersion != expectedVersion) {
      Xor.left(ErrorUnexpectedVersion(id, previousVersion, expectedVersion))
    } else {
      val operationStartNumber = database.lastOperationNr + 1
      val indexedEvents = events.zipWithIndex
      Xor.right {
        //--
        println(s"writing $id, $events")
        val f = database.connection future WriteEvents(
          EventStream.Id(tag.v+"+"+id.v),
          events.map(ev => eventstore.EventData.Json(ev.getClass.toString, data = eventSerialiser.encode(ev))),
          ExpectedVersion(expectedVersion)
        )
        println("writing events: " + Try(Await.result(f, 10.seconds)))
        //--

        DbBackend(
          database.system,
          database.connection,
          database.data.updated(
            tag.v,
            currentTaggedEvents.getOrElse(TreeMap.empty[String, TreeMap[Int, String]]).updated(
              id.v,
              indexedEvents.foldLeft(currentEvents.getOrElse(TreeMap.empty[Int, String])) { (db, ev) =>
                db.updated(previousVersion + 1 + ev._2, eventSerialiser.encode(ev._1))
              }
            )
          ),
          indexedEvents.foldLeft(database.log) { (log, ev) =>
            log + ((operationStartNumber + ev._2, (tag.v, id.v, previousVersion + 1 + ev._2)))
          },
          operationStartNumber + indexedEvents.length
        )
      }
    }
  }

  private def transformDbOpToDbState[E](implicit eventSerialiser: EventSerialisation[E]): EventDatabaseOp[E, ?] ~> Db =
    new (EventDatabaseOp[E, ?] ~> Db) {
      def apply[A](fa: EventDatabaseOp[E, A]): Db[A] = fa match {
        case ReadAggregateEvents(tag, id, version) => State(database => {
          val d = readFromDb[E](database, tag, id, version)
          (database, d)
        })
        case AppendAggregateEvents(tag, id, expectedVersion, events) => State((database: DbBackend) => {
          val d = addToDb[E](database, tag, id, expectedVersion, events)
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

    def consumeDbEvents[D](database: DbBackend, fromOperation: Int, initData: D, queries: List[EventDataConsumerQuery[D]]): Error Xor (Int, D) = {

      def findData(tag: String, id: String, version: Int): Error Xor String = {
        val optionalRet = database.data.get(tag) flatMap (_.get(id)) flatMap (_.get(version))
        optionalRet.map(Xor.right).getOrElse(Xor.left(ErrorDbFailure("Cannot find requested data: " + tag + " " + id + " " + version)))
      }

      def applyLogEntryData(logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D])(data: String): Error Xor D =
        consumer(d, RawEventData(Tag(logEntry._1), AggregateId(logEntry._2), logEntry._3, data))

      def applyQueryToLogEntry(logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D]): Error Xor D =
        findData(logEntry._1, logEntry._2, logEntry._3) flatMap applyLogEntryData(logEntry, d, consumer)

      def checkAndApplyDataLogEntry(initDataForLogEntries: D, logEntry: (String, String, Int)): Error Xor D =
        foldM[D, EventDataConsumerQuery[D], Xor[Error, ?]](
          d => q => if (q.tag.v == logEntry._1) applyQueryToLogEntry(logEntry, d, q.consumer) else Xor.right(d)
        )(initDataForLogEntries)(queries)

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

