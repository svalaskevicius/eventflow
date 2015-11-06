package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.{Backend, EventDataConsumer}
import cats.data.Xor

object Projection {
  def empty[D](d: D): Projection[D] = Projection(0, d, List())
}

final case class Projection[D](lastReadOperation: Int, data: D, dbConsumers: List[(Tag, EventDataConsumer[D])]) {

  type Handler[E] = (D, Database.EventData[E]) => D

  def applyNewEventsFromDb[Db: Backend](db: Db): Error Xor Projection[D] = {
    val updatedProjectionData = Database.consumeDbEvents(db, lastReadOperation, data, dbConsumers)
    updatedProjectionData.map(d => this.copy(lastReadOperation = d._1, data = d._2))
  }

  def addHandler[E: Database.EventSerialisation](tag: Tag, handler: Handler[E]): Projection[D] = {
    val n = List((tag, Database.createEventDataConsumer(handler)))
    copy(dbConsumers = dbConsumers ++ n)
  }
}
