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
package scalaz.camel.core

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange, Processor}

import scalaz._
import Scalaz._
import scalaz.concurrent._

object Conv {
  case class PromiseEither[A, B](value: Promise[Either[A, B]]) extends NewType[Promise[Either[A, B]]]

  implicit def PromiseEitherMonad[L](implicit s: Strategy) =
    new Monad[({type l[b] = PromiseEither[L, b]})#l] {
      def pure[A](a: => A) = PromiseEither(promise(a.right))
      def bind[A, B](a: PromiseEither[L, A], f: A => PromiseEither[L, B]): PromiseEither[L, B] = {
        val pb = a.value.flatMap[Either[L, B]] {
          case Left(l) => promise(l.left)
          case Right(r) => f(r).value
        }
        PromiseEither(pb)
      }
    }

  implicit def PromiseEitherApply[L]: Apply[({type λ[α]=PromiseEither[L, α]})#λ] =
    FunctorBindApply[({type λ[α]=PromiseEither[L, α]})#λ]

  type PromiseEitherMessage[α] = ({type λ[α] = PromiseEither[Message, α]})#λ[α]
}


/**
 * Provides converters for constructing message processing routes from
 *
 * <ul>
 * <li>asynchronous message processing functions (Scala, see <code>MessageProcessor</code>)</li>
 * <li>synchronous Camel processors</li>
 * <li>asynchronous Camel processors</li>
 * <li>synchronous endpoint producers</li>
 * <li>asynchronous endpoint producers</li>
 * </ul>
 *
 * Related implicit conversions are provided by the <code>Camel</code> object.
 *
 * @author Martin Krasser
 */
trait Conv {
  import Scalaz._
  import Message._
  import Conv._

  var strategy = Strategy.Sequential

  // ----------------------------------------------------------------

  type MessageValidation = Either[Message, Message]

  type MessageProcessor = Message => MessageValidation

  type ConcurrentValidation = PromiseEither[Message, Message]

  type ConcurrentProcessor = Message => ConcurrentValidation

  // ----------------------------------------------------------------

  type ConcurrentRoute = Kleisli[PromiseEitherMessage, Message, Message]

  // ----------------------------------------------------------------

  def messageProcessor2concurrentProcessor(p: MessageProcessor): ConcurrentProcessor =
    (m: Message) => PromiseEither(promise(p(m))(strategy))

  def messageProcessor2concurrentRoute(p: MessageProcessor): ConcurrentRoute =
    kleisli[PromiseEitherMessage, Message, Message](messageProcessor2concurrentProcessor(p))

  def endpointUri2concurrentProcessor(uri: String, em: EndpointMgnt, cm: ContextMgnt): ConcurrentProcessor =
    camelProcessor2concurrentProcessor(em.createProducer(uri), cm)

  def camelProcessor2concurrentProcessor(p: Processor, cm: ContextMgnt): ConcurrentProcessor =
    if (p.isInstanceOf[AsyncProcessor]) camelProcessor2concurrentProcessor(p.asInstanceOf[AsyncProcessor], cm)
    else camelProcessor2concurrentProcessor(new ProcessorAdapter(p), cm)

  def camelProcessor2concurrentProcessor(p: AsyncProcessor, cm: ContextMgnt): ConcurrentProcessor = (m: Message) => {
    import org.apache.camel.impl.DefaultExchange

    val me = new DefaultExchange(cm.context)

    me.getIn.fromMessage(m)

    val promise = new Promise[MessageValidation]()(strategy)

    p.process(me, new AsyncCallback {
      def done(doneSync: Boolean) =
        if (me.isFailed)
          promise.fulfill(resultMessage(me).left)
        else
          promise.fulfill(resultMessage(me).right)

      private def resultMessage(me: Exchange) = {
        val rm = if (me.hasOut) me.getOut else me.getIn
        me.setOut(null)
        me.setIn(rm)
        rm.toMessage
      }
    })

    PromiseEither(promise)
  }

  // ----------------------------------------------------------------

  /*

  /**
   * Type of a failed or successful response message. <strong>Will be replaced by
   * <code>Either[Message, Message]</code> with scalaz versions greater than 5.0.</strong>
   */
  type MessageValidation = Validation[Message, Message]

  /**
   * Type of a (potentially asynchronous) message processor that passes a message validation
   * result to a continuation of type <code>MessageValidation => Unit</code>. A CPS message
   * processor.
   */
  type MessageProcessor = (Message, MessageValidation => Unit) => Unit

  /**
   * Type of a message processing route or a single route component. These can be composed
   * via Kleisli composition.
   */
  type MessageRoute = Kleisli[Responder, MessageValidation, MessageValidation]

  /**
   * Set the message exchange of m2 on m1 unless an exchange update should be skipped.
   */
  private val updateExchange = (m1: Message) => (m2: Message) =>
    (if (!m1.skipExchangeUpdate) m1.setExchangeFrom(m2) else m1).setSkipExchangeUpdate(false)

  /**
   *  Concurrency strategy for dispatching messages along the processor chain (i.e. route).
   */
  protected def dispatchStrategy: Strategy

  /**
   * Semigroup to 'append' failure messages. Returns the first failure message and ignores
   * the second. Needed for applicative usage of MessageValidation.
   */
  implicit def ExceptionSemigroup: Semigroup[Message] = semigroup((m1, m2) => m1)

  /**
   * A continuation monad for constructing routes from CPS message processors. It applies the
   * concurrency strategy returned by <code>dispatchStrategy</code> for dispatching messages
   * along the processor chain (i.e. route). Success messages are dispatched to the next processor
   * which in turn passes its result to continuation <code>k</code>. Failure messages are passed
   * directly to continuation k (by-passing the remaining processors in the chain).
   */
  class MessageValidationResponder(v: MessageValidation, p: MessageProcessor) extends Responder[MessageValidation] {
    def respond(k: MessageValidation => Unit) = v match {
      case Success(m) => dispatchStrategy.apply(p(m, r => k(v <*> r ∘ updateExchange /* experimental */)))
      case Failure(m) => dispatchStrategy.apply(k(Failure(m)))
    }
  }

  /**
   * Creates a message processing route component from a CPS message processor
   */
  def messageRoute(p: MessageProcessor): MessageRoute =
    kleisli((v: MessageValidation) => new MessageValidationResponder(v, p).map(r => r))

  /**
   * Creates a CPS message processor from a message processing route
   */
  def messageProcessor(p: MessageRoute): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => p apply m.success respond k

  /**
   * Creates a CPS message processor from a direct-style message processor. The created
   * CPS processor executes the direct-style message processor synchronously.
   */
  def messageProcessor(p: Message => Message): MessageProcessor =
    messageProcessor(p, Strategy.Sequential)

  /**
   * Creates an CPS message processor from a direct-style message processor. The created
   * CPS processor executes the direct-style processor using the concurrency strategy
   * <code>s</code>.
   */
  def messageProcessor(p: Message => Message, s: Strategy): MessageProcessor = (m: Message, k: MessageValidation => Unit) =>
    s.apply { try { k(p(m).success) } catch { case e: Exception => k(m.setException(e).fail) } }

  /**
   * Creates a CPS message processor from a Camel message producer obtained from an endpoint
   * defined by URI. This method has a side-effect because it registers the created producer
   * at the Camel context for lifecycle management.
   */
  def messageProcessor(uri: String, em: EndpointMgnt, cm: ContextMgnt): MessageProcessor =
    messageProcessor(em.createProducer(uri), cm)

  /**
   * Creates a CPS message processor from a (synchronous or asynchronous) Camel processor.
   */
  def messageProcessor(p: Processor, cm: ContextMgnt): MessageProcessor =
    if (p.isInstanceOf[AsyncProcessor]) messageProcessor(p.asInstanceOf[AsyncProcessor], cm)
    else messageProcessor(new ProcessorAdapter(p), cm)

  /**
   * Creates a CPS message processor from an asynchronous Camel processor.
   */
  def messageProcessor(p: AsyncProcessor, cm: ContextMgnt): MessageProcessor = (m: Message, k: MessageValidation => Unit) => {
    import org.apache.camel.impl.DefaultExchange

    val me = new DefaultExchange(cm.context)

    me.getIn.fromMessage(m)

    p.process(me, new AsyncCallback {
      def done(doneSync: Boolean) =
        if (me.isFailed)
          k(resultMessage(me).fail)
        else
          k(resultMessage(me).success)

      private def resultMessage(me: Exchange) = {
        val rm = if (me.hasOut) me.getOut else me.getIn
        me.setOut(null)
        me.setIn(rm)
        rm.toMessage
      }
    })
  }
  */

  /**
   * An <code>AsyncProcessor</code> interface for a (synchronous) Camel <code>Processor</code>.
   */
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
