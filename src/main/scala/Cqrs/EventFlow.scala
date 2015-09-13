package Cqrs

import Cqrs._

import cats.data.Xor
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.liftF

import cats.std.all._
import cats.syntax.flatMap._

class EventFlow[Cmd, Evt] {
  type CommandH = PartialFunction[Cmd, Aggregate.Error Xor List[Evt]]
  type EventH[A] = PartialFunction[Evt, A]

  sealed trait FlowF[+Next]
  case class SetCommandHandler[Next](cmdh: CommandH, next: Next) extends FlowF[Next]
  case class EventHandler[Next, A](evth: EventH[A], whenHandled: A => Next) extends FlowF[Next]

  implicit object FlowFunctor extends Functor[FlowF] {
    def map[A, B](fa: FlowF[A])(f: A => B): FlowF[B] = fa match {
      case ch: SetCommandHandler[A] => SetCommandHandler[B](ch.cmdh, f(ch.next))
      case eh: EventHandler[A, t] => EventHandler[B, t](eh.evth, f compose eh.whenHandled)
    }
  }

  type Flow[A] = Free[FlowF, A]

  def handler(ch: CommandH): Flow[Unit] = liftF(SetCommandHandler(ch, ()))
  def waitFor[A](eh: EventH[A]): Flow[A] = liftF(EventHandler[A, A](eh, identity))
  def runForever(): Flow[Unit] = waitFor(PartialFunction.empty)

  case class EventStreamConsumer(cmdh: CommandH, evh: Evt => Option[EventStreamConsumer])

  def esRunnerCompiler[A](initCmdH: CommandH)(esRunner: Flow[A]): Option[EventStreamConsumer] =
    esRunner.fold(
      _ => None,
      {
        case SetCommandHandler(cmdh, next) => esRunnerCompiler(cmdh)(next)
        case EventHandler(evth, cont) => {
          lazy val self: EventStreamConsumer = EventStreamConsumer(
            initCmdH,
            (ev: Evt) => evth.lift(ev) map (cont andThen esRunnerCompiler(initCmdH)) getOrElse Some(self)
          )
          Some(self)
        }
      }
    )

  type Aggregate = Cqrs.Aggregate[Evt, Cmd, List[EventStreamConsumer]]

  case class ErrorCannotFindHandler(id: Aggregate.AggregateId) extends Aggregate.Error

  def newAggregate(
    id: String,
    aggregateLogic: List[Flow[Unit]]
  ) : Aggregate =
    new Aggregate(
      id = id,
      state = (aggregateLogic map esRunnerCompiler(PartialFunction.empty)).flatten,
      on = e => d => (d map (consumer => consumer.evh(e))).flatten,
      handle = c => d => d.foldLeft(None: Option[Aggregate.Error Xor List[Evt]])(
        (prev:Option[Aggregate.Error Xor List[Evt]], consumer) => prev match {
          case Some(_) => prev
          case None => consumer.cmdh.lift(c)
        }
      ).getOrElse {
        Xor.Left(ErrorCannotFindHandler(id))
      }
    )
}

