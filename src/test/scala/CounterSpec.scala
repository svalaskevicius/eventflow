import org.scalatest._
import Domain.Counter.CounterAggregate.tag
import Domain.Counter._

class CounterSpec extends FlatSpec with Matchers with AggregateSpec {

  "Incrementing a counter" should "succeed" in {
    given {
      newDbRunner
        .event(tag, "counterid", Created("counterid"))
    }.when {
      _.command(CounterAggregate, "counterid", Increment)
    }.thenCheck {
      _.newEvents[Event](tag, "counterid") should be (List(Incremented))
    }
  }
}