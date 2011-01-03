package scalaz.camel.async

import java.util.concurrent.CountDownLatch

import org.apache.camel.{Exchange, AsyncCallback, AsyncProcessor}

import scalaz._
import scalaz.camel.{Message, EndpointMgnt}

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
    val latch = new java.util.concurrent.CountDownLatch(1)
    var result: MessageValidation = null
    p apply m.success respond { mv => result = mv; latch.countDown }
    latch.await  // TODO: improve
    result
  }

  private def promiseFunction(p: Message => MessageValidation)(implicit s: Strategy) =
    kleisliFn[Promise, Message, MessageValidation](p.promise(s))

  // ------------------------------------------
  //  DSL (route initiation)
  // ------------------------------------------

  def from(uri: String) = new MainRouteDefinition(uri)

  class MainRouteDefinition(uri: String) {
    def route(r: MessageProcessorKleisli)(implicit mgnt: EndpointMgnt): ErrorRouteDefinition = {
      val processor = new RouteProcessor(r) with ErrorRouteDefinition
      val consumer = mgnt.createConsumer(uri, processor)
      processor
    }
  }

  trait ErrorRouteDefinition

  class RouteProcessor(p: MessageProcessorKleisli) extends AsyncProcessor { this: ErrorRouteDefinition =>
    def process(exchange: Exchange, callback: AsyncCallback) = {
      p apply exchange.getIn.toMessage.success respond { mv: MessageValidation =>
        mv match {
          case Failure(e) => {
            exchange.setException(e)
            callback.done(false)
          }
          case Success(m) => {
            exchange.getIn.fromMessage(m)
            exchange.getOut.fromMessage(m)
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
      latch.await // TODO: improve
    }
  }
}
