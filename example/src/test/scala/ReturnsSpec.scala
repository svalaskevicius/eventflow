//import Cqrs.Aggregate._
import Cqrs.Aggregate.{ErrorCommandFailure, Errors}
import Domain.Store._
import Domain.StoreAggregate
import cats.data.{NonEmptyList => NEL}

//import Domain.CounterProjection.{ Data => CounterProjectionData, emptyCounterProjection }
//import cats.data.{NonEmptyList => NEL}
import org.scalatest._

//import scala.collection.immutable.TreeMap

class ReturnsSpec extends FlatSpec with Matchers with AggregateSpec {

  "Refunding a bought product in less than 30 days" should "credit the product's price back" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), CashRefund))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        CustomerRefunded("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), CashRefund))
      )
    }
  }

  "Refunding a bought product in less than 30 days" should "allow choosing to refund to store account" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        CustomerRefunded("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit))
      )
    }
  } // no saga support yet to model customer's account

  "Refunding a bought product in more than 30 days" should "fail" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", 200+2592000, Receipt("200421445", "microwave", 1, 100, 123), CashRefund)
      ) should be(Errors(NEL(ErrorCommandFailure("The receipt has expired for cash refunds."))))
    }
  }

  "Refunding a bought product in more than 30 days" should "allow choosing to refund to store account" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", 200+2592000, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        CustomerRefunded("Oliver's goods", 200+2592000, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit))
      )
    }
  } // no saga support yet to model customer's account

  "Refunding a bought product with wrong receipt number" should "fail" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", 200, Receipt("12421425", "microwave", 1, 100, 123), CashRefund)
      ) should be(Errors(NEL(ErrorCommandFailure("Unkown receipt number."))))
    }
  }
  /**
    *
  Scenario: Customer cannot return a product with the wrong receipt
    When I try to return the microwave
    And I provide receipt with sequence number 12421425
    Then my return should be refused
    And the microwave should not be taken back into stock

  Scenario: Customer cannot return a product that is already returned
    Given I returned the microwave on 10th January with receipt 200421445
    When I try to return the microwave on 20th January
    And I provide receipt with sequence number 200421445
    Then my return should be refused
    And the microwave should not be taken back into stock

  Scenario: Customer cannot return a product more than 12 months after purchase
    When I return the microwave on 3rd January next year
    And I provide receipt with sequence number 200421445
    And I ask for a store credit refund
    Then my return should be refused
    And the microwave should not be taken back into stock

  Scenario: Customer returns a damaged item and it is not returned to stock
    When I return the microwave on 20th January
    And I provide receipt with sequence number 200421445
    And I ask for a cash refund
    Then I should be credited with £100
    But the microwave should be taken back into stock
    */
}