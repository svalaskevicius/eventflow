package Cqrs

import Cqrs.Aggregate.{ErrorCannotFindHandler, ErrorCommandFailure, CommandHandlerResult}
import Cqrs.Database.EventSerialisation
import cats._
import cats.data.{NonEmptyList => NEL, _}
import cats.free.Free
import cats.free.Free.liftF
import lib.CaseClassTransformer

import scala.reflect.ClassTag
import scala.language.implicitConversions


class EventFlowImpl[Evt, Cmd] {
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
  def promoteCommandToEvents[C <: Cmd : ClassTag, E <: Evt](implicit cct: CaseClassTransformer[C, E]): PartialFunction[Cmd, List[E]] =
    Function.unlift {
      case c: C => Some(cct.transform(c)).map(List(_))
      case _ => None
    }

  object DslBase {
    def handler(ch: CommandH): Flow[Unit] = liftF(SetCommandHandler(ch, ()))

    def waitFor[A](eh: EventH[A]): Flow[A] = liftF(EventHandler[A, A](eh, identity))

    def waitForAndSwitch[A](eh: EventH[Flow[A]]): Flow[A] = waitFor(eh).flatMap((cont: Flow[A]) => cont)

    def runForever(): Flow[Unit] = waitFor(PartialFunction.empty[Evt, Unit])
  }

  case class EventStreamConsumer(cmdh: CommandH, evh: Evt => Option[EventStreamConsumer])

  type StateData = Option[EventStreamConsumer]

  def esRunnerCompiler[A](initCmdH: CommandH, esRunner: Flow[A]): StateData =
    esRunner.fold(
      _ => None, {
        case SetCommandHandler(cmdh, next) => esRunnerCompiler(cmdh, next)
        case EventHandler(evth, cont) =>
          lazy val self: EventStreamConsumer = EventStreamConsumer(
            initCmdH,
            (ev: Evt) => evth.lift(ev) map (res => esRunnerCompiler(initCmdH, cont(res))) getOrElse Some(self)
          )
          Some(self)
      }
    )

}

trait Snapshottable extends AggregateBase {

  val eventFlowImpl: EventFlowImpl[Event, Command]

  import eventFlowImpl.Flow

  type FlowState[T] = Function1[T, Flow[Unit]]
  type FlowStateHandler[T] = (T, FlowState[T])

  trait RegisteredFlowState {
    type StateParam
    implicit def r: upickle.default.Reader[StateParam]
    implicit def w: upickle.default.Writer[StateParam]
    def classTag: ClassTag[StateParam]
    def state: FlowState[StateParam]
  }
  object RegisteredFlowState {
    implicit def createFlowStateAux[T: ClassTag: upickle.default.Reader: upickle.default.Writer](flowState: FlowState[T]): RegisteredFlowState = new RegisteredFlowState {
      type StateParam = T
      val r = implicitly[upickle.default.Reader[T]]
      val w = implicitly[upickle.default.Writer[T]]
      val classTag = implicitly[ClassTag[T]]
      val state = flowState
    }
  }

  type FlowStates = Map[Symbol, RegisteredFlowState]

  val snapshottableStates: FlowStates


  sealed trait FlowStateCall {
    type StateParam
    val state: Symbol
    val arg: StateParam
    def w: upickle.default.Writer[StateParam]
    def applyToFlowState(f: RegisteredFlowState) = arg match {
      case f.classTag(farg) => Some(f.state(farg))
      case _ => None
    }
  }

  object FlowStateCall {

    def createSnapshot[T: ClassTag](s: FlowStateHandler[T]): Option[FlowStateCall] = {
      val stateOption = snapshottableStates.find(p => p._2.state == s._2)
      println(s"looking for $s in $snapshottableStates; found $stateOption")
      stateOption.flatMap ( foundState =>
        s._1 match {
          case foundState._2.classTag(stateArg) =>
            Some(new FlowStateCall {
              type StateParam = foundState._2.StateParam
              val state = foundState._1
              val arg = stateArg
              val w = foundState._2.w
            })
          case _ => None
        }
      )
    }
    val snapshotSerializer: Database.Serializable[Snapshottable#FlowStateCall] = {
      println("creating serializer!! ")
      new Database.Serializable[Snapshottable#FlowStateCall] {
        case class SnapshotData[T](state: Symbol, arg: T)
        def serialize(a: Snapshottable#FlowStateCall) = {
          def dataWriter = new upickle.default.Writer[SnapshotData[a.StateParam]] {
            val write0: SnapshotData[a.StateParam] => upickle.Js.Value = data =>
              upickle.Js.Obj(
                "state" -> upickle.Js.Str(a.state.toString),
                "arg" -> a.w.write(a.arg)
              )
          }

          val x = upickle.json.write(dataWriter.write(SnapshotData(a.state, a.arg)))

          println("===============")
          println(x)
          println("===============")
          x
        }
        def unserialize(s: String) = {
          val symbolReader = new upickle.default.Reader[Symbol] {
            val read0: PartialFunction[upickle.Js.Value, Symbol] =
              Function.unlift {
                case obj: upickle.Js.Obj => obj.value.toList.collectFirst({
                                                                            case (name, value: upickle.Js.Str) if name.equals("state") => Symbol(value.value)
                                                                          })
                case _ => None
              }
          }
          val json = upickle.json.read(s) // TODO: Try
          val symbol = symbolReader.read(json) // TODO: Try
          snapshottableStates.get(symbol).map { flowState =>
            val argReader = new upickle.default.Reader[flowState.StateParam] {
              val read0: PartialFunction[upickle.Js.Value, flowState.StateParam] =
                Function.unlift {
                  case obj: upickle.Js.Obj => obj.value.toList.collectFirst(Function.unlift {
                                                                              case (name, value) if name.equals("arg") => flowState.r.read.lift(value)
                                                                              case _ => None
                                                                            })
                  case _ => None
                }
            }
            val readArg = argReader.read(json) //TODO: Try
            new FlowStateCall {
              type StateParam = flowState.StateParam
              val state = symbol
              val arg = readArg
              val r = flowState.r //TODO: reader is not needed here
              val w = flowState.w
            }
          }
        }
      }
    }
  }


