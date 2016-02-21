package Domain

import Cqrs._
import Cqrs.Aggregate._
import cats.Monad
import cats.data.{ Xor, XorT }
import cats.syntax.flatMap._

object Book {

  sealed trait Event
  final case class AddedToLibrary(id: AggregateId) extends Event
  final case class LentToReader(readerId: String, dueDate: Long) extends Event
  final case class Returned(readerId: String, returnedOn: Long, dueDate: Long) extends Event
  final case class Extended(readerId: String, dueDate: Long) extends Event


  sealed trait Command
  final case class AddToLibrary(id: AggregateId) extends Command
  final case class LendToReader(readerId: String, dueDate: Long) extends Command
  case object Return extends Command
  final case class Extend(dueDate: Long) extends Command

  val flow = new EventFlow[Command, Event]
  import flow._

  private def bookAvailableLogic: Flow[Unit] =
    handler {
      case LendToReader(readerId, dueDate) => emitEvent(LentToReader(readerId, dueDate))
      case Extend(_) => failCommand("lalala no!")
    } >>
    waitForAndSwitch {
      case LentToReader(readerId, dueDate) => bookLentLogic(readerId, dueDate)
    }

  private def bookLentLogic(readerId: String, dueDate: Long): Flow[Unit] =
    handler {
      case Return => emitEvent(Returned(readerId, currentTime, dueDate))
      case Extend(dueDateToExtend) => emitEvent(Extended(readerId, dueDateToExtend))
    } >>
    waitForAndSwitch {
      case Returned(_, _, _) => bookAvailableLogic
      case Extended(readerIdThatExtended, dueDateToExtend) => bookLentLogic(readerIdThatExtended, dueDateToExtend)
    }

  private val fullAggregateLogic: List[Flow[Unit]] = List(
    handler { case AddToLibrary(id) => emitEvent(AddedToLibrary(id)) }
      >> waitFor { case AddedToLibrary(_) => () }
      >> bookAvailableLogic
  )

  object BookAggregate extends FlowAggregate {
    def tag = Tag("Book")
    def aggregateLogic = fullAggregateLogic
    def initCmd = AddToLibrary
  }

  private def currentTime = System.currentTimeMillis()
}

