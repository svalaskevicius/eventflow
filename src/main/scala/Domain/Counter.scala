package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.data.{Xor, XorT}
import cats.syntax.flatMap._

object Counter {
  sealed trait Event
  final case class Created(id: String) extends Event
  case object Incremented extends Event
  case object Decremented extends Event

  sealed trait Command
  final case class Create(id: String) extends Command
  case object Increment extends Command
  case object Decrement extends Command

  val flow = new EventFlow[Command, Event]
  import flow._
  type CounterAggregate = FlowAggregate

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
    handler {case Create(id) => emitEvent(Created(id))} >> waitFor {case Created(_) => ()},
    waitFor {case Created(_) => ()} >> countingLogic(0)
  )

  def newCounter(id: AggregateId): EAD[FlowAggregate] = {
    val c = newAggregate
    c.initAggregate(id) >> c.handleCommand(Create(id)) >> c.liftToAggregateDef(pure(c))
  }

  def startCounter[A] = startFlow[A](aggregateLogic) _
}