  def compileSnapshot(s: FlowStateCall): Option[eventFlowImpl.StateData] =
    snapshottableStates.get(s.state).flatMap(x => s.applyToFlowState(x)).map(flow => eventFlowImpl.esRunnerCompiler(PartialFunction.empty, flow))

  def toFlow[A](handler: FlowStateHandler[A]): Flow[Unit] = handler._2(handler._1)
}

trait EventFlowBase[Evt, Cmd] extends Aggregate[Evt, Cmd, EventFlowImpl[Evt, Cmd]#StateData, Snapshottable#FlowStateCall] with Snapshottable {

  val eventFlowImpl = new EventFlowImpl[Evt, Cmd]

  type Flow[A] = eventFlowImpl.Flow[A]

  def convertSnapshotToData(s: AggregateSnapshot): Option[AggregateData] = {
    val ct = implicitly[ClassTag[FlowStateCall]]
    s match {
      case ct(sarg) => compileSnapshot(sarg)
      case _ => None
    }
  }

  val snapshotSerializer = FlowStateCall.snapshotSerializer

  def aggregateLogic: Flow[Unit]

  def eventHandler = e => d => d flatMap (_.evh(e))

  def commandHandler = c => d => d.foldLeft(None: Option[CommandHandlerResult[Evt]])(
    (prev: Option[CommandHandlerResult[Evt]], consumer) => prev match {
      case Some(_) => prev
      case None => consumer.cmdh.lift(c)
    }
  ).
    getOrElse {
    Validated.invalid(NEL(ErrorCannotFindHandler(c.toString)))
  }

  def initData = eventFlowImpl.esRunnerCompiler(PartialFunction.empty, aggregateLogic)
}

trait DslV1 { self: AggregateBase with Snapshottable =>

  val eventFlowImpl: EventFlowImpl[Event, Command]

  import eventFlowImpl.{Flow, CommandH, EventH}

  trait CompilableDsl {
    def commandHandler: CommandH

    def eventHandler: EventH[Flow[Unit]]
  }

  trait CompilableDslProvider {
    def toCompilableDsl: CompilableDsl
  }

  type Guard[C <: Command] = (C => Boolean, String)

  object when {
    def apply[C <: Command : ClassTag] = WhenStatement[C](_ => true, List.empty)

    def apply[C <: Command : ClassTag](c: C) = WhenStatement[C](_ == c, List.empty)
  }

  object on {
    def apply[E <: Event : ClassTag](implicit ct: ClassTag[Command]) = ThenStatement[Command, E](PartialFunction.empty, _ => false, List.empty, _ => true)

    def apply[E <: Event : ClassTag](e: E)(implicit ct: ClassTag[Command]) = ThenStatement[Command, E](PartialFunction.empty, _ => false, List.empty, _ == e)
  }

  case class WhenStatement[C <: Command : ClassTag](commandMatcher: C => Boolean, guards: List[Guard[C]]) extends AllowFailingMessageStatement[C] {
    def emit[E <: Event](implicit cct: CaseClassTransformer[C, E], et: ClassTag[E]) =
      ThenStatement[C, E](eventFlowImpl.promoteCommandToEvents[C, E], commandMatcher, guards, _ => true)

    def emit[E <: Event](evs: E*)(implicit et: ClassTag[E]) =
      ThenStatement[C, E](handleWithSpecificCommandHandler(_ => evs.toList), commandMatcher, guards, _ == evs.head)

    def emitEvent[E <: Event](cmdHandler: C => E)(implicit et: ClassTag[E]) =
      ThenStatement[C, E](handleWithSpecificCommandHandler(((x: E) => List(x)).compose(cmdHandler)), commandMatcher, guards, _ => true)

    def emitEvents[E <: Event](cmdHandler: C => List[E])(implicit et: ClassTag[E]) =
      ThenStatement[C, E](handleWithSpecificCommandHandler(cmdHandler), commandMatcher, guards, _ => true)

    def guard(check: C => Boolean, message: String) = WhenStatement[C](commandMatcher, guards :+ ((check, message)))

    private def handleWithSpecificCommandHandler[E <: Event](cmdHandler: C => List[E]) =
      Function.unlift[C, List[E]] {
        case c: C => Some(cmdHandler(c))
        case _ => None
      }
  }

