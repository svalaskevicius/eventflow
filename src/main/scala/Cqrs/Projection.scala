package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.{EventData2, EventData, Backend, EventDataConsumerQuery}
import Cqrs.Projection.Handler
import cats.data.Xor
import cats.state.State

import scala.reflect.ClassTag

object Projection {
  type Handler[D, E] = (D, Database.EventData[E]) => D

  def build[D](name: String) = ProjectionBuilder[D](name, List())
}

final case class ProjectionBuilder[D](name: String, dbConsumers: List[EventDataConsumerQuery[D]]) {
  def addHandler[E: Database.EventSerialisation](tag: EventTag, handler: Handler[D, E]) = {
    val n = List(EventDataConsumerQuery(tag, Database.createEventDataConsumer(handler)))
    copy(dbConsumers = dbConsumers ++ n)
  }
  def empty(d: D) = Projection(name, 0, d, dbConsumers)
}

final case class Projection[D](name: String, lastReadOperation: Long, data: D, dbConsumers: List[EventDataConsumerQuery[D]]) {

  def applyNewEventsFromDb[Db: Backend](db: Db): Database.Error Xor Projection[D] = {
    val updatedProjectionData = Database.consumeDbEvents(db, lastReadOperation, data, dbConsumers)
    updatedProjectionData.map(d => this.copy(lastReadOperation = d._1, data = d._2))
  }

}

//trait Proj[Data] {
//  def listeningFor: List[EventTag]
//  def accept[E](data: Data): PartialFunction[EventData2[E], Data]
//}
//
//
//trait ProjRunner {
//  def listeningFor: List[EventTag]
//  def accept[E](eventData: EventData2[E]): ProjRunner
//}
//
//case class ConcreteProjRunner[Data](proj: Proj[Data], data: Data) extends ProjRunner {
//  def listeningFor = proj.listeningFor
//  def accept[E](eventData: EventData2[E]) =
//    proj.accept(data).lift(eventData) match {
//      case Some(newData) => copy(data = newData)
//      case None => this
//    }
//}
trait Proj[Data] {
  def listeningFor: List[EventTag]
  def accept[E](data: Data): PartialFunction[EventData[E], Data]
}


trait ProjRunner {
  def listeningFor: List[EventTag]
  def accept[E](eventData: EventData[E]): ProjRunner
}

case class ConcreteProjRunner[Data](proj: Proj[Data], data: Data) extends ProjRunner {
  def listeningFor = proj.listeningFor
  def accept[E](eventData: EventData[E]) =
    proj.accept(data).lift(eventData) match {
      case Some(newData) => copy(data = newData)
      case None => this
    }
}

final case class Projection2[D](name: String, lastReadOperation: Long, data: D, dbConsumers: List[EventDataConsumerQuery[D]]) {

  def applyNewEventsFromDb[Db: Backend](db: Db): Database.Error Xor Projection2[D] = {
    val updatedProjectionData = Database.consumeDbEvents(db, lastReadOperation, data, dbConsumers)
    updatedProjectionData.map(d => this.copy(lastReadOperation = d._1, data = d._2))
  }

}
