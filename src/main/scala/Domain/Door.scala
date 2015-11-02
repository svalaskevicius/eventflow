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
  type DoorAggregate = FlowAggregate
  val doorAggregate = flowAggregate(tag)

  def openDoorsLogic: Flow[Unit] =
    handler {
      promote[Close.type, Closed.type] orElse
        { case _ => failCommand("Open door can only be closed.") }
    } >>
      waitForAndSwitch {
        case Closed => closedDoorsLogic
      }

  def closedDoorsLogic: Flow[Unit] =
    handler {
      promote[Lock, Locked] orElse
      promote[Open.type, Opened.type] orElse
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
    handler { promote[Register, Registered] } >> waitFor { case Registered(_) => () },
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

  def emptyDoorProjection = Projection.empty[Data](new TreeMap(), List(
    (Door.tag, Database.createEventDataConsumer( (d: Data, t: Tag, id: AggregateId, v: Int, e: Door.Event) => {
      import Door._
      e match {
        case Registered(id) => d.updated(id, DoorProjection.Open)
        case Door.Closed => d.updated(id, DoorProjection.Closed)
        case Door.Opened => d.updated(id, DoorProjection.Open)
        case Door.Locked(key) => d.updated(id, DoorProjection.Locked(key))
        case Door.Unlocked(_) => d.updated(id, DoorProjection.Closed)
      }}
    ))
  ))
}

