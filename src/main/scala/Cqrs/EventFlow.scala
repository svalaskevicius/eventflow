package Cqrs

import cats.data.Xor
import cats.Monad
import cats._
import cats.free.Free
import cats.free.Free.liftF

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
  def waitForAndSwitch[A](eh: EventH[Flow[A]]): Flow[A] = waitFor(eh).flatMap((cont: Flow[A]) => cont)
  def runForever(): Flow[Unit] = waitFor(PartialFunction.empty[Evt, Unit])

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

  type FlowAggregate = Aggregate[Evt, Cmd, List[EventStreamConsumer]]
  type EAD[A] = Aggregate.AggregateDef[Evt, List[EventStreamConsumer], A]

  case object ErrorCannotFindHandler extends Aggregate.Error

  val flowAggregate: FlowAggregate =
    Aggregate(
      on = e => d => (d map (consumer => consumer.evh(e))).map(Option.option2Iterable _).flatten,
      handle = c => d => d.foldLeft(None: Option[Aggregate.Error Xor List[Evt]])(
        (prev:Option[Aggregate.Error Xor List[Evt]], consumer) => prev match {
          case Some(_) => prev
          case None => consumer.cmdh.lift(c)
        }
      ).getOrElse {
        Xor.Left(ErrorCannotFindHandler)
      }
    )

  import Aggregate._

  def startFlow[A](aggregateLogic: List[Flow[Unit]])(aggregate: EAD[A]): StatefulEventDatabaseWithFailure[Evt, List[EventStreamConsumer], A] = {
    val initState = (aggregateLogic map esRunnerCompiler(PartialFunction.empty)).map(Option.option2Iterable _).flatten
    runAggregateFromStart(aggregate, initState)
  }
}

