import java.awt.print.Book

import Cqrs.Aggregate._
import Cqrs.Database.EventSerialisation
import Domain.Door
import org.scalacheck.{Gen, Properties}

object DoorSpecification extends Properties("Door") {

  property("full cycle test") = DoorSpec.property()

  sealed trait TestState
  object TestState {
    case object Closed extends TestState
    case object Open extends TestState
    case class Locked(key: String) extends TestState
  }

  private object DoorSpec extends AggregateCommands[Door.Event, Door.flow.StateData, TestState] {

    override val genInitialState = Gen.const(TestState.Open)
    override def initSutActions = Door.registerDoor(AggregateId("test door"))

    def genCommand(state: State): Gen[Command] = {
      val randomUnlock = Gen.alphaStr.flatMap(randkey => Gen.const(Unlock(randkey)))
      val unlockCmd = state match {
        case TestState.Locked(key) => Gen.oneOf[Command](randomUnlock, Unlock(key))
        case _ => randomUnlock
      }
      Gen.oneOf(
        Gen.const(Open),
        Gen.const(Close),
        Gen.alphaStr.flatMap(key => Gen.const(Lock(key))),
        unlockCmd
      )
    }

    override def evtSerializer = implicitly[EventSerialisation[Door.Event]]

    case object Open extends AggregateCommand {
      def commandActions = Door.doorAggregate.handleCommand(Door.Open)
      def nextState(state: State) = if (state == TestState.Closed) TestState.Open else state
      def preCondition(state: State) = true
      def postCondition(state: State, success: Boolean) = success == (state == TestState.Closed)
    }

    case object Close extends AggregateCommand {
      def commandActions = Door.doorAggregate.handleCommand(Door.Close)
      def nextState(state: State) = if (state == TestState.Open) TestState.Closed else state
      def preCondition(state: State) = true
      def postCondition(state: State, success: Boolean) = success == (state == TestState.Open)
    }

    case class Lock(key: String) extends AggregateCommand {
      def commandActions = Door.doorAggregate.handleCommand(Door.Lock(key))
      def nextState(state: State) = if (state == TestState.Closed) TestState.Locked(key) else state
      def preCondition(state: State) = true
      def postCondition(state: State, success: Boolean) = success == (state == TestState.Closed)
    }

    case class Unlock(key: String) extends AggregateCommand {
      def commandActions = Door.doorAggregate.handleCommand(Door.Unlock(key))
      def nextState(state: State) = if (state == TestState.Locked(key)) TestState.Closed else state
      def preCondition(state: State) = true
      def postCondition(state: State, success: Boolean) = success == (state == TestState.Locked(key))
    }
  }
}