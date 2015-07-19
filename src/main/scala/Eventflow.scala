
import cats.state.State
import cats.data.Reader
import cats.data.Xor
import cats.implicits._

object Eventflow {

  def main(args: Array[String]) {
    val counter = Counter
    println(counter.handleCommand(Create(99)))
    println(counter.handleCommand(Create(99)))
    println(counter.handleCommand(Increment))
    println(counter.handleCommand(Increment))
  }


  class Aggregate[E, C, D] (
                             private val on: Aggregate[E, C, D]#EventHandler,
                             private val handle: Aggregate[E, C, D]#CommandHandler,
                             private var data: D
                           ) {
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
  }

  protected def updateState[D](fn: D => D): State[D, Unit] = State(d => (fn(d), ()))
  protected def emitEvent[E, Errors](ev:E): Errors Xor List[E] = Xor.Right(List(ev))
  protected def failCommand[Events](err:String): List[String] Xor Events = Xor.Left(List(err))

  sealed trait Event
  case class Created(id: Int) extends Event
  case object Incremented extends Event

  sealed trait Command
  case class Create(id: Int) extends Command
  case object Increment extends Command

  final case class Data(created: Boolean, counter: Int) {
    def this() = this(created = false, counter = 0)
  }

  val Counter = new Aggregate[Event, Command, Data](
    on = {
      case Created(id) => updateState (_.copy(created = true))
      case Incremented => updateState (d => d.copy(counter = d.counter + 1))
    },
    handle = {
      case Create(id) => Reader {
        case Data(false, _) => emitEvent(Created(id))
        case Data(true, _) => failCommand("Counter has been created already.")
      }
      case Increment => Reader {
        case Data(true, _) => emitEvent(Incremented)
        case Data(false, _) => failCommand("Counter has not been created yet.")
      }
    },
    data = new Data()
  )
}


