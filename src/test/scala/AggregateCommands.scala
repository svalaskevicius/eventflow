import Cqrs.Aggregate.{AggregateDef, EventDatabaseWithFailure, AggregateState}
import Cqrs.{Projection, BatchRunner}
import Cqrs.Database.EventSerialisation
import Cqrs.DbAdapters.InMemoryDb._
import cats.data.Xor
import lib.HList.KMapper
import org.scalacheck.commands.Commands
import shapeless.HNil

trait AggregateCommands[E, AggStateData, S] extends Commands {

  type State = S

  case class Sut(var runner: BatchRunner[DbBackend, HNil.type], var aggUnderTest: AggregateState[AggStateData])

  def canCreateNewSut(newState: State, initSuts: Traversable[State], runningSuts: Traversable[Sut]) = true

  def initSutActions: EventDatabaseWithFailure[E, AggregateState[AggStateData]]

  def evtSerializer: EventSerialisation[E]

  def newSut(state: State): Sut = {
    val runner = BatchRunner.forDb(newInMemoryDb)
    val action = runner.db(initSutActions)(evtSerializer)
    val (runner1, aggState) = runner.run(action) match {
      case Xor.Left(err) => throw new Exception("Failed to initialise aggregate: " + err)
      case Xor.Right(r) => r
    }
    Sut(runner1, aggState)
  }

  def destroySut(sut: Sut) = ()

  def initialPreCondition(state: State) = true

  trait AggregateCommand extends UnitCommand {
    def commandActions: AggregateDef[E, AggStateData, Unit]
    def preCondition(state: State) = true
    def run(sut: Sut) = sut.synchronized {
      val action = sut.runner.db(sut.aggUnderTest, commandActions)(evtSerializer)
      sut.runner = sut.runner.run(action) match {
        case Xor.Left(err) => throw new Exception("Failed to run aggregate command: " + err._2)
        case Xor.Right(r) => r._1
      }
    }
  }
}

