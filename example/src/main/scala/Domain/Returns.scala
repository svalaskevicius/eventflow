package Domain

import Cqrs.Aggregate._
import Cqrs._
import Domain.Store._

import org.joda.time.DateTime

import scala.collection.immutable.TreeMap

object Store {

  type ProductId = String
  type SequenceNumber = String
  case class Time(millis: Long) {
    lazy val dateTime = new DateTime(millis)
  }
  type Money = Int

  final case class Receipt(sequenceNumber: SequenceNumber, product: ProductId, quantity: Int, amount: Money, time: Time)

  sealed trait RefundType
  case object CashRefund extends RefundType
  case object StoreCredit extends RefundType

  sealed trait ProductState
  case object Resellable extends ProductState
  case object Damaged extends ProductState

  sealed trait Event
  final case class ItemBought(id: AggregateId, producedReceipt: Receipt) extends Event
  final case class CustomerRefunded(id: AggregateId, time: Time, receipt: Receipt, refundType: RefundType) extends Event
  final case class ItemRestocked(id: AggregateId, time: Time, productId: ProductId, qty: Int) extends Event

  sealed trait Command
  final case class RequestRefund(id: AggregateId, time: Time, receipt: Receipt, refundType: RefundType, productState: ProductState) extends Command
}

object StoreAggregate extends EventFlow[Event, Command] {

  import EventFlow.state

  final case class StoreInfo(inventory: Map[ProductId, Int], prices: Map[ProductId, Money], knownReceipts: Map[SequenceNumber, Receipt]) {

    def returnProduct(receipt: Receipt) = copy(knownReceipts = knownReceipts - receipt.sequenceNumber)

    def add(productId: ProductId, qty: Int) = copy(inventory = inventory.updated(productId, quantity(productId) + qty))

    def quantity(productId: ProductId) = inventory.getOrElse(productId, 0)

    def addReceipt(receipt: Receipt) = copy(knownReceipts = knownReceipts.updated(receipt.sequenceNumber, receipt))
  }
  object StoreInfo {
    def empty = StoreInfo(TreeMap[ProductId, Int](), TreeMap[ProductId, Money](), TreeMap[SequenceNumber, Receipt]())
  }

  @state def store(storeInfo: StoreInfo) {
    when[RequestRefund].
      guard(isNotExpiredForCash, "The receipt has expired for cash refunds.").
      guard(isNotExpired, "The receipt has expired for refunds.").
      guard(cmd => storeInfo.knownReceipts.contains(cmd.receipt.sequenceNumber), "Unkown receipt number.").
      emitEvents { cmd =>
        val stockEvents = cmd.productState match {
          case Damaged => Nil
          case Resellable => List(ItemRestocked(cmd.id, cmd.time, cmd.receipt.product, cmd.receipt.quantity))
        }
        stockEvents ++ List(CustomerRefunded(cmd.id, cmd.time, cmd.receipt, cmd.refundType))
      }

    on[CustomerRefunded].switchByEvent(ev => store(storeInfo.returnProduct(ev.receipt)))

    on[ItemRestocked].switchByEvent(ev => store(storeInfo.add(ev.productId, ev.qty) ))

    on[ItemBought].switchByEvent(ev => store(storeInfo.addReceipt(ev.producedReceipt)))
  }

  override def startLogic = toFlow(store(StoreInfo.empty)).get

  private def isNotExpiredForCash(r: RequestRefund) = r.time.dateTime.isBefore(r.receipt.time.dateTime.plusDays(30)) || (r.refundType != CashRefund)

  private def isNotExpired(r: RequestRefund) = r.time.dateTime.isBefore(r.receipt.time.dateTime.plusMonths(12))

}

