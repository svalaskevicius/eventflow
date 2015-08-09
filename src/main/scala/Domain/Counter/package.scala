package Domain

import Cqrs._
import cats.data.Xor
import cats.Monad
import cats._
import cats.std.all._
import cats.syntax.flatMap._

package object Counter {

  sealed trait CounterEvent extends Event
  case class Created(id: String) extends CounterEvent
  case object Incremented extends CounterEvent

  sealed trait Command
  case class Create(id: String) extends Command
  case object Increment extends Command

  final case class CounterState(created: Boolean) {
    def this() = this(created = false)
  }

  type CounterAggregate = Aggregate[CounterEvent, Command, CounterState]

  def createCounter(id: String): List[String] Xor CounterAggregate = {

    import Cqrs.Aggregate._

    val c = new CounterAggregate(
      id = id,
      state = new CounterState(),
      on = {
        case Created(id) => _.copy(created = true)
        case Incremented => identity
      },
      handle = {
        case Create(id) => state =>
          if (state.created) failCommand("Counter has been created already.")
          else emitEvent(Created(id))
        case Increment => state =>
          if (state.created) emitEvent(Incremented)
          else failCommand("Counter has not been created yet.")
      }
    )

    c.handleCommand(Create(id)) map (_ => c)
  }








  type CommandH = Command => Option[List[String] Xor List[CounterEvent]]

  def emptyCmdH(c: Command) = None

  final case class Mu[F[_]](value: F[Mu[F]])

  type EventStreamReaderStep[A] = {type λ[α] = (CommandH => Event => (Option[A], CommandH, α))}
  type EventStreamReader[A] = Mu[EventStreamReaderStep[A]#λ]

  type EventStreamRunnerStep[A] = {type λ[α] = (CommandH, Event => (Option[A], α))}
  type EventStreamRunner[A] = Mu[EventStreamRunnerStep[A]#λ]
  type EventStreamRunnerHead[A] = CommandH => EventStreamRunner[A]

  implicit object streamReaderToRunner extends (EventStreamReader ~> EventStreamRunnerHead) {
    def apply[A](fa: EventStreamReader[A]): EventStreamRunnerHead[A] = (cmdh:CommandH) => Mu[EventStreamRunnerStep[A]#λ](
      cmdh,
      (evt : Event) => {
        val ret = fa.value(cmdh)(evt)
        (ret._1, apply(ret._3)(ret._2))
      }
    )
  }


  def emptyEventStreamReader[A]() : EventStreamReader[A] = {
    lazy val self:EventStreamReader[A] = Mu[EventStreamReaderStep[A]#λ]( (cmd:CommandH) => (_:Event) => (None, cmd, self))
    self
  }
  def unitEventStreamReader[A](x:A) : EventStreamReader[A] = {
    lazy val self:EventStreamReader[A] = Mu[EventStreamReaderStep[A]#λ]( (cmd:CommandH) => (_:Event) => (Some(x), cmd, self))
    self
  }

  implicit val monadEventStreamReader: Monad[EventStreamReader] =
    new Monad[EventStreamReader] {
      def pure[A](x: A): EventStreamReader[A] = unitEventStreamReader(x)
      def flatMap[A, B](fa: EventStreamReader[A])(f: A => EventStreamReader[B]): EventStreamReader[B] = {
        lazy val self: EventStreamReader[B] = Mu[EventStreamReaderStep[B]#λ]((cmd: CommandH) => (e:Event) => fa.value(cmd)(e) match {
          case (None, cmd2, _) => (None, cmd2, self)
          case (Some(a), cmd2, _) => (None, cmd2, f(a))
        })
        self
      }
    }

  def after[A](eh: Event => Option[A]): EventStreamReader[A] = {
    lazy val self: EventStreamReader[A] = Mu[EventStreamReaderStep[A]#λ]((origCmd: CommandH) => (e:Event) => eh(e) match {
      case None => (None, origCmd, self)
      case Some(a) => (Some(a), origCmd, unitEventStreamReader(a))
    })
    self
  }

  def until[A](eh: Event => Option[A]): EventStreamReader[A] = {
    lazy val self: EventStreamReader[A] = Mu[EventStreamReaderStep[A]#λ]((origCmd: CommandH) => (e:Event) => eh(e) match {
      case None => (None, origCmd, self)
      case Some(a) => (Some(a), emptyCmdH, unitEventStreamReader(a))
    })
    self
  }

  def handler(cmdh:CommandH): EventStreamReader[Unit] = {
    lazy val self:EventStreamReader[Unit] = Mu[EventStreamReaderStep[Unit]#λ]( (_:CommandH) => (_:Event) => (Some(()), cmdh, self))
    self
  }



  type Counter2Aggregate = Aggregate[CounterEvent, Command, List[EventStreamRunner[Unit]]]
  def createCounter2(id: String): List[String] Xor Counter2Aggregate = {

    import Cqrs.Aggregate._

    val aggregateLogic: List[EventStreamReader[Unit]] = List(
      after({ case Created(_) => Some(()) }) >>
      handler({
        case Increment => Some(emitEvent(Incremented))
        case _ => None
      }),
      handler({
        case Create(id) => Some(emitEvent(Created(id)))
        case _ => None
      }) >>
      until({ case Created(_) => Some(())})
    )
    println(aggregateLogic map streamReaderToRunner.apply)

    val c = new Aggregate[CounterEvent, Command, List[EventStreamRunner[Unit]]](
      id = id,
      state = (aggregateLogic map streamReaderToRunner.apply) map (rh => rh(emptyCmdH)),
      on = e => d => d map (runner => runner.value._2(e)._2),
      handle = c => d => d.foldLeft(None: Option[List[String] Xor List[CounterEvent]])(
          (prev:Option[List[String] Xor List[CounterEvent]], runner) => prev match {
            case Some(_) => prev
            case None => runner.value._1(c)
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
