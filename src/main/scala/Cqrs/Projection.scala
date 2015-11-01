package Cqrs

import Cqrs.Aggregate._
import DbAdapters.InMemoryDb._

object Projection {
  def empty[D](d: D, h: List[(Tag, EventDataConsumer[D])]): Projection[D] = Projection(0, d, h)
}

final case class Projection[D](lastReadOperation: Int, data: D, dbConsumers: List[(Tag, EventDataConsumer[D])]) {

  def applyNewEventsFromDb(db: DbBackend): Projection[D] = {
    val updatedProjectionData = consumeEvents(db, lastReadOperation, data, dbConsumers)
    this.copy(lastReadOperation = updatedProjectionData._1, data = updatedProjectionData._2)
  }
}
