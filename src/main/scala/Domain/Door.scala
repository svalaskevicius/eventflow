package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.data.{ Xor, XorT }
import cats.syntax.flatMap._

object Door {

  val tag = Tag("Door")

  sealed trait Event
  final case class Registered(id: AggregateId) extends Event
  case object Opened extends Event
  case object Closed extends Event
  final case class Locked(key: String) extends Event
  final case class Unlocked(key: String) extends Event

  sealed trait Command
  final case class Register(id: AggregateId) extends Command
  case object Open extends Command
  case object Close extends Command
  final case class Lock(key: String) extends Command
  final case class Unlock(key: String) extends Command

  val flow = new EventFlow[Command, Event]
  import flow._

  private def openDoorsLogic: Flow[Unit] =
    handler {
      promote[Close.type, Closed.type] orElse
        { case _ => failCommand("Open door can only be closed.") }
    } >>
      waitForAndSwitch {
        case Closed => closedDoorsLogic
      }

  private def closedDoorsLogic: Flow[Unit] =
    handler {
      promote[Lock, Locked] orElse
      promote[Open.type, Opened.type] orElse
        { case _ => failCommand("Closed door can only be opened or locked.") }
    } >>
      waitForAndSwitch {
        case Opened => openDoorsLogic
        case Locked(key) => lockedDoorsLogic(key)
      }

  private def lockedDoorsLogic(key: String): Flow[Unit] =
    handler {
      case Unlock(attemptedKey) => if (key == attemptedKey) emitEvent(Unlocked(attemptedKey))
                                   else failCommand("Attempted unlock key is invalid")
      case _ => failCommand("Locked door can only be unlocked.")
    } >>
      waitForAndSwitch {
        case Unlocked(_) => closedDoorsLogic
      }

  private val aggregateLogic: List[Flow[Unit]] = List(
    handler { promote[Register, Registered] } >> waitFor { case Registered(_) => () },
    waitFor { case Registered(_) => () } >> openDoorsLogic
  )

  type DoorAggregate = FlowAggregate
  val doorAggregate = flowAggregate(tag, aggregateLogic)

  def newDoor(id: AggregateId) : EventDatabaseWithFailure[Event, DoorAggregate#State] =
    doorAggregate.handleFirstCommand(id, Register(id))
}

import scala.collection.immutable.TreeMap

object DoorProjection {

  sealed trait State
  case object Open extends State
  case object Closed extends State
  final case class Locked(key: String) extends State

  type Data = TreeMap[AggregateId, State]

  def emptyDoorProjection = Projection.build.
    addHandler(Door.tag, (d: Data, e: Database.EventData[Door.Event]) => {
      import Door._
      e.data match {
        case Registered(id) => d.updated(e.id, DoorProjection.Open)
        case Door.Closed => d.updated(e.id, DoorProjection.Closed)
        case Door.Opened => d.updated(e.id, DoorProjection.Open)
        case Door.Locked(key) => d.updated(e.id, DoorProjection.Locked(key))
        case Door.Unlocked(_) => d.updated(e.id, DoorProjection.Closed)
      }
    }).empty(TreeMap.empty)
}

