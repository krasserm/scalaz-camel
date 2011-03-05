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
package scalaz.camel.akka

import java.util.Collection
import java.util.concurrent.ThreadPoolExecutor

import akka.actor.ActorRef

import org.apache.camel._
import org.apache.camel.impl.ServiceSupport
import org.apache.camel.spi.LifecycleStrategy

import scalaz.camel.core.ContextMgnt

/**
 * Manages the life cycle of actors. Should be used as follows:
 *
 * <pre>
 * val context: CamelContext = ...
 * implicit val router = new Router(context) with ActorMgnt
 * </pre>
 *
 * @author Martin Krasser
 */
trait ActorMgnt { this: ContextMgnt =>

  /**
   * Binds the life cycle of <code>actor</code> to that of the current CamelContext.
   */
  def manage(actor: ActorRef): ActorRef = {
    context.addLifecycleStrategy(new LifecycleSync(actor))
    val service = context.asInstanceOf[ServiceSupport]
    if (service.isStarted) actor.start else actor
  }
}

/**
 * @author Martin Krasser
 */
private[camel] class LifecycleSync(actor: ActorRef) extends LifecycleStrategy {
  import org.apache.camel.spi.RouteContext
  import org.apache.camel.builder.ErrorHandlerBuilder

  def onContextStart(context: CamelContext) = actor.start
  def onContextStop(context: CamelContext) = actor.stop

  // no action by default
  def onThreadPoolAdd(camelContext: CamelContext, threadPool: ThreadPoolExecutor) = {}
  def onErrorHandlerAdd(routeContext: RouteContext, errorHandler: Processor, errorHandlerBuilder: ErrorHandlerBuilder) = {}
  def onRouteContextCreate(routeContext: RouteContext) = {}
  def onRoutesRemove(routes: Collection[Route]) = {}
  def onRoutesAdd(routes: Collection[Route]) = {}
  def onServiceRemove(context: CamelContext, service: Service, route: Route) = {}
  def onServiceAdd(context: CamelContext, service: Service, route: Route) = {}
  def onEndpointRemove(endpoint: Endpoint) = {}
  def onEndpointAdd(endpoint: Endpoint) = {}
  def onComponentRemove(name: String, component: Component) = {}
  def onComponentAdd(name: String, component: Component) = {}
}
