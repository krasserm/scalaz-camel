package scalaz.camel

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange, Processor}

import scalaz._

/**
 * @author Martin Krasser
 *
 * @see scalaz.camel.async.Camel
 */
trait CamelConv {
  import Scalaz._
  import Message._

  // ----------------------------------------------------------------------------------------------
  //  This is currently an experimental alternative implementation to  scalaz.camel.CamelConv
  //  that supports asynchronous message processors and non-blocking routes based on continuations
  //  (scala.Responder, a continuation monad). However, the DSL syntax is the same. This approach
  //  will either replace the approach followed in scalaz.camel.CamelConv or a combined usage will
  //  be supported.
  // ----------------------------------------------------------------------------------------------

  type MessageValidation = Validation[Exception, Message]
  type MessageAggregator = (Message, Message) => Message
  type MessageProcessor = (Message, MessageValidation => Unit) => Unit
  type MessageProcessorKleisli = Kleisli[Responder, MessageValidation, MessageValidation]

  class MessageResponder(v: MessageValidation, p: MessageProcessor) extends Responder[MessageValidation] {
    def respond(k: (MessageValidation) => Unit) = v match {
      case Success(m) => p(m, r => k(r))
      case Failure(e) => k(Failure(e))
    }
  }

  //
  // implicit conversions
  //

  implicit def messageProcessorFunctionToMessageProcessorKleisli(p: MessageProcessor): MessageProcessorKleisli =
    kleisliProcessor(p)

  implicit def messageProcessorFunctionToMessageProcessorKleisli(p: Message => Message): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(p))

  implicit def messageMessageProcessorToMessageProcessorKleisli(p: Processor): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(p))

  implicit def uriStringToMessageProcessorKleisli(uri: String)(implicit mgnt: EndpointMgnt): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(uri, mgnt))

  /** Semigroup to append exceptions. Returns the first exception and ignores the second */
  implicit def ExceptionSemigroup: Semigroup[Exception] = semigroup((e1, e2) => e1)

  //
  // factory methods for MessageProcessorKleisli
  //

  def kleisliProcessor(p: MessageProcessor): MessageProcessorKleisli =
    kleisli((v: MessageValidation) => new MessageResponder(v, p).map(r => r))

  // 
  // factory methods for MessageProcessor
  //

  protected def messageProcessor(p: MessageProcessorKleisli): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => p apply m.success respond k

  protected def messageProcessor(uri: String, mgnt: EndpointMgnt): MessageProcessor =
    messageProcessor(mgnt.createProducer(uri))

  protected def messageProcessor(p: Processor): MessageProcessor =
    if (p.isInstanceOf[AsyncProcessor]) messageProcessor(p.asInstanceOf[AsyncProcessor])
    else messageProcessor(new ProcessorAdapter(p))

  protected def messageProcessor(p: AsyncProcessor): MessageProcessor = (m: Message, k: MessageValidation => Unit) => {
    val me = m.exchange.getOrElse(throw new IllegalArgumentException("Message exchange not set"))
    val ce = me.copy

    ce.getIn.fromMessage(m)
    ce.setOut(null)

    p.process(ce, new CallbackHandler(ce, k))
  }

  protected def messageProcessor(p: Message => Message): MessageProcessor = (m: Message, k: MessageValidation => Unit) => {
    try {
      k(p(m).success)
    } catch {
      case e: Exception => k(e.fail)
    }
  }

  //
  // Other
  //

  private class ProcessorAdapter(p: Processor) extends AsyncProcessor {
    def process(exchange: Exchange) = throw new UnsupportedOperationException()
    def process(exchange: Exchange, callback: AsyncCallback) = {
      try {
        p.process(exchange)
      } catch {
        case e: Exception => exchange.setException(e)
      }
      callback.done(true)
      true
    }
  }

  private class CallbackHandler(me: Exchange, k: MessageValidation => Unit) extends AsyncCallback {
    def done(doneSync: Boolean) =
      if (me.isFailed) k(me.getException.fail)
      else k(resultMessage(me).success)
    
    private def resultMessage(me: Exchange) = {
      val rm = if (me.hasOut) me.getOut else me.getIn
      me.setOut(null)
      me.setIn(rm)
      rm.toMessage.setExchange(me)
    }
  }
}