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

  // asynchronous message processor that fails with given error message
  def asyncFailWith(em: String)(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(new Exception(em).fail))

  // asynchronous message processor that appends s to message body
  def asyncAppendString(s: String)(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(m.appendBody(s).success))

  // asynchronous message processor that converts message body to String
  def asyncConvertToString(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(m.bodyTo[String].success))

  // asynchronous message processor (Camel interface) that repeats message body
  def asyncRepeatBody(implicit strategy: Strategy) = new AsyncProcessor() {
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
