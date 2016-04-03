import Cqrs.Aggregate.{ ErrorCommandFailure, Errors }
import Domain.Store._
import Domain.StoreAggregate
import cats.data.{ NonEmptyList => NEL }

import org.scalatest._

class ReturnsSpec extends FlatSpec with Matchers with AggregateSpec {

  "Refunding a bought product in less than 30 days" should "credit the product's price back" in {

    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))

    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", receipt))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", date("2016-03-25"), receipt, CashRefund, Resellable))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        ItemRestocked("Oliver's goods", date("2016-03-25"), "microwave", 1),
        CustomerRefunded("Oliver's goods", date("2016-03-25"), receipt, CashRefund)
      ))
    }
  }

  it should "allow choosing to refund to store account" in {

    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))

    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", receipt))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", date("2016-03-25"), receipt, StoreCredit, Resellable))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        ItemRestocked("Oliver's goods", date("2016-03-25"), "microwave", 1),
        CustomerRefunded("Oliver's goods", date("2016-03-25"), receipt, StoreCredit)
      ))
    }
  } // no saga support yet to model customer's account

  "Refunding a bought product in more than 30 days" should "fail for cash refund" in {

    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))

    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", receipt))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", date("2016-04-21"), receipt, CashRefund, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("The receipt has expired for cash refunds."))))
    }
  }

  it should "allow choosing to refund to store account" in {
    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", receipt))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", date("2016-04-21"), receipt, StoreCredit, Resellable))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        ItemRestocked("Oliver's goods", date("2016-04-21"), "microwave", 1),
        CustomerRefunded("Oliver's goods", date("2016-04-21"), receipt, StoreCredit)
      ))
    }
  } // no saga support yet to model customer's account

  "Refunding a bought product with wrong receipt number" should "fail" in {
    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))
    val wrongReceipt = Receipt("100421445", "microwave", 1, 100, date("2016-03-20"))
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", receipt))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", date("2016-03-22"), wrongReceipt, CashRefund, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("Unkown receipt number."))))
    }
  }

  "Refunding a bought product with the same receipt twice" should "fail" in {
    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))
    given {
      newDb.
        withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("Oliver's goods", receipt)).
        withEvent(StoreAggregate.tag, "Oliver's goods", CustomerRefunded("Oliver's goods", date("2016-03-24"), receipt, CashRefund))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", date("2016-04-02"), receipt, CashRefund, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("Unkown receipt number."))))
    }
  }

  "Refunding a bought product in more than 12 months" should "fail for store credit refund" in {
    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", receipt))
    } check {
      _.failedCommandError(
        StoreAggregate,
        "Oliver's goods",
        RequestRefund("Oliver's goods", date("2017-03-21"), receipt, StoreCredit, Resellable)
      ) should be(Errors(NEL(ErrorCommandFailure("The receipt has expired for refunds."))))
    }
  }

  "Refunding a damaged product" should "credit the product's price back" in {
    val receipt = Receipt("200421445", "microwave", 1, 100, date("2016-03-20"))
    given {
      newDb.withEvent(StoreAggregate.tag, "Oliver's goods", ItemBought("storeid", receipt))
    } when {
      _.command(StoreAggregate, "Oliver's goods", RequestRefund("Oliver's goods", date("2016-03-27"), receipt, CashRefund, Damaged))
    } thenCheck {
      _.newEvents[Event](StoreAggregate.tag, "Oliver's goods") should be(List(
        CustomerRefunded("Oliver's goods", date("2016-03-27"), receipt, CashRefund)
      ))
    }
  }

  def date(s: String): Long =
    java.time.LocalDate.parse(s).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC)
}
