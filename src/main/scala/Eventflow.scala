import Eventflow.Aggregate._

object Eventflow {

  def main(args: Array[String]) {

    sealed trait CounterEvent;
    case class CounterCreated() extends CounterEvent;
    case class CounterIncremented() extends CounterEvent;

    val aggregateDef = (
      Aggregate
        named "counter"
        on event named "created" will (() => {})
        on event named "incremented" will (() => {})
        on command named "create" will (() => {})
        on command named "increment" will (() => {})
    )
    println(aggregateDef)
  }

  object Aggregate {
    def named(name: String): Aggregate = Aggregate(name, List(), List())

    class CommandBuilder(retTo:Aggregate) {
      case class CommandNameBuilder(name:String) {
        def will(expr: () => Unit): Aggregate = retTo accepting CommandHandler(name, expr)
      }
      def named(a: String): CommandNameBuilder = CommandNameBuilder(a)
    }
    class EventProcessorBuilder(retTo:Aggregate) {
      case class EventProcessorNameBuilder(name:String) {
        def will(expr: () => Unit): Aggregate = retTo handling EventHandler(name, expr)
      }
      def named(a: String): EventProcessorNameBuilder = EventProcessorNameBuilder(a)
    }
    object command
    object event

  }


  case class CommandHandler(name: String, actions: () => Unit)
  case class EventHandler(name: String, actions: () => Unit)

  case class Aggregate(aggregateName: String, commandHandlers: List[CommandHandler], eventHandlers: List[EventHandler]) {
    def accepting(commandHandler: CommandHandler): Aggregate = copy(commandHandlers = commandHandler :: commandHandlers)
    def handling(eventHandler: EventHandler): Aggregate = copy(eventHandlers = eventHandler :: eventHandlers)

    def on(cmd: command.type):CommandBuilder = new CommandBuilder(this)
    def on(cmd: event.type):EventProcessorBuilder = new EventProcessorBuilder(this)
  }
}
