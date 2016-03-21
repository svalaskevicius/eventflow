import Cqrs.Aggregate._
import Domain.DoorAggregate.tag
import Domain.Door._
import Domain.{DoorProjection, DoorState, DoorAggregate}
import cats.data.{NonEmptyList => NEL}
import org.scalatest._

import scala.collection.immutable.TreeMap

class DoorSpec extends FlatSpec with Matchers with AggregateSpec {

  "Door" should "be open at start" in {
    given {
      newDb.withEvent(tag, "door", Registered("door"))
    } when {
      _.command(DoorAggregate, "door", Close)
    } thenCheck {
      _.newEvents[Event](tag, "door") should be(List(Closed))
    }
  }

  "Closing door" should "allow open it again" in {
    given {
      newDb.withEvents[Event](tag, "door", Registered("door"), Closed)
    } when {
      _.command(DoorAggregate, "door", Open)
    } thenCheck {
      _.newEvents[Event](tag, "door") should be(List(Opened))
    }
  }

  it should "allow locking door" in {
    given {
      newDb.withEvents[Event](tag, "door", Registered("door"), Closed)
    } when {
      _.command(DoorAggregate, "door", Lock("key"))
    } thenCheck {
      _.newEvents[Event](tag, "door") should be(List(Locked("key")))
    }
  }

  "Locked door" should "allow to unclock with the same key" in {
    given {
      newDb.withEvents[Event](tag, "door", Registered("door"), Closed, Locked("key"))
    } when {
      _.command(DoorAggregate, "door", Unlock("key"))
    } thenCheck {
      _.newEvents[Event](tag, "door") should be(List(Unlocked("key")))
    }
  }

  it should "fail to unlock with the different key" in {
    given {
      newDb.withEvents[Event](tag, "door", Registered("door"), Closed, Locked("key"))
    } check {
      _.failedCommandError(DoorAggregate, "door", Unlock("wrongkey")) should be(Errors(NEL(ErrorCommandFailure("Attempted unlock key is invalid"))))
    }
  }

  it should "not allow to open the door" in {
    given {
      newDb.withEvents[Event](tag, "door", Registered("door"), Closed, Locked("key"))
    } check {
      _.failedCommandError(DoorAggregate, "door", Open) should be(Errors(NEL(ErrorCommandFailure("Locked door can only be unlocked."))))
    }
  }

  "Unlocked doors" should "allow to be opened" in {
    given {
      newDb.withEvents[Event](tag, "door", Registered("door"), Closed, Locked("key"), Unlocked("key"))
    } when {
      _.command(DoorAggregate, "door", Open)
    } thenCheck {
      _.newEvents[Event](tag, "door") should be(List(Opened))
    }
  }

  "Door projection" should "return the current state" in {
    import Domain.Door
    given {
      newDb(DoorProjection)
        .withEvent(tag, "door1", Registered("door1"))
        .withEvent(tag, "door2", Registered("door2"))
    } when {
      _.command(DoorAggregate, "door1", Door.Close)
        .command(DoorAggregate, "door2", Door.Close)
        .command(DoorAggregate, "door1", Door.Open)
    } thenCheck {
      _.projectionData(DoorProjection) should be(Some(TreeMap(
        AggregateId("door1") -> DoorState.Open,
        AggregateId("door2") -> DoorState.Closed
      )))
    }
  }
}