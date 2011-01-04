package scalaz.camel.async

import java.util.concurrent.CountDownLatch

import org.apache.camel.{Exchange, AsyncCallback, AsyncProcessor}

import scalaz._
import scalaz.camel.{Message, ContextMgnt, EndpointMgnt}

/**
 * @author Martin Krasser
 *
 * @see scalaz.camel.async.Camel
 */
trait CamelDsl extends CamelConv {
  import concurrent.Promise
  import concurrent.Strategy
  import Scalaz._
  import Message._

  // ----------------------------------------------------------------------------------------------
  //  This is currently an experimental alternative implementation to  scalaz.camel.CamelDsl
  //  that supports asynchronous message processors and non-blocking routes based on continuations
  //  (scala.Responder, a continuation monad). However, the DSL syntax is the same. This approach
  //  will either replace the approach followed in scalaz.camel.CamelDsl or a combined usage will
  //  be supported.
  // ----------------------------------------------------------------------------------------------

  // ------------------------------------------
  //  DSL (EIPs)
  // ------------------------------------------

  /** The content-based router EIP */
  def choose(f: PartialFunction[Message, MessageProcessorKleisli]): MessageProcessorKleisli =
    kleisli[Responder, MessageValidation, MessageValidation](
      (mv: MessageValidation) => mv match {
        case Failure(e) =>  new MessageResponder(Failure(e), null) // 2nd arg won't be evaluated
        case Success(m) =>  new MessageResponder(Success(m), messageProcessor(f(m)))
      }
    )

  /** The recipient-list EIP */
  def multicast(destinations: MessageProcessorKleisli*)(aggregator: MessageAggregator)(implicit s: Strategy): MessageProcessorKleisli =
    kleisli[Responder, MessageValidation, MessageValidation](
      (mv: MessageValidation) => mv match {
        case Failure(e) =>  new MessageResponder(Failure(e), null) // 2nd arg won't be evaluated
        case Success(m) =>  new MessageResponder(Success(m), multicast(destinations.toList)(aggregator))
      }
    )

  private def multicast(destinations: List[MessageProcessorKleisli])(aggregator: MessageAggregator)(implicit s: Strategy): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => {
      // obtain a promise for a list of MessageValidation values
      val vsPromise = (m.pure[List] <*> destinations ∘ validationFunction ∘ (promiseFunction(_)(s))).sequence
      // combine promised success messages into single message or return first failure
      k(vsPromise.map(vs => vs.tail.foldLeft(vs.head) {
        (z, m) => m <*> z ∘ aggregator.curried
      }).get /* here we need to block */)
    }

  private def validationFunction(p: MessageProcessorKleisli): Message => MessageValidation = (m: Message) => {
    val exchr = new java.util.concurrent.Exchanger[MessageValidation]
    p apply m.success respond { mv => exchr.exchange(mv) }
    exchr.exchange(null)
  }

  private def promiseFunction(p: Message => MessageValidation)(implicit s: Strategy) =
    kleisliFn[Promise, Message, MessageValidation](p.promise(s))

  // ------------------------------------------
  //  DSL (route initiation)
  // ------------------------------------------

  def from(uri: String) = new MainRouteDefinition(uri)

  class MainRouteDefinition(uri: String) {
    def route(r: MessageProcessorKleisli)(implicit emgnt: EndpointMgnt, cmgnt: ContextMgnt): ErrorRouteDefinition = {
      val processor = new RouteProcessor(r, cmgnt) with ErrorRouteDefinition
      val consumer = emgnt.createConsumer(uri, processor)
      processor
    }
  }

  trait ErrorRouteDefinition {
    import collection.mutable.Buffer

    case class ErrorHandler(c: Class[_ <: Exception], r: MessageProcessorKleisli)

    val errorHandlers = Buffer[ErrorHandler]()
    var errorClass: Class[_ <: Exception] = _

    def route(r: MessageProcessorKleisli) = {
      errorHandlers append ErrorHandler(errorClass, r)
      this
    }

    def onError[A <: Exception](c: Class[A]) = {
      errorClass = c
      this
    }
  }

  class RouteProcessor(p: MessageProcessorKleisli, mgnt: ContextMgnt) extends AsyncProcessor { this: ErrorRouteDefinition =>
    def process(exchange: Exchange, callback: AsyncCallback) =
      route(exchange.getIn.toMessage.cache(mgnt), callback, p, errorHandlers.toList)

    def route(message: Message, callback: AsyncCallback, target: MessageProcessorKleisli, errorHandlers: List[ErrorHandler]): Boolean = {
      val exchange = message.exchange
      target apply message.success respond { rv: MessageValidation =>
        rv match {
          case Failure(e) => {
            for (me <- exchange) {
              me.setException(e)
              errorHandlers.find(_.c.isInstance(e)) match {
                case None                     => callback.done(false)
                case Some(ErrorHandler(_, r)) => {
                  me.setException(null)
                  // route original message to error handler
                  // with the exception set on message header
                  route(message.reset.setException(e), callback, r, Nil)
                }
              }
            }
          }
          case Success(m) => {
            for (me <- exchange; e <- m.exception) {
              me.setException(e)
            }
            for (me <- exchange) {
              me.getIn.fromMessage(m)
              me.getOut.fromMessage(m)
            }
            callback.done(false)
          }
        }
      }
      false
    }

    def process(exchange: Exchange) = {
      val latch = new CountDownLatch(1)
      process(exchange, new AsyncCallback() {
        def done(doneSync: Boolean) = {
          latch.countDown
        }
      })
      latch.await
    }
  }
}
