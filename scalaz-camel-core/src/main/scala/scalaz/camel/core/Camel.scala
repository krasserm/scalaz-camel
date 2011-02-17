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

/**
 * Provides the Camel DSL. To use it in your application use the following template.
 *
 * <pre>
 * import scalaz._
 * import scalaz.camel._
 *
 * class Foo {
 *   import Scalaz._
 *   import Camel._
 *
 *   ...
 * }
 * </pre>
 *
 * @author Martin Krasser
 */
object Camel extends DslEip with DslAttempt with DslEndpoint with DslApply with Conv {
  import org.apache.camel.Processor
  import scalaz.concurrent.Strategy

  /**
   * Concurrency strategy to use for dispatching messages along the processor chain.
   * Defaults to <code>Strategy.Sequential</code>.
   */
  var dispatchConcurrencyStrategy: Strategy = Strategy.Sequential

  /**
   * Concurrency strategy to use for distributing messages to destinations with the
   * multicast and scatter-gather EIPs. Defaults to <code>Strategy.Sequential</code>.
   */
  var multicastConcurrencyStrategy: Strategy = Strategy.Sequential

  protected def dispatchStrategy = dispatchConcurrencyStrategy
  protected def multicastStrategy = multicastConcurrencyStrategy

  implicit def messageProcessorToMessageRoute(p: MessageProcessor): MessageRoute =
    messageRoute(p)

  implicit def messageProcessorToMessageRoute(p: Message => Message): MessageRoute =
    messageRoute(messageProcessor(p))

  implicit def camelProcessorToMessageRoute(p: Processor)(implicit cm: ContextMgnt): MessageRoute =
    messageRoute(messageProcessor(p, cm))

  implicit def responderToResponderApplication(r: Responder[MessageValidation]) =
    new ResponderApplication(r)

  implicit def routeToRouteApplication(p: MessageRoute) =
    new RouteApplication(p)
}