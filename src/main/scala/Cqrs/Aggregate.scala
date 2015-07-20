package Cqrs

import cats.data.Xor

trait Event
// todo: add type for aggregate id
// todo: add type for event processor monad
trait EventRouter {
  type EventReader = (String, Event) => List[String] Xor Unit
  def route: EventReader
  def subscribe: EventReader => (() => Unit)
}

class Projection[R] (on: EventRouter#EventReader, result: R)

object Aggregate {

  def emitEvent[E, Errors](ev: E): Errors Xor List[E] = Xor.right(List(ev))
  def emitEvents[E, Errors](evs: List[E]): Errors Xor List[E] = Xor.right(evs)

  def failCommand[Events](err: String): List[String] Xor Events = Xor.left(List(err))

  implicit object DefaultEventRouter extends EventRouter {
    private[this] var _subscribers = Map[Int, EventReader]()
    private[this] var _nextId = 1
    def route = (id, ev) => {
      Xor.right(())
    }
    def subscribe = subscriber  => {
      val id = nextId
      _subscribers += (id -> subscriber)
      () => {
        _subscribers -= id
      }
    }
    private def nextId: Int = {
      val id = _nextId
      _nextId += 1
      id
    }
  }
}

class Aggregate[E <: Event, C, D] (
                           id: String,
                           on: Aggregate[E, C, D]#EventHandler,
                           handle: Aggregate[E, C, D]#CommandHandler,
                           private[this] var state: D
                         ) (
                           implicit private val eventRouter: EventRouter
                         ) {
  type Errors = List[String]
  type Events = List[E]
  type CommandHandler = C => D => Errors Xor Events
  type EventHandler = E => D => D

  def handleCommand(cmd: C): Errors Xor Unit =
    handle(cmd)(state) fold (Xor.left, onEvents)

  private def onEvents(evs: Events): Errors Xor Unit = {
    val startState: Errors Xor Unit = Xor.right(())
    val result = evs.foldLeft(startState)( (prev, ev) =>
      prev flatMap (_ => eventRouter.route(id, ev))
    )
    if (result.isRight) {
      applyEvents(evs)
    }
    result
  }

  private def applyEvents(evs: Events): Unit = {
    state = evs.foldLeft(state)((d, e) => on(e)(d))
  }
}

