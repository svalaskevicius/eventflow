package Domain

import Cqrs._
import cats.data.Xor

package object Counter {

  sealed trait Event
  case class Created(id: Int) extends Event
  case object Incremented extends Event

  sealed trait Command
  case class Create(id: Int) extends Command
  case object Increment extends Command

  final case class Data(created: Boolean) {
    def this() = this(created = false)
  }

  type CounterAggregate = Aggregate[Event, Command, Data]

  def createCounter(id: Int): List[String] Xor CounterAggregate = {

    import Cqrs.Aggregate._

    val c = new CounterAggregate(
      on = {
        case Created(id) => updateState(_.copy(created = true))
        case Incremented => noStateChanges()
      },
      handle = {
        case Create(id) => commandHandler {
          case Data(false) => emitEvent(Created(id))
          case Data(true) => failCommand("Counter has been created already.")
        }
        case Increment => commandHandler {
          case Data(true) => emitEvent(Incremented)
          case Data(false) => failCommand("Counter has not been created yet.")
        }
      },
      data = new Data()
    )

    c.handleCommand(Create(id)) map (_ => c)
  }
}
