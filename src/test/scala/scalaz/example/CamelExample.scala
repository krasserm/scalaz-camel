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
package scalaz.example

import org.scalatest.matchers.MustMatchers

import scalaz._
import scalaz.camel._
import scalaz.concurrent.Strategy

// Oversimplified purchase order domain model
case class PurchaseOrder(items: List[PurchaseOrderItem])
case class PurchaseOrderItem(customer: Int, category: String, name: String, amount: Int)
case class ValidationException(msg: String) extends Exception(msg)

/**
 * @author Martin Krasser
 */
object CamelExample extends MustMatchers {
  import Scalaz._
  import Camel._

  def main(args: Array[String]) = run

  def run: Unit = {

    // -----------------------------------------------------------------
    //  Application setup
    // -----------------------------------------------------------------

    val executor = java.util.concurrent.Executors.newFixedThreadPool(3)

    // use a custom concurrency strategy for routing
    // messages along the message processing chain(s)
    Camel.dispatchConcurrencyStrategy = Strategy.Executor(executor)

    // setup Camel context and producer template
    import org.apache.camel.spring.SpringCamelContext._
    val context = springCamelContext("/context.xml")
    val template = context.createProducerTemplate

    // setup and start router
    implicit val router = new Router(context); router.start

    // -----------------------------------------------------------------
    //  Application-specific message processors
    // -----------------------------------------------------------------

    // Continuation-passing style (CPS) message processor that validates order messages
    // in a separate thread (created by Strategy.Naive). Validation responses are sent
    // asynchronously via k.
    val validateOrder: MessageProcessor = (m: Message, k: MessageValidation => Unit) => {
      Strategy.Naive.apply(m.body match {
        case order: PurchaseOrder if (!order.items.isEmpty) => k(m.success)
        case _ => k(m.setException(ValidationException("invalid order")).fail)
      })
    }

    // Direct-style message processor that transforms an order item to a tuple. Synchronous processor.
    val orderItemToTuple = (m: Message) => m.transform[PurchaseOrderItem](i => (i.customer, i.name, i.amount))

    // -----------------------------------------------------------------
    //  Route definitions (Kleisli composition of message processors)
    // -----------------------------------------------------------------

    // order placement route (main route)
    val placeOrderRoute = validateOrder >=> oneway >=> to("jms:queue:valid") >=> { m: Message => m.setBody("order accepted") }

    // order placement route consuming from direct:place-order endpoint (incl. error handler)
    from("direct:place-order") {
      attempt { placeOrderRoute } fallback {
        case e: ValidationException => { m: Message => m.setBody("order validation failed")}
        case e: Exception           => { m: Message => m.setBody("general processing error")} >=> failWith(e)
      }
    }

    // order processing route
    from("jms:queue:valid") {
      split { m: Message => for (item <- m.bodyAs[PurchaseOrder].items) yield m.setBody(item) } >=> choose {
        case Message(PurchaseOrderItem(_, "books", _, _), _) => orderItemToTuple >=> to("mock:books")
        case Message(PurchaseOrderItem(_, "bikes", _, _), _) => to("mock:bikes")
      } >=> { m: Message => println("received order item = %s" format m.body); m }
    }

    // -----------------------------------------------------------------
    //  Route application
    // -----------------------------------------------------------------

    import CamelExampleAsserts.assertOrderProcessed

    val order = PurchaseOrder(List(
      PurchaseOrderItem(123, "books", "Camel in Action", 1),
      PurchaseOrderItem(123, "books", "DSLs in Action", 1),
      PurchaseOrderItem(123, "bikes", "Canyon Torque FRX", 1)
    ))

    val orderMessage = Message(order)


    // usage of producer template (requestBody blocks)
    {
      template.requestBody("direct:place-order", order)   must equal ("order accepted")
      template.requestBody("direct:place-order", "wrong") must equal ("order validation failed")
    }

    // usage of responseFor (responseFor blocks)
    {
      assertOrderProcessed { placeOrderRoute responseFor orderMessage }
      placeOrderRoute responseFor Message("wrong") match {
        case Failure(m) => m.body must equal ("wrong")
        case Success(m) => throw new Exception("unexpected success")
      }
    }

    // usage of responsePromiseFor (responsePromiseFor does not block, promise.get blocks)
    {
      implicit val strategy = Strategy.Naive // needed for creation of promise
      assertOrderProcessed { val promise = placeOrderRoute responsePromiseFor orderMessage; promise.get }
    }

    // continuation-passing style, CPS (respond does not block, latch.await blocks)
    {
      import java.util.concurrent.CountDownLatch
      import java.util.concurrent.TimeUnit

      val latch = new CountDownLatch(1)
      placeOrderRoute apply orderMessage.success respond { mv => assertOrderProcessed(mv); latch.countDown }
      if (!latch.await(10, TimeUnit.SECONDS)) throw new Exception("unexpected order processing failure")
    }

    // -----------------------------------------------------------------
    //  Application shutdown
    // -----------------------------------------------------------------

    router.stop
    executor.shutdownNow
  }
}

/**
 * @author Martin Krasser
 */
object CamelExampleAsserts extends MustMatchers {
  import org.apache.camel.component.mock.MockEndpoint

  def assertOrderProcessed(validation: => Camel.MessageValidation)(implicit cm: ContextMgnt) {
    val books = cm.context.getEndpoint("mock:books", classOf[MockEndpoint])
    val bikes = cm.context.getEndpoint("mock:bikes", classOf[MockEndpoint])

    books.reset
    bikes.reset
    
    books.expectedBodiesReceivedInAnyOrder((123, "Camel in Action", 1), (123, "DSLs in Action", 1))
    bikes.expectedBodiesReceived(PurchaseOrderItem(123, "bikes" , "Canyon Torque FRX", 1))

    validation match {
      case Success(m) => m.body must equal ("order accepted")
      case Failure(m) => throw new Exception("unexpected failure")
    }

    books.assertIsSatisfied
    bikes.assertIsSatisfied
  }
}
