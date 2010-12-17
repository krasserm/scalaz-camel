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

import org.apache.camel.{CamelContext, Exchange, Processor}

import scalaz._

/**
 * Domain-specific language (DSL) focused on creating message processing routes via Kleisli
 * composition. Provides additional combinators representing enterprise integration patterns
 * (EIPs) such as content-based router or recipient-list.
 *
 * @author Martin Krasser
 */
trait CamelDsl extends CamelConv {
  import Scalaz._
  import Message._

  // ------------------------------------------
  //  DSL (EIPs)
  // ------------------------------------------

  /** The content-based router EIP */
  def choose(f: PartialFunction[Message, MessageProcessorKleisli]) =
    kleisli[ValidationMonad, Message, Message]((m: Message) => f(m) apply m)

  /** The multicast EIP */
  def multicast(destinations: MessageProcessorKleisli*)(aggregator: MessageAggregator): MessageProcessorKleisli =
    kleisliProcessor((msg: Message) => {
        // apply all destination routes to the given message and store the results as list
        val results = msg.pure[List] <*> destinations.toList ∘ kleisliFunction
        // aggregate the results by folding over the list and return
        // - the aggregated message if all applications were successful i.e. Success(message)
        // - return the first exception if one or more applications failed i.e. Failure(exception)
        results.tail.foldLeft(results.head) { (z, m) => m <*> z ∘ aggregator.curried }
      }
    )

  // ------------------------------------------
  //  DSL (route initiation)
  // ------------------------------------------

  /** Defines the endpoint from where routes/processors consume */
  def from(uri: String) = new MainRouteDefinition(uri)

  /**
   * Builder for the main route or processor.
   *
   * <pre>
   * from("...") route {
   *   // main route defined here ...
   * }
   * </pre>
   *
   * or
   *
   * <pre>
   * from("...") processor { m: Message =>
   *   // main processor defined here ...
   * }
   * </pre>
   */
  class MainRouteDefinition(uri: String) {
    /** Connects the main route <code>r</code> with the endpoint defined by <code>uri</code>. */
    def route(r: MessageProcessorKleisli)(implicit context: CamelContext): ErrorRouteDefinition =
      process((m: Message) => r apply m)(context)

    /** Connects the main processor <code>p</code> with the endpoint defined by <code>uri</code>. */
    def process(p: MessageProcessor)(implicit context: CamelContext): ErrorRouteDefinition = {
      val processor = new RouteProcessor((m: Message) => p(m)) with ErrorRouteDefinition
      val endpoint = context.getEndpoint(uri)

      // TODO: manage consumer (and do not start consumer during route construction)
      // ...

      endpoint.start
      endpoint.createConsumer(processor).start
      processor
    }
  }

  /**
   * Builder for the error handling routes or processors.
   *
   * <pre>
   * from("...") route {
   *   // ...
   * } onError classOf[Exception1] route { m: Message =>
   *   // error handling route 1 defined here ...
   * } onError classOf[Exception2] route { m: Message =>
   *   // error handling route 2 defined here ...
   * } ...
   * </pre>
   *
   * or
   *
   * <pre>
   * from("...") processor {
   *   // ...
   * } onError classOf[Exception1] processor { m: Message =>
   *   // error handling processor 1 defined here ...
   * } onError classOf[Exception2] processor { m: Message =>
   *   // error handling processor 2 defined here ...
   * } ...
   * </pre>
   */
  trait ErrorRouteDefinition {

    // TODO: support more than one error handler
    // currently only one is supported (or the last one if several are defined)

    /*
     * from(...) route {
     *   ...
     * } onError classOf[Exception1] route {
     *   ...
     * } onError classOf[Exception2] route {
     *   ...
     * }
     */

    var handler: Option[MessageProcessor] = None

    /** Defines the error handling route for an exception given by <code>onError</code> */
    def route(r: MessageProcessorKleisli) = {
      handler = Some(r)
      this
    }

    /** Defines the error handling processor for an exception given by <code>onError</code> */
    def process(p: MessageProcessor) = {
      handler = Some(p)
      this
    }

    /** Sets the exception for which an error handling route or processor can be defined */
    def onError[A <: Exception](e: Class[A] /* TODO: use param */) = this
  }

  // ------------------------------------------
  //  Internal
  // ------------------------------------------

  private class RouteProcessor(p: MessageProcessor) extends Processor { this: ErrorRouteDefinition =>
    def process(exchange: Exchange) = route(exchange.getIn.toMessage, p, handler)
    def route(message: Message, target: MessageProcessor, handler: Option[MessageProcessor]) {
      val exchange = message.exchange
      target(message) match {
        case Failure(e) => {
          for (me <- exchange) {
            me.setException(e)
          }
          for (me <- exchange; h <- handler) {
            me.setException(null)
            // route original message to error handler
            // with the exception set on message header
            route(message.setException(e), h, None)
          }
        }
        case Success(m) => {
          for (me <- exchange; e <- m.exception) {
            me.setException(e)
          }
          for (me <- exchange) {
            me.getIn.fromMessage(m)
            me.getOut.fromMessage(m)
          }
        }
      }
    }
  }
}
