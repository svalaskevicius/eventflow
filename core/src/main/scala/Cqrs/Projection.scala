package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.EventData

import scala.reflect.ClassTag

trait Projection[D] {
  type Data = D

  def initialData: Data

  def listeningFor: List[EventTag]

  def accept[E](data: Data): PartialFunction[EventData[E], Data]
}

trait ProjectionRunner {
  def listeningFor: List[EventTag]

  def accept[E](eventData: EventData[E]): ProjectionRunner

  def getProjectionData[D: ClassTag](projection: Projection[D]): Option[D]
}

import scala.language.implicitConversions

object ProjectionRunner {
  implicit def createProjectionRunner[D](p: Projection[D]): ProjectionRunner = ConcreteProjectionRunner[D](p, p.initialData)
}

case class ConcreteProjectionRunner[Data](proj: Projection[Data], data: Data) extends ProjectionRunner {
  def listeningFor = proj.listeningFor

  def accept[E](eventData: EventData[E]) =
    proj.accept(data).lift(eventData) match {
      case Some(newData) => copy(data = newData)
      case None          => this
    }

  def getProjectionData[D: ClassTag](projection: Projection[D]): Option[D] = {
    if (proj.getClass.getName == projection.getClass.getName) {
      data match {
        case asD: D => Some(asD)
        case _      => None
      }
    }
    else None
  }
}

