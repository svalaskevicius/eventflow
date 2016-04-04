package Domain

import Cqrs.Aggregate._
import Cqrs.Database.EventData
import Cqrs._
import Domain.Door._

object Door {

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
}

object DoorAggregate extends EventFlow[Event, Command] {

  private val openDoors: RegisteredFlowStateAux[Unit] = ref('open, handler(
    when(Close).emit(Closed).switch(closedDoors)
  ))

  private val closedDoors: RegisteredFlowStateAux[Unit] = ref('closed, handler(
    when(Open).emit(Opened).switch(openDoors),
    when[Lock].emit[Locked].switchByEvent(ev => ev.key -> lockedDoors)
  ))

  private val lockedDoors: RegisteredFlowStateAux[String] = ref('locked, key => handler(
    when(Unlock(key)).emit[Unlocked].switch(closedDoors),
    when[Unlock].failWithMessage("Attempted unlock key is invalid"),
    anyOther.failWithMessage("Locked door can only be unlocked.")
  ))

  // unused, here just for dsl examples
  private def lockedDoorsAlternativeExamples(key: String): Flow[Unit] = handler(
    when(Unlock(key)).emitEvent(cmd => Unlocked(cmd.key)).switch(closedDoors), // alternative to `when(Unlock(key)).emit[Unlocked]`
    on(Unlocked(key)).switch(closedDoors), // alternative to `emit[Unlocked].switch(closedDoors)`
    on(Unlocked(key)).switchByEvent(evt => closedDoors), // alternative to above
    on[Unlocked].switchByEvent(evt => closedDoors), // alternative to above
    on[Unlocked].switch(closedDoors), // alternative to above
    when[Unlock].failWithMessage("Attempted unlock key is invalid"),
    anyOther.failWithMessage("Locked door can only be unlocked.")
  )

  val snapshottableStates: FlowStates = List(openDoors, lockedDoors, closedDoors)

  val aggregateLogic: Flow[Unit] = handler(
    when[Register].emit[Registered].switch(openDoors)
  )

}

import scala.collection.immutable.TreeMap

sealed trait DoorState

object DoorState {

  case object Open extends DoorState

  case object Closed extends DoorState

  case object Locked extends DoorState

}

object DoorProjection extends Projection[TreeMap[AggregateId, DoorState]] {

  def initialData = TreeMap.empty

  val listeningFor = List(DoorAggregate.tag)

  def accept[E](d: Data) = {
    case EventData(_, id, _, Registered(_)) => d + (id -> DoorState.Open)
    case EventData(_, id, _, Closed)        => d + (id -> DoorState.Closed)
    case EventData(_, id, _, Opened)        => d + (id -> DoorState.Open)
    case EventData(_, id, _, Locked(_))     => d + (id -> DoorState.Locked)
    case EventData(_, id, _, Unlocked(_))   => d + (id -> DoorState.Closed)
  }
}

