package Cqrs

import Cqrs.Aggregate.{EventDatabaseWithFailure, AggregateId, Tag, Error}
import cats.data.Xor

import scala.util.Try

object Database {

  trait Backend[Db] {
    def runDb[E: EventSerialisation, A](database: Db, actions: EventDatabaseWithFailure[E, A]): Error Xor (Db, A)
    def consumeDbEvents[D](database: Db, fromOperation: Int, initData: D, query: EventDataConsumerQuery[D]): Option[(Int, D)]
  }

  final case class ErrorDbFailure(message: String) extends Error

  trait EventSerialisation[E] {
    def encode(d: E): String
    def decode(s: String): Option[E]
  }

  implicit def defaultEventSerialisation[E](implicit w: upickle.default.Writer[E], r: upickle.default.Reader[E]): EventSerialisation[E] = new EventSerialisation[E] {
    def encode(d: E): String = upickle.json.write(w.write(d))
    def decode(s: String): Option[E] = Try(r.read(upickle.json.read(s))).toOption
  }

  trait EventDataConsumer[D] {
    def apply(d: D, tag: Tag, id: AggregateId, version: Int, data: String): Option[D]
  }
  def createEventDataConsumer[E, D](handler: (D, Tag, AggregateId, Int, E) => D)(implicit eventSerialiser: EventSerialisation[E]) =
    new EventDataConsumer[D] {
      def apply(d: D, tag: Tag, id: AggregateId, version: Int, data: String): Option[D] = eventSerialiser.decode(data).map(handler(d, tag, id, version, _))
    }

  type EventDataConsumerQuery[D] = List[(Tag, EventDataConsumer[D])]


  def runDb[E: EventSerialisation, A, Db: Backend](database: Db, actions: EventDatabaseWithFailure[E, A]) =
    implicitly[Backend[Db]].runDb(database, actions)

  def consumeDbEvents[D, Db: Backend](database: Db, fromOperation: Int, initData: D, query: EventDataConsumerQuery[D]) =
    implicitly[Backend[Db]].consumeDbEvents(database, fromOperation, initData, query)
}

