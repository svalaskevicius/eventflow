package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.data.{ Xor, XorT }
import cats.syntax.flatMap._

object Door {
  sealed trait Event
  final case class Registered(id: String) extends Event
  case object Opened extends Event
  case object Closed extends Event
  final case class Locked(key: String) extends Event
  final case class Unlocked(key: String) extends Event

  sealed trait Command
  final case class Register(id: String) extends Command
  case object Open extends Command
  case object Close extends Command
  final case class Lock(key: String) extends Command
  final case class Unlock(key: String) extends Command

  val flow = new EventFlow[Command, Event]
  import flow._
  type DoorAggregate = FlowAggregate
  val doorAggregate = flowAggregate

  def openDoorsLogic: Flow[Unit] =
    handler {
      handler[Close.type, Closed.type] orElse
        { case _ => failCommand("Open door can only be closed.") }
    } >>
      waitForAndSwitch {
        case Closed => closedDoorsLogic
      }

  def closedDoorsLogic: Flow[Unit] =
    handler {
      handler[Lock, Locked] orElse
      handler[Open.type, Opened.type] orElse
        { case _ => failCommand("Closed door can only be opened or locked.") }
    } >>
      waitForAndSwitch {
        case Opened => openDoorsLogic
        case Locked(key) => lockedDoorsLogic(key)
      }

  def lockedDoorsLogic(key: String): Flow[Unit] =
    handler {
      case Unlock(attemptedKey) => if (key == attemptedKey) emitEvent(Unlocked(attemptedKey))
                                   else failCommand("Attempted unlock key is invalid")
      case _ => failCommand("Locked door can only be unlocked.")
    } >>
      waitForAndSwitch {
        case Unlocked(_) => closedDoorsLogic
      }

  val aggregateLogic: List[Flow[Unit]] = List(
    handler { handler[Register, Registered] } >> waitFor { case Registered(_) => () },
    waitFor { case Registered(_) => () } >> openDoorsLogic
  )

  def newDoor(id: AggregateId): EAD[Unit] = {
    import doorAggregate._
    initAggregate(id) >> handleCommand(Register(id))
  }

  def registerDoor = startFlow[Unit](aggregateLogic) _ compose newDoor
}

import scala.collection.immutable.TreeMap

object DoorProjection {

  sealed trait State
  case object Open extends State
  case object Closed extends State
  final case class Locked(key: String) extends State

  type Data = TreeMap[AggregateId, State]

  def emptyDoorProjection = Projection.empty[Data](new TreeMap())

  implicit object DoorHandler extends Projection.Handler[Door.Event, Data] {

    import Door._

    def hashPrefix = "Door"

    def handle(id: AggregateId, e: Event, d: Data) = e match {
      case Registered(id) => println ("created "+id) ; d.updated(id, DoorProjection.Open)
      case Door.Closed => println ("closed") ; d.updated(id, DoorProjection.Closed)
      case Door.Opened => println ("opened") ; d.updated(id, DoorProjection.Open)
      case Door.Locked(key) => println ("locked") ; d.updated(id, DoorProjection.Locked(key))
      case Door.Unlocked(_) => println ("unlocked") ; d.updated(id, DoorProjection.Closed)
    }
  }
}

