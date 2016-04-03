//import Cqrs.Aggregate._
import Cqrs.Aggregate.{ ErrorCommandFailure, Errors }
import Domain.Store._
import Domain.StoreAggregate
import cats.data.{ NonEmptyList => NEL }

//import Domain.CounterProjection.{ Data => CounterProjectionData, emptyCounterProjection }
//import cats.data.{NonEmptyList => NEL}
import org.scalatest._

//import scala.collection.immutable.TreeMap

class ReturnsSpec extends FlatSpec with Matchers with AggregateSpec {

  "Refunding a bought product in less than 30 days" should "credit the product's price back" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), CashRefund, Resellable))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        ItemRestocked("Oliver's goods", 200, "microwave", 1),
        CustomerRefunded("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), CashRefund)
      ))
    }
  }

  it should "allow choosing to refund to store account" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit, Resellable))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        ItemRestocked("Oliver's goods", 200, "microwave", 1),
        CustomerRefunded("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit)
      ))
    }
  } // no saga support yet to model customer's account

  "Refunding a bought product in more than 30 days" should "fail for cash refund" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", 200 + 2592000, Receipt("200421445", "microwave", 1, 100, 123), CashRefund, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("The receipt has expired for cash refunds."))))
    }
  }

  it should "allow choosing to refund to store account" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", 200 + 2592000, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit, Resellable))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        ItemRestocked("Oliver's goods", 200 + 2592000, "microwave", 1),
        CustomerRefunded("Oliver's goods", 200 + 2592000, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit)
      ))
    }
  } // no saga support yet to model customer's account

  "Refunding a bought product with wrong receipt number" should "fail" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", 200, Receipt("12421425", "microwave", 1, 100, 123), CashRefund, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("Unkown receipt number."))))
    }
  }

  "Refunding a bought product with the same receipt twice" should "fail" in {
    given {
      newDb.
        withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("Oliver's goods", Receipt("200421445", "microwave", 1, 100, 123))).
        withEvent(StoreAggregate.tag, "Oliver's goods", CustomerRefunded("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), CashRefund))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", 300, Receipt("200421445", "microwave", 1, 100, 123), CashRefund, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("Unkown receipt number."))))
    }
  }

  "Refunding a bought product in more than 12 months" should "fail for store credit refund" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", 200 + 31104000, Receipt("200421445", "microwave", 1, 100, 123), StoreCredit, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("The receipt has expired for refunds."))))
    }
  }

  "Refunding a damaged product" should "credit the product's price back" in {
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", Receipt("200421445", "microwave", 1, 100, 123)))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), CashRefund, Damaged))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
                                                                           CustomerRefunded("Oliver's goods", 200, Receipt("200421445", "microwave", 1, 100, 123), CashRefund)
                                                                         ))
    }
  }
}
