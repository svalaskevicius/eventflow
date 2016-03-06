package Domain

import Cqrs.Aggregate._
import Cqrs._

object Door {

  sealed trait Event
  final case class Registered(id: AggregateId) extends Event
  case object Opened extends Event
  case object Closed extends Event
  final case class Locked(key: String) extends Event
  final case class Unlocked(key: String) extends Event

  sealed trait Command
  final case class Register(id: AggregateId) extends Command with InitialAggregateCommand
  case object Open extends Command
  case object Close extends Command
  final case class Lock(key: String) extends Command
  final case class Unlock(key: String) extends Command

  val flow = new EventFlow[Command, Event]
  import flow.{ Flow, FlowAggregate }
  import flow.DslV1._

  private def openDoors: Flow[Unit] = handler(
    when(Close).emit(Closed).switch(closedDoors)
  )

  private def closedDoors: Flow[Unit] = handler(
    when(Open).emit(Opened),
    when[Lock].emit[Locked].switch(ev => lockedDoors(ev.key))
  )

  private def lockedDoors(key: String): Flow[Unit] = handler(
    when(Unlock(key)).emit[Unlocked].switch(closedDoors),
    on(Unlocked(key)).switch(closedDoors), // alternative to above
    on(Unlocked(key)).switch(evt => closedDoors), // alternative to above
    on[Unlocked].switch(evt => closedDoors), // alternative to above
    on[Unlocked].switch(closedDoors), // alternative to above
    when[Unlock].failWithMessage("Attempted unlock key is invalid"),
    anyOther.failWithMessage("Locked door can only be unlocked.")
  )

  private val fullAggregate: Flow[Unit] = handler(
    when[Register].emit[Registered].switch(openDoors)
  )

  object DoorAggregate extends FlowAggregate {
    def tag = Tag("Door")
    def aggregateLogic = fullAggregate
  }
}

import scala.collection.immutable.TreeMap

object DoorProjection {

  sealed trait State
  case object Open extends State
  case object Closed extends State
  final case class Locked(key: String) extends State

  type Data = TreeMap[AggregateId, State]

  def emptyDoorProjection = Projection.build("doors").
    addHandler(Door.DoorAggregate.tag, (d: Data, e: Database.EventData[Door.Event]) => {
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

