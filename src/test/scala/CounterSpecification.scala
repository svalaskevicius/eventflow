import Cqrs.Aggregate.AggregateId
import Cqrs.BatchRunner
import Cqrs.DbAdapters.InMemoryDb._
import Domain.Counter
import cats.data.Xor
import org.scalacheck.{Prop, Properties, Gen}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.commands.Commands

object CounterSpecification extends Properties("Counter") {

  property("fails when below zero") = CounterSpec.property()

  private object CounterSpec extends Commands {

    type State = Int

    val (initialRunner, agg) = {
      val runner = BatchRunner.forDb(newInMemoryDb)
      val action = runner.db(Counter.startCounter(AggregateId("test counter")))
      runner.run(action) match {
        case Xor.Left(err) => throw new Exception("Failed to initialise aggregate: " + err)
        case Xor.Right(r) => r
      }
    }

    final case class Sut(var runner: initialRunner.Self)

    def canCreateNewSut(newState: State, initSuts: Traversable[State], runningSuts: Traversable[Sut]) = true

    def newSut(state: State): Sut = Sut(initialRunner)

    def destroySut(sut: Sut) = ()

    def initialPreCondition(state: State) = true

    val genInitialState = Gen.const(0)

    def genCommand(state: State): Gen[Command] =
      Gen.oneOf(
        Gen.const(Increment),
        Gen.const(Decrement)
      )

    case object Increment extends UnitCommand {
      def run(sut: Sut) = sut.synchronized {
        val action = sut.runner.db(agg, Counter.counterAggregate.handleCommand(Counter.Increment))
        sut.runner = sut.runner.run(action) match {
          case Xor.Left(err) => throw new Exception("Failed to run aggregate command: " + err._2)
          case Xor.Right(r) => r._1
        }
      }
      def nextState(state: State) = state + 1
      def preCondition(state: State) = true
      def postCondition(state: State, success: Boolean) = success
    }

    case object Decrement extends UnitCommand {
      def run(sut: Sut) = sut.synchronized {
        val action = sut.runner.db(agg, Counter.counterAggregate.handleCommand(Counter.Decrement))
        sut.runner = sut.runner.run(action) match {
          case Xor.Left(err) => throw new Exception("Failed to run aggregate command: " + err._2)
          case Xor.Right(r) => r._1
        }
      }
      def nextState(state: State) = if (state > 0) state - 1 else state
      def preCondition(state: State) = true
      def postCondition(state: State, success: Boolean) = success == (state > 0)
    }
  }
}