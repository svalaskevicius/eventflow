package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.data.{ Xor, XorT }
import cats.syntax.flatMap._
import Cqrs.DbAdapters.InMemoryDb.createEventDataConsumer

object Counter {

  val tag = Tag("Counter")

  sealed trait Event
  final case class Created(id: AggregateId) extends Event
  case object Incremented extends Event
  case object Decremented extends Event

  sealed trait Command
  final case class Create(id: AggregateId) extends Command
  case object Increment extends Command
  case object Decrement extends Command

  val flow = new EventFlow[Command, Event]
  import flow._
  type CounterAggregate = FlowAggregate
  val counterAggregate = flowAggregate(tag)

  def countingLogic(c: Int): Flow[Unit] =
    handler {
      case Increment => emitEvent(Incremented)
      case Decrement => if (c > 0) emitEvent(Decremented)
                        else failCommand("Counter cannot be decremented")
    } >>
      waitFor {
        case Incremented => c + 1
        case Decremented => c - 1
      } >>=
      countingLogic

  val aggregateLogic: List[Flow[Unit]] = List(
    handler { case Create(id) => emitEvent(Created(id)) } >> waitFor { case Created(_) => () },
    waitFor { case Created(_) => () } >> countingLogic(0)
  )

  def newCounter(id: AggregateId): EAD[Unit] = {
    import counterAggregate._
    initAggregate(id) >> handleCommand(Create(id))
  }

  def startCounter = startFlow[Unit](aggregateLogic) _ compose newCounter
}

import scala.collection.immutable.TreeMap

object CounterProjection {

  type Data = TreeMap[AggregateId, Int]

  def emptyCounterProjection = Projection.empty[Data](TreeMap.empty, List(
    (Counter.tag, createEventDataConsumer( (d: Data, t: Tag, id: AggregateId, v: Int, e: Counter.Event) => {
      import Counter._
      e match {
        case Created(id) =>
          println("created " + id); d
        case Incremented =>
          println("+1"); d.updated(id, d.get(id).fold(1)(_ + 1))
        case Decremented => println("-1"); d.updated(id, d.get(id).fold(-1)(_ - 1))
      }}
    ))
  ))
}

