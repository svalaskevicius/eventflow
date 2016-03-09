package Cqrs.DbAdapters

import java.io.Closeable

import Cqrs.Aggregate._
import Cqrs.Database.{Error, _}
import Cqrs.ProjRunner
import akka.actor.ActorSystem
import cats._
import cats.data.Xor
import cats.state._
import cats.std.all._
import eventstore._
import lib.foldM

import scala.collection.immutable.TreeMap
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits.global

object EventStore {

  final case class DbBackend(
                              system: ActorSystem,
                              connection: EsConnection,
                              projections: List[ProjRunner],
                              data: TreeMap[String, TreeMap[String, TreeMap[Int, String]]], // tag -> aggregate id -> version -> event data
                              log: TreeMap[Long, (String, String, Int)], // operation nr -> tag, aggregate id, aggregate version
                              lastOperationNr: Long
                            )

  private type Db[A] = State[DbBackend, A]

  def newEventStoreConn(projections: List[ProjRunner], dbUpdate: (DbBackend => DbBackend) => Unit): DbBackend = {
    val system = ActorSystem()
    val connection = EsConnection(system)
    val allEventsSubscription = connection.subscribeToAllFrom(
      new SubscriptionObserver[IndexedEvent] {
        def onLiveProcessingStart(subscription: Closeable) = {}
        def onEvent(event: IndexedEvent, subscription: Closeable) = {
          parseEsStreamId(event.event.streamId) match {
            case Some((tagId, aggId)) => dbUpdate { db =>
              db.copy(projections = db.projections.map { runner =>
                runner.listeningFor.filter(_.v == tagId).foldLeft(runner) { (rnr, tag) =>
                  rnr.accept(Cqrs.Database.EventData(tag, aggId, 0, tag.eventSerialiser.decode(event.event.data.data.value.utf8String).toOption.get))
                }
              })
            }
            case _ => ()
          }
        }
        def onError(e: Throwable) = ??? //TODO: ???
        def onClose() = {} //TODO: reopen?
      }
    )
    DbBackend(system, connection, projections, TreeMap.empty, TreeMap.empty, 0)
  }

  private def esStreamId(tag: EventTag, id: AggregateId) = EventStream.Id(tag.v + "b" + id.v)
  private def parseEsStreamId(id: EventStream.Id) = id.value.split('b').toList match {
    case tagId :: aggId :: Nil => Some(tagId -> aggId)
    case _ => None
  }

  private def readFromDb[E: EventSerialisation](database: DbBackend, tag: EventTag, id: AggregateId, fromVersion: Int): Error Xor ReadAggregateEventsResponse[E] = {

    def decode(d: String) = implicitly[EventSerialisation[E]].decode(d)
    def decodeEvents(d: List[String])(implicit t: Traverse[List]): Error Xor List[E] = t.sequence[Xor[Error, ?], E](d map decode)

    val eventsFromDb = database.connection future ReadStreamEvents(
      esStreamId(tag, id),
      EventNumber.Exact(fromVersion+1)
    )
    val decodedResponse = eventsFromDb.map { response =>
      decodeEvents(response.events.map(_.data.data.value.utf8String)).map { events =>
        ReadAggregateEventsResponse(response.lastEventNumber.value, events, response.endOfStream)
      }
    }
    val dbErrorsHandled: Future[Error Xor ReadAggregateEventsResponse[E]] =
      decodedResponse.recover {
        case _: StreamNotFoundException => Xor.right(ReadAggregateEventsResponse(NewAggregateVersion, List.empty, endOfStream = true))
        case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
      }
    //TODO: add batches support in db api, where aggregate can read more
    Await.result(dbErrorsHandled, 10.seconds)
  }

  private def addToDb[E](database: DbBackend, tag: EventTag, id: AggregateId, expectedVersion: Int, events: List[E])(implicit eventSerialiser: EventSerialisation[E]): Error Xor DbBackend = {
    val response = database.connection future WriteEvents(
      esStreamId(tag, id),
      events.map(ev => eventstore.EventData.Json(ev.getClass.toString, data = eventSerialiser.encode(ev))),
      ExpectedVersion(expectedVersion)
    )
    val convertedToGlobalPosition = response.map { resp =>
      Xor.right(resp.position.map(_.commitPosition))
    }

    val dbErrorsHandled = convertedToGlobalPosition.recover {
      case err: WrongExpectedVersionException => Xor.left(ErrorUnexpectedVersion(id, err.getMessage))
      case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
    }
    val updatedResponse = dbErrorsHandled.map(_.map { position =>
      database.copy(lastOperationNr = position.getOrElse(database.lastOperationNr))
    })
    // TODO: move futures further to natural transform, maybe even public db api?
    Await.result(updatedResponse, 10.seconds)
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

    def consumeDbEvents[D](database: DbBackend, fromOperation: Long, initData: D, queries: List[EventDataConsumerQuery[D]]): Error Xor (Long, D) = {

      def findData(tag: String, id: String, version: Int): Error Xor String = {
        val optionalRet = database.data.get(tag) flatMap (_.get(id)) flatMap (_.get(version))
        optionalRet.map(Xor.right).getOrElse(Xor.left(ErrorDbFailure("Cannot find requested data: " + tag + " " + id + " " + version)))
      }

      def applyLogEntryData(logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D])(data: String): Error Xor D =
        Xor.right(d) // consumer(d, RawEventData(Tag(logEntry._1), AggregateId(logEntry._2), logEntry._3, data))

      def applyQueryToLogEntry(logEntry: (String, String, Int), d: D, consumer: EventDataConsumer[D]): Error Xor D =
        findData(logEntry._1, logEntry._2, logEntry._3) flatMap applyLogEntryData(logEntry, d, consumer)

      def checkAndApplyDataLogEntry(initDataForLogEntries: D, logEntry: (String, String, Int)): Error Xor D =
        foldM[D, EventDataConsumerQuery[D], Xor[Error, ?]](
          d => q => if (q.tag.v == logEntry._1) applyQueryToLogEntry(logEntry, d, q.consumer) else Xor.right(d)
        )(initDataForLogEntries)(queries)

      val newData = foldM[D, (Long, (String, String, Int)), Xor[Error, ?]](
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

