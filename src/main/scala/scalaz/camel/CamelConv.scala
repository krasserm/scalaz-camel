/*
 * Copyright 2010-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scalaz.camel

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange, Processor}

import scalaz._

/**
 * @author Martin Krasser
 *
 * @see scalaz.camel.Camel
 */
trait CamelConv {
  import scalaz.concurrent.Strategy
  import Scalaz._
  import Message._

  type MessageValidation = Validation[Exception, Message]
  type MessageAggregator = (Message, Message) => Message
  type MessageProcessor = (Message, MessageValidation => Unit) => Unit

  type MessageValidationResponderKleisli = Kleisli[Responder, MessageValidation, MessageValidation]

  /**
   * Concurrency strategy for dispatching messages along the processor chain (i.e. route).
   */
  protected def dispatchStrategy: Strategy

  /**
   * A continuation monad for constructing routes from asynchronous message processors.
   * It applies the concurrency strategy returned by <code>dispatchStrategy</code> for
   * dispatching messages along the processor chain (i.e. route)
   */
  class MessageValidationResponder(v: MessageValidation, p: MessageProcessor) extends Responder[MessageValidation] {
    def respond(k: MessageValidation => Unit) = v match {
      case Success(m) => dispatchStrategy.apply(p(m, r => k(r)))
      case Failure(e) => dispatchStrategy.apply(k(Failure(e)))
    }
  }

  def responderKleisli(p: MessageProcessor): MessageValidationResponderKleisli =
    kleisli((v: MessageValidation) => new MessageValidationResponder(v, p).map(r => r))

  def messageProcessor(p: MessageValidationResponderKleisli): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => p apply m.success respond k

  def messageProcessor(uri: String, em: EndpointMgnt): MessageProcessor =
    messageProcessor(em.createProducer(uri))

  def messageProcessor(p: Processor): MessageProcessor =
    if (p.isInstanceOf[AsyncProcessor]) messageProcessor(p.asInstanceOf[AsyncProcessor])
    else messageProcessor(new ProcessorAdapter(p))

  def messageProcessor(p: Message => Message): MessageProcessor =
    messageProcessor(p, Strategy.Sequential)

  def messageProcessor(p: Message => Message, s: Strategy): MessageProcessor = (m: Message, k: MessageValidation => Unit) =>
    s.apply { try { k(p(m).success) } catch { case e: Exception => k(e.fail) } }

  def messageProcessor(p: AsyncProcessor): MessageProcessor = (m: Message, k: MessageValidation => Unit) => {
    val me = m.exchange.getOrElse(throw new IllegalArgumentException("Message exchange not set"))
    val ce = me.copy

    ce.getIn.fromMessage(m)
    ce.setOut(null)

    p.process(ce, new AsyncCallback {
      def done(doneSync: Boolean) =
        if (ce.isFailed) k(ce.getException.fail)
        else k(resultMessage(ce).success)

      private def resultMessage(me: Exchange) = {
        val rm = if (me.hasOut) me.getOut else me.getIn
        me.setOut(null)
        me.setIn(rm)
        rm.toMessage.setExchange(me)
      }
    })
  }

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
}