  case class ThenStatement[C <: Command : ClassTag, E <: Event : ClassTag](handler: PartialFunction[C, List[E]], commandMatcher: C => Boolean, guards: List[Guard[C]], eventMatcher: E => Boolean) extends CompilableDslProvider {
    def switch[A: ClassTag : upickle.default.Reader : upickle.default.Writer](where: E => FlowStateHandler[A]): SwitchToStatement[C, E, A] = SwitchToStatement[C, E, A](handler, commandMatcher, guards, Some(e => where(e)), eventMatcher)

    def switch[A: ClassTag : upickle.default.Reader : upickle.default.Writer](where: => FlowStateHandler[A]): SwitchToStatement[C, E, A] = switch(_ => where)

    def toCompilableDsl = SwitchToStatement[C, E, Unit](handler, commandMatcher, guards, None, eventMatcher).toCompilableDsl
  }

  case class SwitchToStatement[C <: Command : ClassTag, E <: Event : ClassTag, A: ClassTag: upickle.default.Reader : upickle.default.Writer](handler: PartialFunction[C, List[E]], commandMatcher: C => Boolean, guards: List[Guard[C]], switchTo: Option[E => FlowStateHandler[A]], eventMatcher: E => Boolean) extends CompilableDslProvider {
    def toCompilableDsl = new CompilableDsl {
      def commandHandler = {
        case c: C if commandMatcher(c) =>
          val errors = guards.flatMap(g => if (!g._1(c)) Some(ErrorCommandFailure(g._2)) else None)
          errors match {
            case err :: errs => Validated.invalid(NEL(err, errs))
            case Nil => handler.andThen(onHandledCommand)(c)
          }
      }

      def eventHandler = Function.unlift[Event, Flow[Unit]] {
        case e: E if eventMatcher(e) => switchTo.map(_(e)).map(toFlow)
        case _ => None
      }
    }

    private def onHandledCommand(evs: List[Event]) = switchTo.flatMap { switchHandler =>
      println(s"handled command, got $evs")
      evs.
        collectFirst(Function.unlift({ case ev: E => Some(switchHandler(ev)) })).
        flatMap {flowStateHandler =>
          println(s"event found flh: $flowStateHandler")
          val snapshot = FlowStateCall.createSnapshot(flowStateHandler)
          println(s"and snapshot is: $snapshot")
          snapshot.map(s => Aggregate.emitEventsWithSnapshot[Event, Snapshottable#FlowStateCall](evs, s)(FlowStateCall.snapshotSerializer))
        }
    }.getOrElse(Aggregate.emitEvents[Event](evs))
  }

  def anyOther = new AllowFailingMessageStatement[Command] {
    def commandMatcher = _ => true
  }

  trait AllowFailingMessageStatement[C <: Command] {
    def commandMatcher: C => Boolean

    def failWithMessage(msg: String)(implicit ct: ClassTag[C]) = FailWithMessageStateMent(commandMatcher, msg)
  }

  case class FailWithMessageStateMent[C <: Command : ClassTag](commandMatcher: C => Boolean, msg: String) extends CompilableDslProvider {
    def toCompilableDsl = new CompilableDsl {
      def commandHandler = {
        case c: C if commandMatcher(c) => Aggregate.failCommand(msg)
      }

      def eventHandler = PartialFunction.empty
    }
  }

  def handler(dsl: CompilableDslProvider*): Flow[Unit] = {
    val compilable = dsl.map(_.toCompilableDsl)
    val commandHandler = compilable.map(_.commandHandler).reduceLeft((prev, curr) => prev.orElse(curr))
    val eventHandler = compilable.map(_.eventHandler).reduceLeft((prev, curr) => prev.orElse(curr))
    for {
      _ <- eventFlowImpl.DslBase.handler(commandHandler)
      _ <- eventFlowImpl.DslBase.waitForAndSwitch(eventHandler)
    } yield ()
  }
}

abstract class EventFlow[Evt: EventSerialisation, Cmd] extends EventFlowBase[Evt, Cmd] with DslV1 {
  lazy val tag = {
    val name = this.getClass.getCanonicalName
    if (null == name) {
      throw new Error("Cannot find aggregate name, please implement tag manually.")
    }
    val simplifiedName = "[^a-zA-Z0-9_.-]".r.replaceAllIn(name, "")
    createTag(simplifiedName)
  }
}

