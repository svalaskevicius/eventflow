package Domain

import Cqrs.Aggregate._
import Cqrs._

object Counter {

  sealed trait Event
  final case class Created(id: AggregateId, start: Int) extends Event
  case object Incremented extends Event
  case object Decremented extends Event

  sealed trait Command
  final case class Create(id: AggregateId, start: Int) extends Command
  case object Increment extends Command
  case object Decrement extends Command

  val flow = new EventFlow[Command, Event]
  import flow.{ Flow, FlowAggregate }
  import flow.DslV1._

  private def counting(c: Int): Flow[Unit] = handler(
    when(Increment).emit(Incremented).switch(counting(c + 1)),
    when(Decrement).guard(_ => c > 0, "Counter cannot be decremented").emit(Decremented).switch(counting(c - 1))
  )

  private val fullAggregate: Flow[Unit] = handler(
    when[Create].emit[Created].switch(evt => counting(evt.start))
  )

  object CounterAggregate extends FlowAggregate {
    def tag = Tag("Counter")
    def aggregateLogic = fullAggregate
  }
}

import scala.collection.immutable.TreeMap

object CounterProjection {

  type Data = TreeMap[AggregateId, Int]

  def emptyCounterProjection = Projection.build("counters").
    addHandler(Counter.CounterAggregate.tag, (d: Data, e: Database.EventData[Counter.Event]) => {
      import Counter._
      e.data match {
        case Created(id, start) => d.updated(e.id, start)
        case Incremented => d.updated(e.id, d.get(e.id).fold(1)(_ + 1))
        case Decremented => d.updated(e.id, d.get(e.id).fold(-1)(_ - 1))
      }
    }).empty(TreeMap.empty)
}

