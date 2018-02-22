package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.FoldableDatabase.EventDataConsumer
import cats.data.EitherT
import cats.free.Free
import cats.free.Free.liftF
import cats.{ Monad, MonadError }

import scala.concurrent.Future
import scala.util.Try
import cats.implicits._

import scala.concurrent.ExecutionContext.Implicits.global

object Database {

  trait Serializable[T] {
    type Serialized = upickle.Js.Value
    type SerializerWriter = upickle.default.Writer[T]
    type SerializerReader = upickle.default.Reader[T]
    def serializer: SerializerWriter
    def unserializer: SerializerReader
    def write(a: Serialized) = upickle.json.write(a)
    def read(s: String) = Try(upickle.json.read(s)).toOption
    def toString(a: T): String = write(serializer.write(a))
    def fromString(s: String): Option[T] = read(s).flatMap(d => Try(unserializer.read(d)).toOption)
  }

  object Serializable {

    implicit def defaultSerializable[T](implicit w: upickle.default.Writer[T], r: upickle.default.Reader[T]): Serializable[T] = new Serializable[T] {
      lazy val serializer = w
      lazy val unserializer = r
    }
  }

  sealed trait Error

  final case class ErrorDbFailure(message: String) extends Error

  final case class EventDecodingFailure(rawData: String) extends Error

  //TODO: add tag
  final case class ErrorUnexpectedVersion(id: AggregateId, message: String) extends Error

  final case class ReadAggregateEventsResponse[E](lastVersion: Int, events: List[E], endOfStream: Boolean)

  final case class ReadSnapshotResponse[A](version: Int, data: A)

  sealed trait EventDatabaseOp[E, A]

  final case class ReadAggregateEvents[E](tag: EventTagAux[E], id: AggregateId, fromVersion: Int) extends EventDatabaseOp[E, Error Either ReadAggregateEventsResponse[E]]

  final case class AppendAggregateEvents[E](tag: EventTagAux[E], id: AggregateId, expectedVersion: Int, events: List[E]) extends EventDatabaseOp[E, Error Either Unit]

  final case class SaveSnapshot[E, A: Serializable](tag: EventTagAux[E], id: AggregateId, version: Int, data: A) extends EventDatabaseOp[E, Error Either Unit] {
    def serializer = implicitly[Serializable[A]]
  }

  final case class ReadSnapshot[E, A: Serializable](tag: EventTagAux[E], id: AggregateId) extends EventDatabaseOp[E, Error Either ReadSnapshotResponse[A]] {
    def serializer = implicitly[Serializable[A]]
  }

  type EventDatabase[E, A] = Free[EventDatabaseOp[E, ?], A]
  type EventDatabaseWithAnyFailure[E, Err, A] = EitherT[EventDatabase[E, ?], Err, A]
  type EventDatabaseWithFailure[E, A] = EventDatabaseWithAnyFailure[E, Error, A]

  def lift[E, A](a: EventDatabaseOp[E, Error Either A]): EventDatabaseWithFailure[E, A] =
    EitherT[EventDatabase[E, ?], Error, A](liftF[EventDatabaseOp[E, ?], Error Either A](a))

  def readNewEvents[E](tag: EventTagAux[E], id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[E, ReadAggregateEventsResponse[E]] =
    lift(ReadAggregateEvents[E](tag, id, fromVersion))

  def appendEvents[E](tag: EventTagAux[E], id: AggregateId, expectedVersion: Int, events: List[E]): EventDatabaseWithFailure[E, Unit] =
    lift(AppendAggregateEvents(tag, id, expectedVersion, events))

  def saveSnapshot[E, A: Serializable](tag: EventTagAux[E], id: AggregateId, version: Int, data: A): EventDatabaseWithFailure[E, Unit] =
    lift(SaveSnapshot(tag, id, version, data))

  def readSnapshot[E, A: Serializable](tag: EventTagAux[E], id: AggregateId): EventDatabaseWithFailure[E, ReadSnapshotResponse[A]] = lift(ReadSnapshot(tag, id))

  implicit def eventDatabaseMonad[E]: Monad[EventDatabase[E, ?]] = Free.catsFreeMonadForFree[EventDatabaseOp[E, ?]]

  implicit def eventDatabaseWithFailureMonad[E]: MonadError[EventDatabaseWithFailure[E, ?], Error] = EitherT.catsDataMonadErrorForEitherT[EventDatabase[E, ?], Error]

  /**
   * Database backend exposing the DB API.
   */
  trait Backend {

    /**
     * Run aggregate interpreter
     *
     * @param actions aggregate execution program
     * @tparam E Type of aggregate events
     * @tparam A return type from the given `actions` program
     * @return error on failure or the returned value from the aggregate execution program
     */
    def runDb[E, A](actions: EventDatabaseWithFailure[E, A]): Future[Error Either A]

    def runAggregate[E, A](actions: DatabaseWithAggregateFailure[E, A]): Future[Aggregate.Error Either A] = {
      runDb(actions.value) map {
        case Left(err)             => Left(DatabaseError(err))
        case Right(Left(err))  => Left(err)
        case Right(Right(ret)) => Right(ret)
      }
    }
  }

  trait FoldableDatabase {
    def consumeDbEvents[D](fromOperation: Long, initData: D, queries: List[EventDataConsumer[D]]): Error Either (Long, D)
  }

  final case class EventData[E](tag: EventTag, id: AggregateId, version: Int, data: E)

  object FoldableDatabase {

    final case class RawEventData(tag: EventTag, id: AggregateId, version: Int, data: String)

    /**
     * Db fold operation that updates the given data according to passed event
     *
     * @tparam D
     */
    trait EventDataConsumer[D] {
      def tag: EventTag
      def apply(data: D, event: RawEventData): Error Either D
    }

    def createEventDataConsumer[E, D](evTag: EventTagAux[E])(handler: (D, EventData[E]) => D) =
      new EventDataConsumer[D] {
        val tag = evTag
        def apply(data: D, event: RawEventData): Error Either D = decodeEvent[E](event.data)(tag.eventSerialiser).map(e => handler(data, EventData(event.tag, event.id, event.version, e)))
      }
  }

  def decodeEvent[E](rawData: String)(implicit serializer: Serializable[E]): Error Either E = Try(serializer.fromString(rawData).map(Right(_))).toOption.flatten.getOrElse(Left(EventDecodingFailure(rawData)))

}

