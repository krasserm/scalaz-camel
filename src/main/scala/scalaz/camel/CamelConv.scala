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

import org.apache.camel.Processor

import scalaz._

/**
 * Defines the (implicit) conversions from message processing functions, Camel
 * processors and endpoint URIs to a concrete Kleisli type that can be used to
 * construct message-processing routes via Kleisli composition.
 *
 * @author Martin Krasser
 */
trait CamelConv {
  import concurrent.Promise
  import concurrent.Strategy
  import Scalaz._
  import Message._

  /**
   * Type of the monad used for Kleisli composition. Kleisli functions returning
   * <ul>
   *   <li>Failure(exception) cause a Kleisli-composed route to stop message processing</li>
   *   <li>Success(message) cause a Kleisli-composed route to continue message processing</li>
   * </ul>
   */
  type ValidationMonad[B] = PartialApply1Of2[Validation, Exception]#Apply[B]

  /** Concrete Kleisli type of a message processor */
  type MessageProcessorKleisli = Kleisli[ValidationMonad, Message, Message]

  /** Message processor returning Success(message) when successful or Failure(exception) on error */
  type MessageProcessor = Message => Validation[Exception, Message]

  /** Function that combines two messages into into one */
  type MessageAggregator = (Message, Message) => Message

  /** Semigroup to append exceptions. Returns the first exception and ignores the second */
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
  implicit def uriStringToMessageProcessorKleisli(uri: String)(implicit mgnt: EndpointMgnt): MessageProcessorKleisli =
    kleisliProcessor(messageProcessor(uri, mgnt))

  implicit def messageProcessorKleisliToMessageProcessor(p: MessageProcessorKleisli): MessageProcessor =
    (m: Message) => p apply m

  def promiseFunction(p: MessageProcessor)(implicit s: Strategy) =
    kleisliFn[Promise, Message, Validation[Exception, Message]](p.promise(s))

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
    val me = m.exchange.getOrElse(throw new IllegalArgumentException("Message exchange not set"))
    val ce = me.copy

    // pre-process exchange
    ce.getIn.fromMessage(m)
    ce.setOut(null)

    try {
      p.process(ce)
      if (ce.isFailed) {
        ce.getException.fail
      } else {
        val rm = if (ce.hasOut) ce.getOut else ce.getIn
        // post-process exchange
        ce.setOut(null)
        ce.setIn(rm)
        rm.toMessage.setExchange(ce).success
      }
    } catch {
      case e: Exception => e.fail
    }
  }

  private[camel] def messageProcessor(uri: String, mgnt: EndpointMgnt): MessageProcessor =
    messageProcessor(mgnt.createProducer(uri))
}