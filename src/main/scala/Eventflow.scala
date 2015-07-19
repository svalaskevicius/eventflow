
import Cqrs._

object Eventflow {

  def main(args: Array[String]) {
    val counter = Counter
    println(counter.handleCommand(Create(99)))
    println(counter.handleCommand(Create(99)))
    println(counter.handleCommand(Increment))
    println(counter.handleCommand(Increment))
  }


  sealed trait Event
  case class Created(id: Int) extends Event
  case object Incremented extends Event

  sealed trait Command
  case class Create(id: Int) extends Command
  case object Increment extends Command

  final case class Data(created: Boolean, counter: Int) {
    def this() = this(created = false, counter = 0)
  }

  val Counter = {
    import Cqrs.Aggregate._
    new Aggregate[Event, Command, Data](
      on = {
        case Created(id) => updateState(_.copy(created = true))
        case Incremented => updateState(d => d.copy(counter = d.counter + 1))
      },
      handle = {
        case Create(id) => commandHandler {
          case Data(false, _) => emitEvent(Created(id))
          case Data(true, _) => failCommand("Counter has been created already.")
        }
        case Increment => commandHandler {
          case Data(true, _) => emitEvent(Incremented)
          case Data(false, _) => failCommand("Counter has not been created yet.")
        }
      },
      data = new Data()
    )
  }
}


