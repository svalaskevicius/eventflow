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

  sealed trait Command
  case class Create(id: String) extends Command
  case object Increment extends Command



  object EventStreamRunner {
    type CommandH = Command => Option[List[String] Xor List[CounterEvent]]
    type EventH[A] = Event => Option[A]

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
  }

  def emptyCmdH(c: Command) = None

  import EventStreamRunner._
  final case class EventStreamConsumer(cmdh: CommandH, evh: Event => Option[EventStreamConsumer])

  def esRunnerCompiler[A](initCmdH: CommandH)(esRunner: EventStreamRunner[A]): Option[EventStreamConsumer] = esRunner.fold(
    _ => None,
    {
      case SetCommandHandler(cmdh, next) => esRunnerCompiler(cmdh)(next)
      case EventHandler(evth, cont) => {
        lazy val self: EventStreamConsumer = EventStreamConsumer(initCmdH, (ev: Event) => evth(ev) map (cont andThen esRunnerCompiler(initCmdH)) getOrElse Some(self))
        Some(self)
      }
    }
  )

  type CounterAggregate = Aggregate[CounterEvent, Command, List[EventStreamConsumer]]

  def createCounter(id: String): List[String] Xor CounterAggregate = {

    import Cqrs.Aggregate._

    val aggregateLogic: List[EventStreamRunner[Unit]] = List(
      for {
        _ <- waitFor({
                       case Created(_) => Some(())
                       case _ => None
                     })
        _ <- handler({
                       case Increment => Some(emitEvent(Incremented))
                       case _ => None
                     })
        _ <- waitFor( _ => None:Option[Unit])
      } yield (),
      for {
        _ <- handler({case Create(id) => Some(emitEvent(Created(id)))})
        _ <-  waitFor({
                        case Created(_) => Some(())
                        case _ => None
                      })
      } yield ()
    )

    val c = new CounterAggregate(
      id = id,
      state = aggregateLogic map esRunnerCompiler(emptyCmdH) flatten,
      on = e => d => d map (consumer => consumer.evh(e)) flatten,
      handle = c => d => d.foldLeft(None: Option[List[String] Xor List[CounterEvent]])(
          (prev:Option[List[String] Xor List[CounterEvent]], consumer) => prev match {
            case Some(_) => prev
            case None => consumer.cmdh(c)
          }
        ).getOrElse {
          Xor.Left(List("not implemented"))
        }
    )
    c.handleCommand(Create(id)) map (_ => c)
  }

  // extend this with declarative:
  // hasSeen(event) >>= hasNotSeen(event) >>= ... -> (case Create(id) -> handler)
  // hasSeen(event) >>=  ... -> case Create(id) -> handler
}
