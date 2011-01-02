package scalaz.camel.async

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange}

import scalaz._
import scalaz.camel.{Message, EndpointMgnt}

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
  type MessageProcessor = (Message, MessageValidation => Unit) => Unit
  type MessageProcessorKleisli = Kleisli[Responder, MessageValidation, MessageValidation]

  class MessageResponder(p: MessageProcessor, v: MessageValidation) extends Responder[MessageValidation] {
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

  implicit def messageMessageProcessorToMessageProcessorKleisli(p: AsyncProcessor): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(p))

  implicit def uriStringToMessageProcessorKleisli(uri: String)(implicit mgnt: EndpointMgnt): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(uri, mgnt))

  //
  // factory methods for MessageProcessorKleisli
  //

  def kleisliProcessor(p: MessageProcessor): MessageProcessorKleisli =
    kleisli((v: MessageValidation) => new MessageResponder(p, v).map(r => r))

  // 
  // factory methods for MessageProcessor
  //

  private def messageProcessor(uri: String, mgnt: EndpointMgnt): MessageProcessor =
    messageProcessor(mgnt.createProducer(uri).asInstanceOf[AsyncProcessor]) // TODO: handle ClassCastExceptions

  private def messageProcessor(p: AsyncProcessor): MessageProcessor = (m: Message, k: MessageValidation => Unit) => {

    //
    // TODO: accept Processor as well
    //       - if instance of AsyncProcessor ...
    //       - if instance of Processor then wait for processing is done
    //

    val me = m.exchange.getOrElse(throw new IllegalArgumentException("Message exchange not set"))
    val ce = me.copy

    ce.getIn.fromMessage(m)
    ce.setOut(null)

    p.process(ce, new Handler(ce, k))
  }

  //
  // Other
  //

  private class Handler(me: Exchange, k: MessageValidation => Unit) extends AsyncCallback {
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