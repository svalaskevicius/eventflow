package Cqrs.DbAdapters

import java.io.Closeable

import Cqrs.Aggregate._
import Cqrs.Database.{ Error, _ }
import Cqrs.{ Projection, ProjectionRunner }
import akka.actor.ActorSystem
import cats._
import cats.data.Xor
import cats.std.all._
import eventstore._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag

object EventStore {

  class DbBackend(
    system:                  ActorSystem,
    connection:              EsConnection,
    private var projections: List[ProjectionRunner]
  ) extends Backend {

    val allEventsSubscription = connection.subscribeToAllFrom(new SubscriptionObserver[IndexedEvent] {
      def onLiveProcessingStart(subscription: Closeable) = ()

      def onEvent(event: IndexedEvent, subscription: Closeable) = {
        if (!event.event.streamId.isMetadata) {
          parseEsStreamId(event.event.streamId) match {
            case Some((tagId, aggId)) => synchronized {
              projections = projections.map { runner =>
                runner.listeningFor.filter(_.name == tagId).foldLeft(runner) { (rnr, tag) =>
                  rnr.accept(Cqrs.Database.EventData(
                    tag,
                    aggId,
                    0,
                    //TODO: what to do when cannot decode for projection?
                    // skipping is not too great as it might mean missed old events
                    // log[warn] & skip / fail? if fail then how?
                    tag.eventSerialiser.decode(event.event.data.data.value.utf8String).toOption.get
                  ))
                }
              }
            }
            case _ => ()
          }
        }
      }

      def onError(e: Throwable) = throw e

      def onClose() = {} //TODO: reopen?
    })

    def runDb[E, A](actions: EventDatabaseWithFailure[E, A]): Future[Error Xor A] =
      actions.value.foldMap(transformDbOpToDbState)

    private def readFromDb[E](tag: EventTagAux[E], id: AggregateId, fromVersion: Int): Future[Error Xor ReadAggregateEventsResponse[E]] = {

      def decode(d: String) = tag.eventSerialiser.decode(d)
      def decodeEvents(d: List[String])(implicit t: Traverse[List]): Error Xor List[E] = t.sequence[Xor[Error, ?], E](d map decode)

      val eventsFromDb = connection future ReadStreamEvents(
        esStreamId(tag, id),
        EventNumber.Exact(fromVersion + 1)
      )
      val decodedResponse = eventsFromDb.map { response =>
        decodeEvents(response.events.map(_.data.data.value.utf8String)).map { events =>
          ReadAggregateEventsResponse(response.lastEventNumber.value, events, response.endOfStream)
        }
      }

      decodedResponse.recover {
        case _: StreamNotFoundException => Xor.right(ReadAggregateEventsResponse(NewAggregateVersion, List.empty, endOfStream = true))
        case err: EsException           => Xor.left(ErrorDbFailure(err.getMessage))
      }
    }

    private def addToDb[E](tag: EventTagAux[E], id: AggregateId, expectedVersion: Int, events: List[E]): Future[Error Xor Unit] = {
      val response = connection future WriteEvents(
        esStreamId(tag, id),
        events.map(ev => eventstore.EventData.Json(ev.getClass.toString, data = tag.eventSerialiser.encode(ev))),
        ExpectedVersion(expectedVersion)
      )

      val dbErrorsHandled = response.map(_ => Xor.right(())).recover {
        case err: WrongExpectedVersionException => Xor.left(ErrorUnexpectedVersion(id, err.getMessage))
        case err: EsException                   => Xor.left(ErrorDbFailure(err.getMessage))
      }

      dbErrorsHandled
    }

    case class StoredSnapshot[A](version: Int, data: A)

    object StoredSnapshot {
      implicit def serializer[A](implicit sa: Serializable[A]): Serializable[StoredSnapshot[A]] = new Serializable[StoredSnapshot[A]] {
        val serializer = new SerializerWriter {
          val write0 = (a: StoredSnapshot[A]) => {
            upickle.Js.Obj(
              "version" -> upickle.default.IntRW.write0(a.version),
              "data" -> sa.serializer.write0(a.data)
            )
          }
        }
        val unserializer = new SerializerReader {
          val read0: PartialFunction[Serialized, StoredSnapshot[A]] =
            Function.unlift {
              case obj: upickle.Js.Obj =>
                val v = upickle.default.IntRW.read0(obj("version"))
                val d = sa.unserializer.read0(obj("data"))
                Some(StoredSnapshot(v, d))
              case _ => None
            }
        }
      }
    }

    private def readDbSnapshot[E, S: Serializable](tag: EventTagAux[E], id: AggregateId): Future[Error Xor ReadSnapshotResponse[S]] = {
      val response = connection future ReadEvent.StreamMetadata(esMetaStreamId(tag, id))
      val decodedResponse = response.map[Error Xor ReadSnapshotResponse[S]] { resp =>

        val serializer = implicitly[Serializable[StoredSnapshot[S]]]
        serializer.fromString(resp.event.data.data.value.utf8String).fold[Error Xor ReadSnapshotResponse[S]](
          Xor.left(ErrorDbFailure(s"Cannot unserialise snapshot data for ${tag.name} :: $id == ${resp.event.data.data.value.utf8String}"))
        )(data =>
            Xor.right(ReadSnapshotResponse(data.version, data.data)))
      }
      val dbErrorsHandled = decodedResponse.recover {
        case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
      }

      dbErrorsHandled
    }

    private def saveDbSnapshot[E, S: Serializable](tag: EventTagAux[E], id: AggregateId, version: Int, snapshot: S): Future[Error Xor Unit] = {
      val snapshotToStore = StoredSnapshot(version, snapshot)
      val serialisedData = {
        val serializer = implicitly[Serializable[StoredSnapshot[S]]]
        serializer.toString(snapshotToStore)
      }
      val response = connection future WriteEvents.StreamMetadata(
        esMetaStreamId(tag, id),
        Content(serialisedData)
      )
      val dbErrorsHandled = response.recover {
        case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
      }

      dbErrorsHandled.map(_ => Xor.right(()))
    }

    private def transformDbOpToDbState[E]: EventDatabaseOp[E, ?] ~> Future =
      new (EventDatabaseOp[E, ?] ~> Future) {
        def apply[A](fa: EventDatabaseOp[E, A]): Future[A] = fa match {
          case ReadAggregateEvents(tag, id, version)                   => readFromDb[E](tag, id, version)
          case AppendAggregateEvents(tag, id, expectedVersion, events) => addToDb[E](tag, id, expectedVersion, events)
          case rsReq @ ReadSnapshot(tag, id)                           => readDbSnapshot(tag, id)(rsReq.serializer)
          case ssReq @ SaveSnapshot(tag, id, version, data)            => saveDbSnapshot(tag, id, version, data)(ssReq.serializer)
        }
      }

    def getProjectionData[D: ClassTag](projection: Projection[D]): Option[D] = {
      projections.foldLeft(None: Option[D])((ret, p) => ret.orElse(p.getProjectionData[D](projection)))
    }
  }

  def newEventStoreConn(projections: ProjectionRunner*): DbBackend = {
    val system = ActorSystem()
    val connection = EsConnection(system)
    new DbBackend(system, connection, projections.toList)
  }

  private val TagAndIdSeparator = '#'

  private def esStreamId(tag: EventTag, id: AggregateId) = EventStream.Id(tag.name + TagAndIdSeparator + id)
  private def esMetaStreamId(tag: EventTag, id: AggregateId) = EventStream.Metadata(tag.name + TagAndIdSeparator + id)

  private def parseEsStreamId(id: EventStream.Id) = id.value.split(TagAndIdSeparator).toList match {
    case tagId :: aggId :: Nil => Some(tagId -> aggId)
    case _                     => None
  }
}

