package Cqrs

import Cqrs.Aggregate.{ ErrorCannotFindHandler, ErrorCommandFailure, CommandHandlerResult, EventHandlerResult }
import Cqrs.Database.Serializable
import cats._
import cats.data.{ NonEmptyList => NEL, _ }
import cats.free.Free
import cats.free.Free.liftF
import lib.CaseClassTransformer

import scala.reflect.ClassTag

class EventFlowImpl[Evt, Cmd] {
  type CommandH = PartialFunction[Cmd, CommandHandlerResult[Evt]]
  type EventH[A] = PartialFunction[Evt, EventHandlerResult[A]]

  sealed trait FlowF[+Next]

  case class SetCommandHandler[Next](cmdh: CommandH, next: Next) extends FlowF[Next]

  case class EventHandler[Next, A](evth: EventH[A], whenHandled: A => Next) extends FlowF[Next]

  implicit object FlowFunctor extends Functor[FlowF] {
    def map[A, B](fa: FlowF[A])(f: A => B): FlowF[B] = fa match {
      case ch: SetCommandHandler[A] => SetCommandHandler[B](ch.cmdh, f(ch.next))
      case eh: EventHandler[A, t]   => EventHandler[B, t](eh.evth, f compose eh.whenHandled)
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
  def promoteCommandToEvents[C <: Cmd: ClassTag, E <: Evt](implicit cct: CaseClassTransformer[C, E]): PartialFunction[Cmd, List[E]] =
    Function.unlift {
      case c: C => Some(cct.transform(c)).map(List(_))
      case _    => None
    }

  object DslBase {
    def handler(ch: CommandH): Flow[Unit] = liftF(SetCommandHandler(ch, ()))

    def waitFor[A](eh: EventH[A]): Flow[A] = liftF(EventHandler[A, A](eh, identity))

    def waitForAndSwitch[A](eh: EventH[Flow[A]]): Flow[A] = waitFor(eh).flatMap((cont: Flow[A]) => cont)

    def runForever(): Flow[Unit] = waitFor(PartialFunction.empty[Evt, Aggregate.JustData[Unit]])
  }

  case class EventStreamConsumer(cmdh: CommandH, evh: Evt => EventHandlerResult[Option[EventStreamConsumer]])

  type StateData = Option[EventStreamConsumer]

  def esRunnerCompiler[A](initCmdH: CommandH, esRunner: Flow[A]): StateData =
    esRunner.fold(
      _ => None, {
        case SetCommandHandler(cmdh, next) => esRunnerCompiler(cmdh, next)
        case EventHandler(evth, cont) =>
          lazy val self: EventStreamConsumer = EventStreamConsumer(
            initCmdH,
            (ev: Evt) => evth.lift(ev) map { res =>
              val newData = esRunnerCompiler(initCmdH, cont(res.aggregateData))
              res match {
                case Aggregate.JustData(_)                      => Aggregate.JustData(newData)
                case r @ Aggregate.DataAndSnapshot(_, snapshot) => Aggregate.DataAndSnapshot(newData, snapshot)(r.serializer)
              }
            } getOrElse Aggregate.JustData(Some(self))
          )
          Some(self)
      }
    )

}

trait Snapshottable extends AggregateBase {

  val eventFlowImpl: EventFlowImpl[Event, Command]

  import eventFlowImpl.Flow

  type FlowState[T] = Function1[T, Flow[Unit]]

  trait RegisteredFlowState {
    type StateParam
    implicit def r: upickle.default.Reader[StateParam]
    implicit def w: upickle.default.Writer[StateParam]
    def classTag: ClassTag[StateParam]
    def state: FlowState[StateParam]
    def name: Symbol
    def apply(param: StateParam): FlowStateCallAux[StateParam] = {
      type SP = StateParam
      new FlowStateCall {
        type StateParam = SP
        def state = name
        val arg = param
      }
    }
  }
  type RegisteredFlowStateAux[T] = RegisteredFlowState { type StateParam = T }

  object RegisteredFlowState {
    implicit def registerFlowState[T: ClassTag: upickle.default.Reader: upickle.default.Writer](stateName: Symbol, flowState: FlowState[T]): RegisteredFlowStateAux[T] = new RegisteredFlowState {
      type StateParam = T
      val r = implicitly[upickle.default.Reader[T]]
      val w = implicitly[upickle.default.Writer[T]]
      val classTag = implicitly[ClassTag[T]]
      val state = flowState
      val name = stateName
    }
  }

  type FlowStates = List[RegisteredFlowState]

  val snapshottableStates: FlowStates

  lazy val snapshottableStatesMap = snapshottableStates.map(x => x.name -> x).toMap

  sealed trait FlowStateCall {
    type StateParam
    def state: Symbol
    def arg: StateParam
  }

  type FlowStateCallAux[A] = FlowStateCall { type StateParam = A }

  object FlowStateCall {

    lazy val snapshotSerializer: Database.Serializable[Snapshottable#FlowStateCall] = {
      new Database.Serializable[Snapshottable#FlowStateCall] {
        val serializer = new SerializerWriter {
          val write0 = (a: Snapshottable#FlowStateCall) => {
            val registeredState = snapshottableStatesMap.get(a.state).get
            a.arg match {
              case registeredState.classTag(arg) =>
                upickle.Js.Obj(
                  "state" -> upickle.default.SymbolRW.write0(a.state),
                  "arg" -> registeredState.w.write(arg)
                )
            }
          }
        }

        val unserializer = new SerializerReader {
          val read0: PartialFunction[Serialized, Snapshottable#FlowStateCall] =
            Function.unlift { json =>
              val symbol = symbolReader.read(json)
              snapshottableStatesMap.get(symbol).map { flowState =>
                val readArg = argReader(flowState.r).read(json) //TODO: Try
                new FlowStateCall {
                  type StateParam = flowState.StateParam
                  val state = symbol
                  val arg = readArg
                }
              }
            }

          private val symbolReader = new upickle.default.Reader[Symbol] {
            val read0: PartialFunction[upickle.Js.Value, Symbol] =
              Function.unlift {
                case obj: upickle.Js.Obj => Some(upickle.default.SymbolRW.read0(obj("state")))
                case _                   => None
              }
          }

          private def argReader[T](reader: upickle.default.Reader[T]) = new upickle.default.Reader[T] {
            val read0: PartialFunction[upickle.Js.Value, T] =
              Function.unlift {
                case obj: upickle.Js.Obj => obj.value.toList.collectFirst(Function.unlift {
                  case (name, value) if name.equals("arg") => reader.read.lift(value)
                  case _                                   => None
                })
                case _ => None
              }
          }
        }
      }
    }
  }

  def compileSnapshot(s: FlowStateCall): Option[eventFlowImpl.StateData] =
    toFlow(s).map(flow => eventFlowImpl.esRunnerCompiler(PartialFunction.empty, flow))

  def toFlow(s: FlowStateCall): Option[Flow[Unit]] =
    snapshottableStatesMap.get(s.state).flatMap { flowState =>
      s.arg match {
        case flowState.classTag(arg) => Some(flowState.state(arg))
        case _                       => None
      }
    }

}

trait EventFlowBase[Evt, Cmd] extends Aggregate[Evt, Cmd, EventFlowImpl[Evt, Cmd]#StateData, Snapshottable#FlowStateCall] with Snapshottable {

  val eventFlowImpl = new EventFlowImpl[Evt, Cmd]

  type Flow[A] = eventFlowImpl.Flow[A]

  def convertSnapshotToData(s: AggregateSnapshot): Option[AggregateData] = {
    val ct = implicitly[ClassTag[FlowStateCall]]
    s match {
      case ct(sarg) => compileSnapshot(sarg)
      case _        => None
    }
  }

  val snapshotSerializer = FlowStateCall.snapshotSerializer

  def aggregateLogic: Flow[Unit]

  def eventHandler = e => d => d match {
    case Some(eFlow) => eFlow.evh(e)
    case _           => Aggregate.JustData(None)
  }

  def commandHandler = c => d => d.foldLeft(None: Option[CommandHandlerResult[Evt]])(
    (prev: Option[CommandHandlerResult[Evt]], consumer) => prev match {
      case Some(_) => prev
      case None    => consumer.cmdh.lift(c)
    }
  ).
    getOrElse {
      Validated.invalid(NEL.of(ErrorCannotFindHandler(c.toString)))
    }

  def initData = eventFlowImpl.esRunnerCompiler(PartialFunction.empty, aggregateLogic)
}

trait DslV1 { self: AggregateBase with Snapshottable =>

  val eventFlowImpl: EventFlowImpl[Event, Command]

  import eventFlowImpl.{ Flow, CommandH, EventH }

  trait CompilableDsl {
    def commandHandler: CommandH

    def eventHandler: EventH[Flow[Unit]]
  }

  trait CompilableDslProvider {
    def toCompilableDsl: CompilableDsl
  }

  type Guard[C <: Command] = (C => Boolean, String)

  object when {
    def apply[C <: Command: ClassTag] = WhenStatement[C](_ => true, List.empty)

    def apply[C <: Command: ClassTag](c: C) = WhenStatement[C](_ == c, List.empty)
  }

  object on {
    def apply[E <: Event: ClassTag](implicit ct: ClassTag[Command]) = ThenStatement[Command, E](PartialFunction.empty, _ => false, List.empty, _ => true)

    def apply[E <: Event: ClassTag](e: E)(implicit ct: ClassTag[Command]) = ThenStatement[Command, E](PartialFunction.empty, _ => false, List.empty, _ == e)
  }

  case class WhenStatement[C <: Command: ClassTag](commandMatcher: C => Boolean, guards: List[Guard[C]]) extends AllowFailingMessageStatement[C] {
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
        case _    => None
      }
  }

  sealed trait SwitchCallTarget[T] {
    def flow(t: T): Flow[Unit]
    def flowCall(t: T): Option[FlowStateCall]
  }

  object SwitchCallTarget {
    implicit val forFlow: SwitchCallTarget[Flow[Unit]] = new SwitchCallTarget[Flow[Unit]] {
      def flow(t: Flow[Unit]) = t
      def flowCall(t: Flow[Unit]) = None
    }
    implicit def forRegisteredFlowState[A]: SwitchCallTarget[(A, RegisteredFlowStateAux[A])] = new SwitchCallTarget[(A, RegisteredFlowStateAux[A])] {
      def flow(t: (A, RegisteredFlowStateAux[A])) = toFlow(flowCallDirect(t)).get
      def flowCall(t: (A, RegisteredFlowStateAux[A])) = Some(flowCallDirect(t))
      private def flowCallDirect(t: (A, RegisteredFlowStateAux[A])) = t._2(t._1)
    }
    implicit def forRegisteredFlowStateUnit[A](implicit unitSct: SwitchCallTarget[(Unit, RegisteredFlowStateAux[Unit])]): SwitchCallTarget[RegisteredFlowStateAux[Unit]] = new SwitchCallTarget[RegisteredFlowStateAux[Unit]] {
      def flow(t: RegisteredFlowStateAux[Unit]) = unitSct.flow(() -> t)
      def flowCall(t: RegisteredFlowStateAux[Unit]) = unitSct.flowCall(() -> t)
    }
  }

  case class ThenStatement[C <: Command: ClassTag, E <: Event: ClassTag](handler: PartialFunction[C, List[E]], commandMatcher: C => Boolean, guards: List[Guard[C]], eventMatcher: E => Boolean) extends CompilableDslProvider {

    def switchByEvent[T: ClassTag](where: E => T)(implicit sct: SwitchCallTarget[T]): SwitchToStatement[C, E] = SwitchToStatement[C, E](handler, commandMatcher, guards, Some((e: E) => sct.flow(where(e))), Some((e: E) => sct.flowCall(where(e))), eventMatcher)

    def switch[T: ClassTag](where: => T)(implicit sct: SwitchCallTarget[T]): SwitchToStatement[C, E] = switchByEvent((_: E) => where)

    def toCompilableDsl = SwitchToStatement[C, E](handler, commandMatcher, guards, None, None, eventMatcher).toCompilableDsl

  }

  case class SwitchToStatement[C <: Command: ClassTag, E <: Event: ClassTag](handler: PartialFunction[C, List[E]], commandMatcher: C => Boolean, guards: List[Guard[C]], switchTo: Option[E => Flow[Unit]], snapshot: Option[E => Option[FlowStateCall]], eventMatcher: E => Boolean) extends CompilableDslProvider {
    def toCompilableDsl = new CompilableDsl {
      def commandHandler = {
        case c: C if commandMatcher(c) =>
          val errors = guards.flatMap(g => if (!g._1(c)) Some(ErrorCommandFailure(g._2)) else None)
          errors match {
            case err :: errs => Validated.invalid(NEL(err, errs))
            case Nil         => handler.andThen(Aggregate.emitEvents)(c)
          }
      }

      def eventHandler = Function.unlift[Event, EventHandlerResult[Flow[Unit]]] {
        case e: E if eventMatcher(e) => switchTo.map { handler =>
          val newData = handler(e)
          snapshot.flatMap(_(e)) match {
            case Some(snapshotData) => Aggregate.DataAndSnapshot[Flow[Unit], Snapshottable#FlowStateCall](newData, snapshotData)(FlowStateCall.snapshotSerializer)
            case None               => Aggregate.JustData(newData)
          }
        }
        case _ => None
      }
    }
  }

  def anyOther = new AllowFailingMessageStatement[Command] {
    def commandMatcher = _ => true
  }

  trait AllowFailingMessageStatement[C <: Command] {
    def commandMatcher: C => Boolean

    def failWithMessage(msg: String)(implicit ct: ClassTag[C]) = FailWithMessageStateMent(commandMatcher, msg)
  }

  case class FailWithMessageStateMent[C <: Command: ClassTag](commandMatcher: C => Boolean, msg: String) extends CompilableDslProvider {
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

  def ref[A: ClassTag: upickle.default.Reader: upickle.default.Writer](state: Symbol, flowState: FlowState[A]): RegisteredFlowStateAux[A] = RegisteredFlowState.registerFlowState[A](state, flowState)

  def ref(state: Symbol, flowState: Flow[Unit]): RegisteredFlowStateAux[Unit] = RegisteredFlowState.registerFlowState[Unit](state, (_: Unit) => flowState)

}

abstract class EventFlow[Evt: Serializable, Cmd] extends EventFlowBase[Evt, Cmd] with DslV1 {
  lazy val tag = {
    val name = this.getClass.getCanonicalName
    if (null == name) {
      throw new Error("Cannot find aggregate name, please implement tag manually.")
    }
    val simplifiedName = "[^a-zA-Z0-9_.-]".r.replaceAllIn(name, "")
    createTag(simplifiedName)
  }
}

