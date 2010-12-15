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

import scala.Either.RightProjection
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
   *   <li>returning Left(exception) cause a Kleisli-composed route to stop message processing</li>
   *   <li>returning Right(message) cause a Kleisli-composed route to continue message processing</li>
   * </ul>
   */
  type RightProjectionMonad[B] = PartialApply1Of2[RightProjection, Exception]#Apply[B]

  /** Concrete Kleisli type of a message processor */
  type MessageProcessorKleisli = Kleisli[RightProjectionMonad, Message, Message]

  /** Message processor returning Right(message) when successful or Left(exception) on error */
  type MessageProcessor = Message => Either[Exception, Message]

  /** Function that combines two messages into into one */
  type MessageAggregator = (Message, Message) => Message

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
    (m: Message) => (p apply m).e

  def kleisliFunction(p: MessageProcessorKleisli) =
    kleisliFn[RightProjectionMonad, Message, Message](p)

  def kleisliProcessor(p: MessageProcessor) =
    kleisli[RightProjectionMonad, Message, Message]((m: Message) => p(m).right)

  private[camel] def messageProcessor(p: Message => Message): MessageProcessor = (m: Message) => {
    try {
      Right(p(m))
    } catch {
      case e: Exception => Left(e)
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
        Left(me.getException)
      } else {
        val rm = if (me.hasOut) me.getOut else me.getIn
        // post-process exchange
        me.setOut(null)
        me.setIn(rm)
        Right(rm.toMessage)
      }
    } catch {
      case e: Exception => Left(e)
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