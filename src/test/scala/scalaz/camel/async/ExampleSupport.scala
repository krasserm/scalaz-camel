package scalaz.camel.async

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange}

import scalaz.camel.ContextMgnt
import scalaz.camel.Message

import scalaz._

/**
 * @author Martin Krasser
 */
trait ExampleSupport {
  import scalaz.concurrent.Strategy
  import Scalaz._
  import Camel.MessageValidation
  import Camel.MessageProcessor

  // asynchronous message processor (Scala function)
  // that appends s to message body
  def appendStringAsync(s: String)(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(m.appendBody(s).success))

  // asynchronous message processor (Scala function)
  // that converts message body to String
  def convertToStringAsync(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(m.bodyTo[String].success))

  // asynchronous message processor (Camel interface)
  // that repeats message body
  def repeatBodyAsync(implicit strategy: Strategy) = new AsyncProcessor() {
    def process(exchange: Exchange) = throw new UnsupportedOperationException
    def process(exchange: Exchange, callback: AsyncCallback) = {
      strategy.apply {
        val body = exchange.getIn.getBody(classOf[String])
        exchange.getIn.setBody(body + body)
        callback.done(false)
      }
      false
    }
  }
}
