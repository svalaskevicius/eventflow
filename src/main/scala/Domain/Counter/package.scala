package Domain

import Cqrs._
import cats.data.Xor

package object Counter {

  sealed trait CounterEvent extends Event
  case class Created(id: String) extends CounterEvent
  case object Incremented extends CounterEvent

  sealed trait Command
  case class Create(id: String) extends Command
  case object Increment extends Command

  final case class CounterState(created: Boolean) {
    def this() = this(created = false)
  }

  type CounterAggregate = Aggregate[CounterEvent, Command, CounterState]

  def createCounter(id: String): List[String] Xor CounterAggregate = {

    import Cqrs.Aggregate._

    val c = new CounterAggregate(
      id = id,
      state = new CounterState(),
      on = {
        case Created(id) => _.copy(created = true)
        case Incremented => identity
      },
      handle = {
        case Create(id) => state =>
          if (state.created) failCommand("Counter has been created already.")
          else emitEvent(Created(id))
        case Increment => state =>
          if (state.created) emitEvent(Incremented)
          else failCommand("Counter has not been created yet.")
      }
    )

    c.handleCommand(Create(id)) map (_ => c)
  }
}
