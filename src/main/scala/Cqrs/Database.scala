package Cqrs

import Cqrs.Aggregate.{ EventDatabaseWithFailure, AggregateId, Tag, Error }
import cats.data.Xor

import scala.util.Try

object Database {

  /**
   * Database backend typeclass exposing the DB API.
   *
   * @tparam Db specific database connection handle
   */
  trait Backend[Db] {

    /**
     * Run aggregate interpreter
     *
     * @param database db connection handle
     * @param actions  aggregate execution program
     * @tparam E       Type of aggregate events
     * @tparam A       return type from the given `actions` program
     * @return         error on failure or the returned value from the aggregate execution program
     */
    def runDb[E: EventSerialisation, A](database: Db, actions: EventDatabaseWithFailure[E, A]): Error Xor (Db, A)

    /**
     * Read stored events from the given database handle. The execution folds over the events in the database by given
     * query and returns modified initial value passed as `initData`.
     *
     * @param database      db connection handle
     * @param fromOperation starting log number to read the events from
     * @param initData      initial data to start folding from
     * @param query         fold filters and functions to be applied on the `initData`
     * @tparam D            type of the init / return data
     * @return              error on failure or the new operations log number and the fold result
     */
    def consumeDbEvents[D](database: Db, fromOperation: Int, initData: D, query: List[EventDataConsumerQuery[D]]): Error Xor (Int, D)
  }

  final case class ErrorDbFailure(message: String) extends Error
  final case class EventDecodingFailure(rawData: String) extends Error

  trait EventSerialisation[E] {
    def encode(event: E): String
    def decode(rawData: String): Error Xor E
  }

  implicit def defaultEventSerialisation[E](implicit w: upickle.default.Writer[E], r: upickle.default.Reader[E]): EventSerialisation[E] = new EventSerialisation[E] {
    def encode(event: E): String = upickle.json.write(w.write(event))
    def decode(rawData: String): Error Xor E = Try(Xor.right(r.read(upickle.json.read(rawData)))).getOrElse(Xor.left(EventDecodingFailure(rawData)))
  }

  final case class RawEventData(tag: Tag, id: AggregateId, version: Int, data: String)
  final case class EventData[E](tag: Tag, id: AggregateId, version: Int, data: E)

  /**
   * Db fold operation that updates the given data according to passed event
   *
   * @tparam D
   */
  trait EventDataConsumer[D] {
    def apply(data: D, event: RawEventData): Error Xor D
  }

  def createEventDataConsumer[E, D](handler: (D, EventData[E]) => D)(implicit eventSerialiser: EventSerialisation[E]) =
    new EventDataConsumer[D] {
      def apply(data: D, event: RawEventData): Error Xor D = eventSerialiser.decode(event.data).map(e => handler(data, EventData(event.tag, event.id, event.version, e)))
    }

  /**
   * A query specifying both the tag to load aggregate events by and the fold operation
   *
   * @param tag       Aggregate tag to load events from
   * @param consumer  Db fold operation
   * @tparam D        Data type for the db fold
   */
  final case class EventDataConsumerQuery[D](tag: Tag, consumer: EventDataConsumer[D])

  def runDb[E: EventSerialisation, A, Db](database: Db, actions: EventDatabaseWithFailure[E, A])(implicit backend: Backend[Db]) =
    backend.runDb(database, actions)

  def consumeDbEvents[D, Db](database: Db, fromOperation: Int, initData: D, query: List[EventDataConsumerQuery[D]])(implicit backend: Backend[Db]) =
    backend.consumeDbEvents(database, fromOperation, initData, query)
}

