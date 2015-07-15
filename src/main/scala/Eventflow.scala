/**
 * Created by svalaskevicius on 12/07/2015.
 */

object Eventflow {

  def main(args: Array[String]) {
    val aggregateDef = (
      Aggregate
        named "counter"
        on command named "create" will (() => {})
        on command named "increment" will (() => {})
    )
    println(aggregateDef)
  }

  object Aggregate {
    def named(name: String): Aggregate = Aggregate(name, List())
  }


  case class Command(name: String, actions: () => Unit)

  object command

  case class Aggregate(aggregateName: String, commands: List[Command]) {
    def accepting(cmd: Command): Aggregate = copy(commands = cmd :: commands)

    def on(cmd: command.type):CommandBuilder = new CommandBuilder(this)

    class CommandBuilder(retTo:Aggregate) {
      case class CommandNameBuilder(name:String) {
        def will(expr: () => Unit): Aggregate = retTo accepting Command(name, expr)
      }
      def named(a: String): CommandNameBuilder = CommandNameBuilder(a)
    }
  }
}
