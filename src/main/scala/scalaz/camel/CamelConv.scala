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
 */
trait CamelConv {
  import scalaz.concurrent.Strategy
  import Scalaz._
  import Message._

  type MessageValidation = Validation[Message, Message]
  type MessageProcessor = (Message, MessageValidation => Unit) => Unit
  type MessageValidationResponderKleisli = Kleisli[Responder, MessageValidation, MessageValidation]

  /**
   * Concurrency strategy for dispatching messages along the processor chain (i.e. route).
   */
  protected def dispatchStrategy: Strategy

  /**
   * Semigroup to 'append' failure messages. Returns the first failure message and ignores
   * the second. Needed for applicative usage of MessageValidation.
   */
  implicit def ExceptionSemigroup: Semigroup[Message] = semigroup((m1, m2) => m1)

  /**
   * A continuation monad for constructing routes from asynchronous message processors.
   * It applies the concurrency strategy returned by <code>dispatchStrategy</code> for
   * dispatching messages along the processor chain (i.e. route)
   */
  class MessageValidationResponder(v: MessageValidation, p: MessageProcessor) extends Responder[MessageValidation] {
    val updateExchange = (m1: Message) => (m2: Message) => m1.setExchange(m2)
    def respond(k: MessageValidation => Unit) = v match {
      case Success(m) => dispatchStrategy.apply(p(m, r => k(v <*> r ∘ updateExchange /* preserves MessageExchange */)))
      case Failure(m) => dispatchStrategy.apply(k(Failure(m)))
    }
  }

  def responderKleisli(p: MessageProcessor): MessageValidationResponderKleisli =
    kleisli((v: MessageValidation) => new MessageValidationResponder(v, p).map(r => r))

  def messageProcessor(p: MessageValidationResponderKleisli): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => p apply m.success respond k

  def messageProcessor(p: Message => Message): MessageProcessor =
    messageProcessor(p, Strategy.Sequential)

  def messageProcessor(p: Message => Message, s: Strategy): MessageProcessor = (m: Message, k: MessageValidation => Unit) =>
    s.apply { try { k(p(m).success) } catch { case e: Exception => k(m.setException(e).fail) } }

  def messageProcessor(uri: String, em: EndpointMgnt, cm: ContextMgnt): MessageProcessor =
    messageProcessor(em.createProducer(uri), cm)

  def messageProcessor(p: Processor, cm: ContextMgnt): MessageProcessor =
    if (p.isInstanceOf[AsyncProcessor]) messageProcessor(p.asInstanceOf[AsyncProcessor], cm)
    else messageProcessor(new ProcessorAdapter(p), cm)

  def messageProcessor(p: AsyncProcessor, cm: ContextMgnt): MessageProcessor = (m: Message, k: MessageValidation => Unit) => {
    import org.apache.camel.impl.DefaultExchange

    val me = new DefaultExchange(cm.context)

    me.getIn.fromMessage(m) // also updates the exchange pattern

    p.process(me, new AsyncCallback {
      def done(doneSync: Boolean) =
        if (me.isFailed) k(resultMessage(me).setException(me.getException).fail)
        else k(resultMessage(me).success)

      private def resultMessage(me: Exchange) = {
        val rm = if (me.hasOut) me.getOut else me.getIn
        me.setOut(null)
        me.setIn(rm)
        rm.toMessage
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
