package Cqrs

import Cqrs.Aggregate.{EventDatabaseWithFailure, AggregateId, Tag, Error}
import cats.data.Xor

import scala.util.Try

object Database {

  trait Backend[Db] {
    def runDb[E: EventSerialisation, A](database: Db, actions: EventDatabaseWithFailure[E, A]): Error Xor (Db, A)
    def consumeDbEvents[D](database: Db, fromOperation: Int, initData: D, query: EventDataConsumerQuery[D]): Error Xor (Int, D)
  }

  final case class ErrorDbFailure(message: String) extends Error
  final case class UnpicklingFailure(s: String) extends Error

  trait EventSerialisation[E] {
    def encode(d: E): String
    def decode(s: String): Error Xor E
  }

  implicit def defaultEventSerialisation[E](implicit w: upickle.default.Writer[E], r: upickle.default.Reader[E]): EventSerialisation[E] = new EventSerialisation[E] {
    def encode(d: E): String = upickle.json.write(w.write(d))
    def decode(s: String): Error Xor E = Try(Xor.right(r.read(upickle.json.read(s)))).getOrElse(Xor.left(UnpicklingFailure(s)))
  }

  final case class RawEventData(tag: Tag, id: AggregateId, version: Int, data: String)
  final case class EventData[E](tag: Tag, id: AggregateId, version: Int, data: E)

  trait EventDataConsumer[D] {
    def apply(d: D, event: RawEventData): Error Xor D
  }
  def createEventDataConsumer[E, D](handler: (D, EventData[E]) => D)(implicit eventSerialiser: EventSerialisation[E]) =
    new EventDataConsumer[D] {
      def apply(d: D, event: RawEventData): Error Xor D = eventSerialiser.decode(event.data).map(e => handler(d, EventData(event.tag, event.id, event.version, e)))
    }

  type EventDataConsumerQuery[D] = List[(Tag, EventDataConsumer[D])]


  def runDb[E: EventSerialisation, A, Db: Backend](database: Db, actions: EventDatabaseWithFailure[E, A]) =
    implicitly[Backend[Db]].runDb(database, actions)

  def consumeDbEvents[D, Db: Backend](database: Db, fromOperation: Int, initData: D, query: EventDataConsumerQuery[D]) =
    implicitly[Backend[Db]].consumeDbEvents(database, fromOperation, initData, query)
}

