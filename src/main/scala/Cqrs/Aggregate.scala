package Cqrs

import cats.state.State
import cats.data.Reader
import cats.data.Xor
import cats.implicits._

trait Event

trait EventRouter {
  def route: Event => List[String] Xor Unit
}

object Aggregate {

  def updateState[D](fn: D => D): State[D, Unit] = State(d => (fn(d), ()))

  def noStateChanges[D](): State[D, Unit] = updateState(identity)

  def emitEvent[E, Errors](ev: E): Errors Xor List[E] = Xor.right(List(ev))
  def emitEvents[E, Errors](evs: List[E]): Errors Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): List[String] Xor Events = Xor.left(List(err))

  def commandHandler[A, B] = Reader

  implicit object EventRouter extends EventRouter {
    def route = ev => {
      Xor.right(())
    }
  }
}

class Aggregate[E <: Event, C, D] (
                           on: Aggregate[E, C, D]#EventHandler,
                           handle: Aggregate[E, C, D]#CommandHandler,
                           private[this] var data: D
                         ) (
                           implicit private val eventRouter: EventRouter
                         ) {
  type Errors = List[String]
  type Events = List[E]
  type CommandHandler = C => Reader[D, Errors Xor Events]
  type EventHandler = E => State[D, Unit]

  def handleCommand(cmd: C): Errors Xor Unit =
    handle(cmd).run(data) fold (Xor.left, onEvents)

  private def onEvents(evs: Events): Errors Xor Unit = {
    val result = evs.foldLeft(Xor.right[Errors, Unit](()))((prev, ev) => prev flatMap (_ => eventRouter.route(ev)))
    if (result.isRight) {
      applyEvents(evs)
    }
    result
  }

  private def applyEvents(evs: Events): Unit = {
    data = evs.foldLeft(data)((d, e) => on(e).runS(d).run)
  }
}

