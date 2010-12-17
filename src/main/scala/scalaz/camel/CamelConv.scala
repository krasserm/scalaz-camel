/*
 * Copyright 2010 the original author or authors.
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

import org.apache.camel.{CamelContext, Processor}

import scalaz._

/**
 * Defines the (implicit) conversions from message processing functions, Camel
 * processors and endpoint URIs to a concrete Kleisli type that can be used to
 * construct message-processing routes via Kleisli composition.
 *
 * @author Martin Krasser
 */
trait CamelConv {
  import Scalaz._
  import Message._

  /**
   * The monad used for Kleisli composition. This monad ensures that message processors
   * <ul>
   *   <li>returning Failure(exception) cause a Kleisli-composed route to stop message processing</li>
   *   <li>returning Success(message) cause a Kleisli-composed route to continue message processing</li>
   * </ul>
   */
  type ValidationMonad[B] = PartialApply1Of2[Validation, Exception]#Apply[B]

  /** Concrete Kleisli type of a message processor */
  type MessageProcessorKleisli = Kleisli[ValidationMonad, Message, Message]

  /** Message processor returning Success(message) when successful or Failure(exception) on error */
  type MessageProcessor = Message => Validation[Exception, Message]

  /** Function that combines two messages into into one */
  type MessageAggregator = (Message, Message) => Message

  /** Needed to use ValidationMonad as applicative functor */
  implicit def ExceptionSemigroup: Semigroup[Exception] = semigroup((e1, e2) => e1)

  /**
   * Converts a message processing function to a <code>MessageProcessorKleisli</code>.
   */
  implicit def messageProcessorFunctionToMessageProcessorKleisli(p: Message => Message): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(p))

  /**
   * Converts a Camel message processor to a <code>MessageProcessorKleisli</code>.
   */
  implicit def processorToMessageProcessorKleisli(p: Processor): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(p))

  /**
   * Converts a Camel endpoint defined by <code>uri</code> to a <code>MessageProcessorKleisli</code>
   * for producing to that endpoint.
   */
  implicit def uriStringToMessageProcessorKleisli(uri: String)(implicit context: CamelContext): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(uri, context))

  implicit def messageProcessorKleisliToMessageProcessor(p: MessageProcessorKleisli): MessageProcessor =
    (m: Message) => p apply m

  def kleisliFunction(p: MessageProcessorKleisli) =
    kleisliFn[ValidationMonad, Message, Message](p)

  def kleisliProcessor(p: MessageProcessor) =
    kleisli[ValidationMonad, Message, Message]((m: Message) => p(m))

  private[camel] def messageProcessor(p: Message => Message): MessageProcessor = (m: Message) => {
    try {
      p(m).success
    } catch {
      case e: Exception => e.fail
    }
  }

  private[camel] def messageProcessor(p: Processor): MessageProcessor = (m: Message) => {
    // TODO: operate on a copy of m.exchange otherwise processors could modify the shared exchange
    val me = m.exchange.getOrElse(throw new IllegalArgumentException("Message exchange not set"))

    // pre-process exchange
    me.getIn.fromMessage(m)
    me.setOut(null)

    try {
      p.process(me)
      if (me.isFailed) {
        me.getException.fail
      } else {
        val rm = if (me.hasOut) me.getOut else me.getIn
        // post-process exchange
        me.setOut(null)
        me.setIn(rm)
        rm.toMessage.success
      }
    } catch {
      case e: Exception => e.fail
    }
  }

  private[camel] def messageProcessor(uri: String, context: CamelContext): MessageProcessor = {
    val endpoint = context.getEndpoint(uri)
    val producer = endpoint.createProducer

    // TODO: manage producer (and do not start producer during route construction)
    // ...

    producer.start
    messageProcessor(producer)
  }
}