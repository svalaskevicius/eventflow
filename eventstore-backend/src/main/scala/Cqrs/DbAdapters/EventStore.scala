package Cqrs.DbAdapters

import java.io.Closeable

import Cqrs.Aggregate._
import Cqrs.Database.{Error, _}
import Cqrs.{Projection, ProjectionRunner}
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
                   system: ActorSystem,
                   connection: EsConnection,
                   private var projections: List[ProjectionRunner]
                 ) extends Backend {

    val allEventsSubscription = connection.subscribeToAllFrom(new SubscriptionObserver[IndexedEvent] {
        def onLiveProcessingStart(subscription: Closeable) = ()

        def onEvent(event: IndexedEvent, subscription: Closeable) = {
          parseEsStreamId(event.event.streamId) match {
            case Some((tagId, aggId)) => synchronized {
              projections = projections.map { runner =>
                runner.listeningFor.filter(_.name == tagId).foldLeft(runner) { (rnr, tag) =>
                  rnr.accept(Cqrs.Database.EventData(
                    tag,
                    aggId,
                    0,
                    tag.eventSerialiser.decode(event.event.data.data.value.utf8String).toOption.get
                  ))
                }
              }
            }
            case _ => ()
          }
        }

        def onError(e: Throwable) = throw e

        def onClose() = {} //TODO: reopen?
      }
    )

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
        case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
      }
    }

    private def addToDb[E](tag: EventTagAux[E], id: AggregateId, expectedVersion: Int, events: List[E]): Future[Error Xor Unit] = {
      val response = connection future WriteEvents(
        esStreamId(tag, id),
        events.map(ev => eventstore.EventData.Json(ev.getClass.toString, data = tag.eventSerialiser.encode(ev))),
        ExpectedVersion(expectedVersion)
      )
      //TODO: this looks to be unused - returns unit
      val convertedToGlobalPosition = response.map { resp =>
        Xor.right(resp.position.map(_.commitPosition))
      }

      val dbErrorsHandled = convertedToGlobalPosition.recover {
        case err: WrongExpectedVersionException => Xor.left(ErrorUnexpectedVersion(id, err.getMessage))
        case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
      }

      dbErrorsHandled.map(_.map { _ => () })
    }

    private case class StoredSnapshot(version: Int, data: String) extends java.io.Serializable

    private def readDbSnapshot[E, S: Serializable](tag: EventTagAux[E], id: AggregateId): Future[Error Xor ReadSnapshotResponse[S]] = {
      val serializer = implicitly[Serializable[S]]
      val response = connection future ReadEvent.StreamMetadata(esMetaStreamId(tag, id))
      val decodedResponse = response.map[Error Xor ReadSnapshotResponse[S]] { resp =>
        import java.io._
        val ois = new ObjectInputStream( new ByteArrayInputStream(  resp.event.data.data.value.toArray ) );
        val o = ois.readObject().asInstanceOf[StoredSnapshot];
        ois.close();
        serializer.unserialize(o.data).fold[Error Xor ReadSnapshotResponse[S]](
          Xor.left(ErrorDbFailure(s"Cannot unserialise snapshot data for ${tag.name} :: $id"))
        )( data =>
          Xor.right(ReadSnapshotResponse(o.version, data))
        )
      }
      val dbErrorsHandled = decodedResponse.recover {
        case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
      }

      dbErrorsHandled
    }

    private def saveDbSnapshot[E, S: Serializable](tag: EventTagAux[E], id: AggregateId, version: Int, snapshot: S): Future[Error Xor Unit] = {
      val snapshotToStore = StoredSnapshot(version, implicitly[Serializable[S]].serialize(snapshot))
      val serialisedData = {
        import java.io._
        val baos = new ByteArrayOutputStream()
        val oos = new ObjectOutputStream( baos )
        oos.writeObject( snapshotToStore )
        oos.close;
        baos.toByteArray
      }
      val response = connection future WriteEvents.StreamMetadata(
        esMetaStreamId(tag, id),
        Content(serialisedData)
      )
      val dbErrorsHandled = response.recover {
        case err: EsException => Xor.left(ErrorDbFailure(err.getMessage))
      }

      dbErrorsHandled.map( _ => Xor.right(()) )
    }

    private def transformDbOpToDbState[E]: EventDatabaseOp[E, ?] ~> Future =
      new (EventDatabaseOp[E, ?] ~> Future) {
        def apply[A](fa: EventDatabaseOp[E, A]): Future[A] = fa match {
          case ReadAggregateEvents(tag, id, version) => readFromDb[E](tag, id, version)
          case AppendAggregateEvents(tag, id, expectedVersion, events) => addToDb[E](tag, id, expectedVersion, events)
          case rsReq@ReadSnapshot(tag, id) => readDbSnapshot(tag, id)(rsReq.serializer)
          case ssReq@SaveSnapshot(tag, id, version, data) => saveDbSnapshot(tag, id, version, data)(ssReq.serializer)
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
    case _ => None
  }
}

