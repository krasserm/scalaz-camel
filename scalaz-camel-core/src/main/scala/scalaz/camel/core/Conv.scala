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
 * @author Martin Krasser
 */
trait Conv {
  import Scalaz._
  import Message._
  import Conv._

  var strategy = Strategy.Sequential // default concurrency strategy

  type MessageValidation = Either[Message, Message]

  type MessageProcessor = Message => MessageValidation

  type ConcurrentValidation = PromiseEither[Message, Message]

  type ConcurrentProcessor = Message => ConcurrentValidation

  type ConcurrentRoute = Kleisli[PromiseEitherMessage, Message, Message]

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
