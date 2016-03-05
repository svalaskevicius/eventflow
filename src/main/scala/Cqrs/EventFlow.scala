package Cqrs

import Cqrs.Aggregate.{ CommandHandlerResult, ErrorCannotFindHandler, ErrorCommandFailure }
import cats._
import cats.data.{ NonEmptyList => NEL, _ }
import cats.free.Free
import cats.free.Free.liftF
import lib.CaseClassTransformer

import scala.reflect.ClassTag

class EventFlow[Cmd, Evt] {
  type CommandH = PartialFunction[Cmd, CommandHandlerResult[Evt]]
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

  /**
   * Promotes a command to a event. The input types need to be isomoprhic. In other words
   * have the same fields + types
   *
   * @tparam C The command type, should be isomorphic to the event type
   * @tparam E The event type, should be isomorphic to the command type
   * @return A CommandH, which takes a Cmd and either returns a error or a list of events (just one in this case)
   */
  private def promoteCommandToEvent[C <: Cmd: ClassTag, E <: Evt](implicit cct: CaseClassTransformer[C, E]): CommandH =
    Function.unlift[Cmd, CommandHandlerResult[Evt]] {
      //this raises: isInstanceOf is disabled by wartremover, but it has false positives:
      //https://github.com/puffnfresh/wartremover/issues/152
      case c: C => Some(Validated.valid(cct.transform(c)).map(List(_)))
      case _ => None
    }

  object DslV0 {
    def handler(ch: CommandH): Flow[Unit] = liftF(SetCommandHandler(ch, ()))
    def waitFor[A](eh: EventH[A]): Flow[A] = liftF(EventHandler[A, A](eh, identity))
    def waitForAndSwitch[A](eh: EventH[Flow[A]]): Flow[A] = waitFor(eh).flatMap((cont: Flow[A]) => cont)
    def runForever(): Flow[Unit] = waitFor(PartialFunction.empty[Evt, Unit])
  }

  object DslV1 {

    trait CompileableDsl {
      def commandHandler: CommandH
      def eventHandler: EventH[Flow[Unit]]
    }

    trait CompileableDslProvider {
      def toCompileableDsl: CompileableDsl
    }

    type Guard[CH <: Cmd] = (CH => Boolean, String)

    object when {
      def apply[CH <: Cmd: ClassTag] = WhenStatement[CH]()
      def apply[CH <: Cmd: ClassTag](inst: CH) = WhenStatement[CH]()
    }

    trait EventEmittingStatement[CH <: Cmd] {
      def guards: List[Guard[CH]]
      def emit[E <: Evt](implicit cct: CaseClassTransformer[CH, E], ct: ClassTag[CH], et: ClassTag[E]): ThenStatement[CH, E] = {
        ThenStatement[CH, E](promoteCommandToEvent[CH, E], guards)
      }
      def emit[E <: Evt](inst: E)(implicit cct: CaseClassTransformer[CH, E], ct: ClassTag[CH], et: ClassTag[E]): ThenStatement[CH, E] = emit[E]
    }
    case class WhenStatement[CH <: Cmd: ClassTag]() extends EventEmittingStatement[CH] {
      val guards = List.empty
      def guard(guards: Guard[CH]*) = GuardedWhenStatement[CH](guards.toList)
    }

    case class GuardedWhenStatement[CH <: Cmd](guards: List[Guard[CH]]) extends EventEmittingStatement[CH] {
    }

    case class ThenStatement[CH <: Cmd: ClassTag, E <: Evt: ClassTag](handler: CommandH, guards: List[Guard[CH]]) extends CompileableDslProvider {
      def switch(where: E => Flow[Unit]): SwitchToStatement[CH, E] = SwitchToStatement[CH, E](handler, guards, Some(where))
      def switch(where: => Flow[Unit]): SwitchToStatement[CH, E] = switch(_ => where)

      def toCompileableDsl = SwitchToStatement[CH, E](handler, guards, None).toCompileableDsl
    }

    case class SwitchToStatement[CH <: Cmd: ClassTag, E <: Evt: ClassTag](handler: CommandH, guards: List[Guard[CH]], switchTo: Option[E => Flow[Unit]]) extends CompileableDslProvider {
      def toCompileableDsl = new CompileableDsl {
        def commandHandler = if (guards.isEmpty) handler else {
          Function.unlift[Cmd, CommandHandlerResult[Evt]] {
            case c: CH =>
              val errors = guards.flatMap(g => if (!g._1(c)) Some(ErrorCommandFailure(g._2)) else None)
              errors match {
                case err :: errs => Some(Validated.invalid(NEL(err, errs)))
                case Nil => Some(handler(c))
              }
            case _ => None
          }
        }
        def eventHandler = Function.unlift[Evt, Flow[Unit]] {
          case e: E => switchTo.map(_(e))
          case _ => None
        }
      }
    }

    def anyOther = AnyOtherStatement

    object AnyOtherStatement {
      def failWithMessage(msg: String) = FailWithMessageStateMent(msg)
    }

    case class FailWithMessageStateMent(msg: String) extends CompileableDslProvider {
      def toCompileableDsl = new CompileableDsl {
        def commandHandler = { case _ => Aggregate.failCommand(msg) }
        def eventHandler = PartialFunction.empty
      }
    }

    def handler(dsl: CompileableDslProvider*): Flow[Unit] = {
      val compileable = dsl.map(_.toCompileableDsl)
      val commandHandler = compileable.map(_.commandHandler).reduceLeft((prev, curr) => prev.orElse(curr))
      val eventHandler = compileable.map(_.eventHandler).reduceLeft((prev, curr) => prev.orElse(curr))
      for {
        _ <- DslV0.handler(commandHandler)
        _ <- DslV0.waitForAndSwitch(eventHandler)
      } yield ()
    }
  }

  case class EventStreamConsumer(cmdh: CommandH, evh: Evt => Option[EventStreamConsumer])

  def esRunnerCompiler[A](initCmdH: CommandH, esRunner: Flow[A]): Option[EventStreamConsumer] =
    esRunner.fold(
      _ => None,
      {
        case SetCommandHandler(cmdh, next) => esRunnerCompiler(cmdh, next)
        case EventHandler(evth, cont) =>
          lazy val self: EventStreamConsumer = EventStreamConsumer(
            initCmdH,
            (ev: Evt) => evth.lift(ev) map (res => esRunnerCompiler(initCmdH, cont(res))) getOrElse Some(self)
          )
          Some(self)
      }
    )

  type StateData = Option[EventStreamConsumer]
  type EAD[A] = Aggregate.AggregateDef[Evt, StateData, A]

  //TODO: rm list from flows

  trait FlowAggregate extends Aggregate[Evt, Cmd, StateData] {
    def aggregateLogic: Flow[Unit]
    def on = e => d => d flatMap (_.evh(e))
    def handle = c => d => d.foldLeft(None: Option[CommandHandlerResult[Evt]])(
      (prev: Option[CommandHandlerResult[Evt]], consumer) => prev match {
        case Some(_) => prev
        case None => consumer.cmdh.lift(c)
      }
    ).getOrElse {
        Validated.invalid(NEL(ErrorCannotFindHandler))
      }
    def initData = esRunnerCompiler(PartialFunction.empty, aggregateLogic)
  }
}

