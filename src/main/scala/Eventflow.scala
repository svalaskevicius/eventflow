
import cats.state.State
import cats.data.Reader
import cats.data.Xor
import cats.implicits._

object Eventflow {

  def main(args: Array[String]) {
    val add1: State[Int, Unit] = State(n => (n + 1, ()))
    val add2: Unit => State[Int, Int] = (Unit) => State(n => (n + 2, n))
    println(add1.flatMap(add2).run(1).run)
  }

  object Counter { // no need for an object as its going to be in a module
    sealed trait Event
    case class Created(id: Int) extends Event
    case class Incremented() extends Event

    sealed trait Command
    case class Create(id: Int) extends Command
    case class Increment() extends Command

    object Data {
      def apply():Data = Data(created = false, counter = 0)
    }
    final case class Data(created: Boolean, counter: Int)

    type Errors = List[String]
    type Events = List[Event]
    type CommandHandler = Command => Reader[Data, Errors Xor Events]
    type EventHandler = Event => State[Data, Unit]

    def updateState(fn: Data => Data): State[Data, Unit] = State(d => (fn(d), ()))
    def emitEvent(ev:Event): Errors Xor Events = Xor.Right(List(ev))
    def failCommand(err:String): Errors Xor Events = Xor.Left(List(err))
  }

  class Counter {

    import Counter._

    def on: EventHandler = {
      case Created(id) => updateState (_.copy(created = true))
      case Incremented() => updateState (d => d.copy(counter = d.counter + 1))
    }

    def handle: CommandHandler = {
      case Create(id) => Reader {
        case Data(false, _) => emitEvent(Created(id))
        case Data(true, _) => failCommand("Counter has been created already.")
      }
      case Increment() => Reader {
        case Data(true, _) => emitEvent(Incremented())
        case Data(false, _) => failCommand("Counter has not been created yet.")
      }
    }
  }
}


