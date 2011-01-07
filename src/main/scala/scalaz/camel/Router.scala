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

import java.util.Collection
import java.util.concurrent.ThreadPoolExecutor

import org.apache.camel._
import org.apache.camel.impl.ServiceSupport
import org.apache.camel.spi.LifecycleStrategy

/**
 * @author Martin Krasser
 */
trait EndpointMgnt { this: ContextMgnt =>
  def createConsumer(uri: String, p: Processor): Consumer = {
    val service = context.asInstanceOf[ServiceSupport]
    val endpoint = context.getEndpoint(uri)
    val consumer = endpoint.createConsumer(p)

    context.addLifecycleStrategy(new LifecycleSync(consumer))

    if (service.isStarted) {
      endpoint.start
      consumer.start
    }

    consumer
  }

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
 * @author Martin Krasser
 */
trait ContextMgnt {
  val context: CamelContext

  def start = context.start
  def stop = context.stop
}

/**
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
