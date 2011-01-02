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

  // ------------------------------------------
  //  Internal
  // ------------------------------------------

  private class RouteProcessor(p: MessageProcessorKleisli) extends AsyncProcessor { this: ErrorRouteDefinition =>
    def process(exchange: Exchange, callback: AsyncCallback) = {

      // Not sure if we need to find out whether we'll receive an async or sync callback.
      // For example, the CamelContinuationServlet (used by Jetty component) doesn't care
      // (i.e. ignores return values from AsyncProcessor#process and the doneSync parameter
      // of AsyncCallback#done)

      val sync = false

      p apply exchange.getIn.toMessage.success respond { mv: MessageValidation =>
        mv match {
          case Failure(e) => {
            exchange.setException(e)
            callback.done(sync)
          }
          case Success(m) => {
            exchange.getIn.fromMessage(m)
            exchange.getOut.fromMessage(m)
            callback.done(sync)
          }
        }
      }
      sync
    }

    def process(exchange: Exchange) = {

      //
      // TODO: improve
      //

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