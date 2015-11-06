package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.data.{ Xor, XorT }
import cats.syntax.flatMap._

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

  def emptyCounterProjection = Projection.build.
    addHandler(Counter.tag, (d: Data, e: Database.EventData[Counter.Event]) => {
      import Counter._
      e.data match {
        case Created(id) => d
        case Incremented => d.updated(e.id, d.get(e.id).fold(1)(_ + 1))
        case Decremented => d.updated(e.id, d.get(e.id).fold(-1)(_ - 1))
      }
    }).empty(TreeMap.empty)
}

