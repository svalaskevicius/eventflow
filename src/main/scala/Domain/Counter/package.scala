package Domain

import Cqrs._
import cats.data.Xor
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.liftF

import cats.std.all._
import cats.syntax.flatMap._

package object Counter {

  sealed trait CounterEvent extends Event
  case class Created(id: String) extends CounterEvent
  case object Incremented extends CounterEvent
  case object Decremented extends CounterEvent

  sealed trait Command
  case class Create(id: String) extends Command
  case object Increment extends Command
  case object Decrement extends Command



  object EventStreamRunner {
    type CommandH = PartialFunction[Command, List[String] Xor List[CounterEvent]]
    type EventH[A] = PartialFunction[Event, A]

    sealed trait EventStreamRunnerF[+Next]
    case class SetCommandHandler[Next](cmdh:CommandH, next: Next) extends EventStreamRunnerF[Next]
    case class EventHandler[Next, A](evth:EventH[A], whenHandled: A => Next) extends EventStreamRunnerF[Next]

    implicit val esFunctor: Functor[EventStreamRunnerF] = new Functor[EventStreamRunnerF] {
      def map[A, B](fa: EventStreamRunnerF[A])(f: A => B): EventStreamRunnerF[B] = fa match {
        case ch: SetCommandHandler[A] => SetCommandHandler[B](ch.cmdh, f(ch.next))
        case eh: EventHandler[A, t] => EventHandler[B, t](eh.evth, eh.whenHandled andThen f)
      }
    }

    type EventStreamRunner[A] = Free[EventStreamRunnerF, A]

    def handler(ch: CommandH): EventStreamRunner[Unit] = liftF(SetCommandHandler(ch, ()))
    def waitFor[A](eh: EventH[A]): EventStreamRunner[A] = liftF(EventHandler[A, A](eh, identity))
    def runForever(): EventStreamRunner[Unit] = waitFor(PartialFunction.empty)
  }


  import EventStreamRunner._
  final case class EventStreamConsumer(cmdh: CommandH, evh: Event => Option[EventStreamConsumer])

  def esRunnerCompiler[A](initCmdH: CommandH)(esRunner: EventStreamRunner[A]): Option[EventStreamConsumer] = esRunner.fold(
    _ => None,
    {
      case SetCommandHandler(cmdh, next) => esRunnerCompiler(cmdh)(next)
      case EventHandler(evth, cont) => {
        lazy val self: EventStreamConsumer = EventStreamConsumer(initCmdH, (ev: Event) => evth.lift(ev) map (cont andThen esRunnerCompiler(initCmdH)) getOrElse Some(self))
        Some(self)
      }
    }
  )

  type CounterAggregate = Aggregate[CounterEvent, Command, List[EventStreamConsumer]]

  def createCounter(id: String): List[String] Xor CounterAggregate = {

    import Cqrs.Aggregate._

    def countingLogic(c: Int): EventStreamRunner[Unit] =
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

    val aggregateLogic: List[EventStreamRunner[Unit]] = List(
      handler {case Create(id) => emitEvent(Created(id))} >> waitFor {case Created(_) => ()},
      waitFor {case Created(_) => ()} >> countingLogic(0)
    )

    val c = new CounterAggregate(
      id = id,
      state = aggregateLogic map esRunnerCompiler(PartialFunction.empty) flatten,
      on = e => d => d map (consumer => consumer.evh(e)) flatten,
      handle = c => d => d.foldLeft(None: Option[List[String] Xor List[CounterEvent]])(
          (prev:Option[List[String] Xor List[CounterEvent]], consumer) => prev match {
            case Some(_) => prev
            case None => consumer.cmdh.lift(c)
          }
        ).getOrElse {
          Xor.Left(List("not implemented"))
        }
    )
    c.handleCommand(Create(id)) map (_ => c)
  }
}
