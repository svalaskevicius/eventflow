package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.{Backend, EventDataConsumer}
import cats.data.Xor

object Projection {
  def empty[D](d: D, h: List[(Tag, EventDataConsumer[D])]): Projection[D] = Projection(0, d, h)
}

final case class Projection[D](lastReadOperation: Int, data: D, dbConsumers: List[(Tag, EventDataConsumer[D])]) {

  def applyNewEventsFromDb[Db: Backend](db: Db): Error Xor Projection[D] = {
    val updatedProjectionData = Database.consumeDbEvents(db, lastReadOperation, data, dbConsumers)
    updatedProjectionData.map(d => this.copy(lastReadOperation = d._1, data = d._2))
  }
}
