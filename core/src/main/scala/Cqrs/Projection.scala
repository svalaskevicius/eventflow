package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.EventData

object Projection {
  def named(name: String) = new NamedProjection {
    def listeningFor[D](aggregates: AggregateBase*)(handler: D => PartialFunction[EventData[_], D]) = new StartsWithKeyword[D] {
      def startsWith(initialData: D) = new EventConsumerWithData[D](aggregates.toList.map(_.tag), handler, initialData)
    }
  }

  trait NamedProjection {
    def listeningFor[D](aggregates: AggregateBase*)(handler: D => PartialFunction[EventData[_], D]): StartsWithKeyword[D]
  }

  trait StartsWithKeyword[D] {
    def startsWith(initialData: D): EventConsumer
  }
}

trait ProjectionSubscriber[D] {
  def update(data: D)
}

trait EventConsumer {
  def listeningFor: List[EventTag]

  def accept[E](eventData: EventData[E]): EventConsumer
}


final class EventConsumerWithData[Data](val listeningFor: List[EventTag], handler: Data => PartialFunction[EventData[_], Data], initData: Data) extends EventConsumer {

  private var data: Data = initData
  private var subscribers: List[ProjectionSubscriber[Data]] = List.empty

  def accept[E](eventData: EventData[E]) = this.synchronized {
    handler(data).lift(eventData) match {
      case Some(newData) =>
        subscribers.foreach(_.update(newData))
        data = newData
        this
      case None          => this
    }
  }

  def subscribe(subscriber: ProjectionSubscriber[Data]) = this.synchronized {
    subscribers = subscriber :: subscribers
    this
  }

  def getData = this.synchronized(data)
}

