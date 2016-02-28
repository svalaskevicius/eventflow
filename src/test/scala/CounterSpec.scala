import Cqrs.Aggregate._
import Domain.CounterProjection.emptyCounterProjection
import org.scalatest._
import Domain.Counter.CounterAggregate.tag
import Domain.Counter._

import scala.collection.immutable.TreeMap

class CounterSpec extends FlatSpec with Matchers with AggregateSpec {

  "Incrementing a counter" should "succeed" in {
    given {
      newDbRunner
        .withEvent(tag, "counterid", Created("counterid"))
    } when {
      _.command(CounterAggregate, "counterid", Increment)
    } thenCheck {
      _.newEvents[Event](tag, "counterid") should be(List(Incremented))
    }
  }

  it should "fail for missing counter" in {
    given(newDbRunner) check {
      _.failedCommandError(CounterAggregate, "counterid", Increment) should be(ErrorDoesNotExist("counterid"))
    }
  }

  "Decrementing a counter" should "succeed after its incremented" in {
    given {
      newDbRunner
        .withEvents(tag, "counterid", List[Event](Created("counterid"), Incremented))
    } when {
      _.command(CounterAggregate, "counterid", Decrement)
    } thenCheck {
      _.newEvents[Event](tag, "counterid") should be(List(Decremented))
    }
  }

  it should "fail for missing counter" in {
    given(newDbRunner) check {
      _.failedCommandError(CounterAggregate, "counterid", Decrement) should be(ErrorDoesNotExist("counterid"))
    }
  }

  it should "fail if its at zero balance" in {
    given {
      newDbRunner
        .withEvents(tag, "counterid", List[Event](Created("counterid"), Incremented, Decremented))
    } check {
      _.failedCommandError(CounterAggregate, "counterid", Decrement) should be(ErrorCommandFailure("Counter cannot be decremented"))
    }
  }

  "Counter projection" should "return the current count" in {
    given {
      newDbRunner
        .withEvent(tag, "counterid", Created("counterid"))
        .withProjection(emptyCounterProjection)
    } when {
      _.command(CounterAggregate, "counterid", Increment)
        .command(CounterAggregate, "counterid", Increment)
        .command(CounterAggregate, "counterid", Decrement)
    } thenCheck {
      _.projections.head.data should be(TreeMap(AggregateId("counterid") -> 1))
    }
  }
}