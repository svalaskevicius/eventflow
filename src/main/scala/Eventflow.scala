
import cats.state.State
import cats.data.Reader
import cats.data.Xor
import cats.implicits._

object Eventflow {

  def main(args: Array[String]) {
    val counter = new Counter
    println(counter.handleCommand(Create(99)))
    println(counter.handleCommand(Create(99)))
    println(counter.handleCommand(Increment))
    println(counter.handleCommand(Increment))
  }


  trait Aggregate[E, C, D] {
    type Errors = List[String]
    type Events = List[E]
    type CommandHandler = C => Reader[D, Errors Xor Events]
    type EventHandler = E => State[D, Unit]

    def handleCommand(cmd: C): Option[Errors] =
      handle(cmd).run(data) fold (Some(_), onEvents)

    private def onEvents(evs: Events): Option[Errors] = {
      data = evs.foldLeft(data)((d, e) => on(e).runS(d).run)
      None
    }

    protected var data:D
    protected def on:EventHandler
    protected def handle: CommandHandler

    protected def updateState(fn: D => D): State[D, Unit] = State(d => (fn(d), ()))
    protected def emitEvent(ev:E): Errors Xor Events = Xor.Right(List(ev))
    protected def failCommand(err:String): Errors Xor Events = Xor.Left(List(err))
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

  class Counter extends Aggregate[Event, Command, Data] {

    override protected var data = new Data()

    override protected def on: EventHandler = {
      case Created(id) => updateState (_.copy(created = true))
      case Incremented => updateState (d => d.copy(counter = d.counter + 1))
    }

    override protected def handle: CommandHandler = {
      case Create(id) => Reader {
        case Data(false, _) => emitEvent(Created(id))
        case Data(true, _) => failCommand("Counter has been created already.")
      }
      case Increment => Reader {
        case Data(true, _) => emitEvent(Incremented)
        case Data(false, _) => failCommand("Counter has not been created yet.")
      }
    }
  }
}


