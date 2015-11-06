package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.{ EventDataConsumerQuery, Backend }
import Cqrs.Projection.Handler
import cats.data.Xor

object Projection {
  type Handler[D, E] = (D, Database.EventData[E]) => D

  def build[D] = ProjectionBuilder[D](List())
}

final case class ProjectionBuilder[D](dbConsumers: List[EventDataConsumerQuery[D]]) {
  def addHandler[E: Database.EventSerialisation](tag: Tag, handler: Handler[D, E]) = {
    val n = List(EventDataConsumerQuery(tag, Database.createEventDataConsumer(handler)))
    copy(dbConsumers = dbConsumers ++ n)
  }
  def empty(d: D) = Projection(0, d, dbConsumers)
}

final case class Projection[D](lastReadOperation: Int, data: D, dbConsumers: List[EventDataConsumerQuery[D]]) {

  def applyNewEventsFromDb[Db: Backend](db: Db): Error Xor Projection[D] = {
    val updatedProjectionData = Database.consumeDbEvents(db, lastReadOperation, data, dbConsumers)
    updatedProjectionData.map(d => this.copy(lastReadOperation = d._1, data = d._2))
  }

}
