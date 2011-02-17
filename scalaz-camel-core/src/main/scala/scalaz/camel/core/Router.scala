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

import java.util.Collection
import java.util.concurrent.ThreadPoolExecutor

import org.apache.camel._
import org.apache.camel.impl.ServiceSupport
import org.apache.camel.spi.LifecycleStrategy

/**
 * Registers created endpoint consumers and producers at the CamelContext for
 * lifecycle management.
 *
 * @author Martin Krasser
 */
trait EndpointMgnt { this: ContextMgnt =>
  /**
   * Creates a consumer for endpoint defined by <code>uri</code>. The consumer is
   * registered at the CamelContext for lifecycle management. This method can be
   * used in context of both, started and not-yet-started CamelContext instances.
   *
   * @param uri endpoint URI.
   * @param p processor that is connected to the created endpoint consumer. 
   */
  def createConsumer(uri: String, p: Processor): Consumer = {
    val service = context.asInstanceOf[ServiceSupport]
    val endpoint = context.getEndpoint(uri)
    val consumer = endpoint.createConsumer(p)

    context.addLifecycleStrategy(new LifecycleSync(consumer))

    if (service.isStarted) {
      endpoint.start // needed?
      consumer.start
    }

    consumer
  }

  /**
   * Creates a producer for endpoint defined by <code>uri</code>. The producer is
   * registered at the CamelContext for lifecycle management. This method can be
   * used used in context of both, started and not-yet-started CamelContext instances.
   *
   * @param uri endpoint URI.
   */
  def createProducer(uri: String): Producer = {
    val service = context.asInstanceOf[ServiceSupport]
    val endpoint = context.getEndpoint(uri)
    val producer = endpoint.createProducer

    context.addLifecycleStrategy(new LifecycleSync(producer))

    if (service.isStarted) {
      producer.start
    }

    producer
  }
}

/**
 * Manages a CamelContext
 *
 * @author Martin Krasser
 */
trait ContextMgnt {
  /** Managed CamelContext */
  val context: CamelContext

  /** Starts the <code>context</code> */
  def start = context.start
  /** Stops the <code>context</code> */
  def stop = context.stop
}

/**
 * The Camel context and endpoint manager (implicitly) needed in context of routes.
 *
 * @author Martin Krasser
 */
class Router(val context: CamelContext) extends EndpointMgnt with ContextMgnt {
  import org.apache.camel.component.direct.DirectComponent

  context.addComponent("direct", new DirectNostop)

  private class DirectNostop extends DirectComponent {
    override def doStop {}
  }
}

/**
 * @author Martin Krasser
 */
private[camel] class LifecycleSync(service: Service) extends LifecycleStrategy {
  import org.apache.camel.spi.RouteContext
  import org.apache.camel.builder.ErrorHandlerBuilder

  def onContextStart(context: CamelContext) = service.start
  def onContextStop(context: CamelContext) = service.stop

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
