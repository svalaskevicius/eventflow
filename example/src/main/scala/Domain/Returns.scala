package Domain

import Cqrs.Aggregate._
import Cqrs._
import Domain.Store._

import scala.collection.immutable.TreeMap

object Store {

  type ProductId = String
  type SequenceNumber = String
  type Timestamp = Long
  type Money = Int

  final case class StoreInfo(inventory: Map[ProductId, Int], prices: Map[ProductId, Money], knownReceipts: Map[SequenceNumber, Receipt]) {
    def add(productId: ProductId, qty: Int) = copy(inventory = inventory.updated(productId, quantity(productId) + qty))

    def quantity(productId: ProductId) = inventory.getOrElse(productId, 0)

    def addReceipt(receipt: Receipt) = copy(knownReceipts = knownReceipts.updated(receipt.sequenceNumber, receipt))
  }
  object StoreInfo {
    def empty = StoreInfo(TreeMap[ProductId, Int](), TreeMap[ProductId, Money](), TreeMap[SequenceNumber, Receipt]())
  }

  final case class Receipt(sequenceNumber: SequenceNumber, product: ProductId, quantity: Int, amount: Money, timestamp: Timestamp)

  sealed trait RefundType
  case object CashRefund extends RefundType
  case object StoreCredit extends RefundType

  sealed trait Event
  final case class ItemBought(id: AggregateId, producedReceipt: Receipt) extends Event
  final case class CustomerRefunded(id: AggregateId, timestamp: Timestamp, receipt: Receipt, refundType: RefundType) extends Event

  sealed trait Command
  final case class RequestRefund(id: AggregateId, timestamp: Timestamp, receipt: Receipt, refundType: RefundType) extends Command {
    def isNotExpired = (timestamp < receipt.timestamp + 2592000) || (refundType == StoreCredit)
  }
}

object StoreAggregate extends EventFlow[Event, Command] {

  val store: RegisteredFlowStateAux[StoreInfo] = ref('store, storeInfo => handler(
    when[RequestRefund].
      guard(_.isNotExpired, "The receipt has expired for cash refunds.").
      guard(cmd => storeInfo.knownReceipts.contains(cmd.receipt.sequenceNumber), "Unkown receipt number.").
      emit[CustomerRefunded].
      switchByEvent(ev => storeInfo.add(ev.receipt.product, ev.receipt.quantity) -> store),

    on[ItemBought].switchByEvent(ev => storeInfo.addReceipt(ev.producedReceipt) -> store)
  ))

  val snapshottableStates: FlowStates = List(store)

  val aggregateLogic = store.state(StoreInfo.empty)
}

