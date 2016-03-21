import Cqrs.Aggregate._
import Domain.Counter._
import Domain.CounterAggregate
import Domain.CounterProjection
import Domain.CounterAggregate.tag

//import Domain.CounterProjection.{ Data => CounterProjectionData, emptyCounterProjection }
import cats.data.{NonEmptyList => NEL}
import org.scalatest._

import scala.collection.immutable.TreeMap

class CounterSpec extends FlatSpec with Matchers with AggregateSpec {

  "Incrementing a counter" should "succeed" in {
    given {
      newDb.withEvent(tag, "counterid", Created("counterid", 0))
    } when {
      _.command(CounterAggregate, "counterid", Increment)
    } thenCheck {
      _.newEvents[Event](tag, "counterid") should be(List(Incremented))
    }
  }

  it should "fail for missing counter" in {
    given(newDb) check {
      _.failedCommandError(CounterAggregate, "counterid", Increment) should be(Errors(NEL(ErrorCannotFindHandler("Increment"))))
    }
  }

  "Decrementing a counter" should "succeed after its incremented" in {
    given {
      newDb.withEvents[Event](tag, "counterid", Created("counterid", 0), Incremented)
    } when {
      _.command(CounterAggregate, "counterid", Decrement)
    } thenCheck {
      _.newEvents[Event](tag, "counterid") should be(List(Decremented))
    }
  }

  it should "fail for missing counter" in {
    given(newDb) check {
      _.failedCommandError(CounterAggregate, "counterid", Decrement) should be(Errors(NEL(ErrorCannotFindHandler("Decrement"))))
    }
  }

  it should "fail if its at zero balance" in {
    given {
      newDb.withEvents[Event](tag, "counterid", Created("counterid", 0), Incremented, Decremented)
    } check {
      _.failedCommandError(CounterAggregate, "counterid", Decrement) should be(Errors(NEL(ErrorCommandFailure("Counter cannot be decremented"))))
    }
  }

  "Counter projection" should "return the current count" in {
    given {
      newDb(CounterProjection).withEvent(tag, "counterid", Created("counterid", 10))
    } when {
      _.command(CounterAggregate, "counterid", Increment)
        .command(CounterAggregate, "counterid", Increment)
        .command(CounterAggregate, "counterid", Decrement)
    } thenCheck {
      _.projectionData(CounterProjection) should be(Some(TreeMap("counterid" -> 11)))
    }
  }
}