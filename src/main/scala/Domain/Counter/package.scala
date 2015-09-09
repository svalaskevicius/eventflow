package Domain

import Cqrs._
import cats.data.{Xor, XorT}
import cats.syntax.flatMap._

sealed trait CounterEvent

sealed trait CounterCommand

package object Counter extends EventFlow[CounterCommand, CounterEvent] {

  case class Created(id: String) extends CounterEvent
  case object Incremented extends CounterEvent
  case object Decremented extends CounterEvent

  case class Create(id: String) extends CounterCommand
  case object Increment extends CounterCommand
  case object Decrement extends CounterCommand

  import Cqrs.Aggregate._

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

  def newCounter(id: String): XorT[EventDatabase, List[String], Aggregate] = {
    val c = newAggregate(id, aggregateLogic)
    c.handleCommand(Create(id)) >> XorT.pure[EventDatabase, List[String], Aggregate](c)
  }
}
