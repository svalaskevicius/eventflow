package Cqrs

import cats._
import cats.data.Xor
import cats.free.Free
import cats.free.Free.liftF
import shapeless.Generic

import scala.reflect.ClassTag

trait PromotionHandler[C, E] {
  def create(cmd: C): E
}

object PromotionHandler {
  implicit def transform[C, E, CRepr, ERepr](implicit genericC: Generic.Aux[C, CRepr],
                                                      genericE: Generic.Aux[E, ERepr],
                                                      proof: CRepr =:= ERepr): PromotionHandler[C, E] = new PromotionHandler[C, E] {
    def create(cmd: C): E = genericE.from(genericC.to(cmd))
  }
}

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

  def safeCast[I, O : ClassTag](in: I) = in match {
    case x: O => Some(x)
    case _ => None
  }

  def handler[C <: Cmd : ClassTag, E <: Evt](implicit promotionHandler: PromotionHandler[C, E], proofE: E <:< Evt): PartialFunction[Cmd, Aggregate.Error Xor List[Evt]] =
    Function.unlift[Cmd, Aggregate.Error Xor List[Evt]] {(c: Cmd) =>
      for {
        cmd <- safeCast[Cmd, C](c)
      } yield Xor.right(promotionHandler.create(cmd)).map(e => List(proofE(e)))
    }

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
        case EventHandler(evth, cont) =>
          lazy val self: EventStreamConsumer = EventStreamConsumer(
            initCmdH,
            (ev: Evt) => evth.lift(ev) map (cont andThen esRunnerCompiler(initCmdH)) getOrElse Some(self)
          )
          Some(self)
      }
    )

  type FlowAggregate = Aggregate[Evt, Cmd, List[EventStreamConsumer]]
  type EAD[A] = Aggregate.AggregateDef[Evt, List[EventStreamConsumer], A]

  case object ErrorCannotFindHandler extends Aggregate.Error

  val flowAggregate: FlowAggregate =
    Aggregate(
      on = e => d => (d map (consumer => consumer.evh(e))).flatMap(Option.option2Iterable),
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
    val initState = (aggregateLogic map esRunnerCompiler(PartialFunction.empty)).flatMap(Option.option2Iterable)
    runAggregateFromStart(aggregate, initState)
  }
}

