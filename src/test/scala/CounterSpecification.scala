import Cqrs.Aggregate._
import Cqrs.Database.EventSerialisation
import Domain.Counter
import org.scalacheck.{Properties, Gen}

object CounterSpecification extends Properties("Counter") {

  property("fails when below zero") = CounterSpec.property()

  private object CounterSpec extends AggregateCommands[Counter.Event, Counter.flow.StateData, Int] {

    override val genInitialState = Gen.const(0)
    override def initSutActions = Counter.CounterAggregate.initAggregate(AggregateId("test counter"))

    def genCommand(state: State): Gen[Command] =
      Gen.oneOf(
        Gen.const(Increment),
        Gen.const(Decrement)
      )

    override def evtSerializer = implicitly[EventSerialisation[Counter.Event]]

    case object Increment extends AggregateCommand {
      def commandActions = Counter.CounterAggregate.handleCommand(Counter.Increment)
      def nextState(state: State) = state + 1
      def postCondition(state: State, success: Boolean) = success
    }

    case object Decrement extends AggregateCommand {
      def commandActions = Counter.CounterAggregate.handleCommand(Counter.Decrement)
      def nextState(state: State) = if (state > 0) state - 1 else state
      def postCondition(state: State, success: Boolean) = success == (state > 0)
    }

  }
}