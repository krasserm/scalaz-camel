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
