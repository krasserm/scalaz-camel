package scalaz.camel

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange, Processor}

import scalaz._

/**
 * @author Martin Krasser
 */
trait ExampleSupport {
  import scalaz.concurrent.Strategy
  import Scalaz._
  import Camel.MessageValidation
  import Camel.MessageProcessor

  // ----------------------------------------
  //  Asynchronous message processors
  // ----------------------------------------

  // Fails with given error message
  def asyncFailWith(em: String)(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    asyncFailWith(new Exception(em))

  // Fails with given exception
  def asyncFailWith(e: Exception)(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(e.fail))

  // Appends s to message body
  def asyncAppendString(s: String)(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(m.appendBody(s).success))

  // Converts message body to String
  def asyncConvertToString(implicit strategy: Strategy, mgnt: ContextMgnt): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => strategy.apply(k(m.bodyTo[String].success))

  //  Repeats message body (Camel interface)
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

  // ----------------------------------------
  //  Synchronous message processors
  // ----------------------------------------

  // Marks an exception as handled for a message
  val syncMarkHandled = (msg: Message) => msg.exceptionHandled

  // Appends s to message body
  def syncAppendString(s: String)(implicit mgnt: ContextMgnt) = (m: Message) => m.appendBody(s)

  //  Repeats message body (Camel interface)
  def syncRepeatBody = new Processor() {
    def process(exchange: Exchange) = {
      val body = exchange.getIn.getBody(classOf[String])
      exchange.getIn.setBody(body + body)
    }
  }
}