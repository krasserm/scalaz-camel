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

import org.junit.Test
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitSuite
import org.scalatest.matchers.MustMatchers

import scalaz._

/**
 * @author Martin Krasser
 */
object ExampleContext extends ExampleSupport {
  import org.apache.camel.builder.RouteBuilder
  import org.apache.camel.impl.DefaultCamelContext

  implicit val context = new DefaultCamelContext

  val template = context.createProducerTemplate
  val routes = new RouteBuilder {
    def configure = {
      from("direct:extern-1").process(appendString("-extern-1"))
      from("direct:extern-2").process(appendString("-extern-2"))
      from("direct:extern-3").process(appendString("-extern-3"))
      from("direct:extern-4").process(appendString("-extern-4"))
    }
  }

  context.addRoutes(routes)
}

/**
 * @author Martin Krasser
 */
class Example extends ExampleSupport with JUnitSuite with MustMatchers with BeforeAndAfterAll {
  import Scalaz._
  import Camel._
  import ExampleContext._

  override def beforeAll = context.start

  // -------------------------------------------------
  //  Simple route construction with Kleisli composition (>=>)
  // -------------------------------------------------
  @Test def testKleisliRoutes {
    // route created from Kleisli composition of Camel processors
    from("direct:test-1") route {
      appendString("-1") >=> appendString("-2") >=> appendString("-3")
    }

    // route created from Kleisli composition of Camel processors
    // and a custom processor (function)
    from("direct:test-2") route {
      appendString("-1") >=> appendString("-2") >=> { msg: Message => msg.appendBody("-4") }
    }

    // route created from Kleisli composition of Camel processors
    // and endpoints (producers)
    from("direct:test-3") route {
      "direct:extern-1" >=> appendString("-4") >=> "direct:extern-2"
    }

    // route created from Kleisli composition of Camel processors
    // and a custom processor that throws an exception
    from("direct:test-4") route {
      appendString("-1") >=> appendString("-2") >=> failWith("failure")
    }

    // route with a single Camel processor
    from("direct:test-5") route { appendString("-1a") }

    // route with a single custom processor (function)
    from("direct:test-6") route { msg: Message => msg.appendBody("-1b") }

    // route with a single endpoint (producer)
    from("direct:test-7") route { "direct:extern-1" }


    template.requestBody("direct:test-1", "test") must equal("test-1-2-3")
    template.requestBody("direct:test-2", "test") must equal("test-1-2-4")
    template.requestBody("direct:test-3", "test") must equal("test-extern-1-4-extern-2")

    try {
      template.requestBody("direct:test-5", "test")
    } catch {
      case e => e.getCause.getMessage must equal("failure")
    }

    template.requestBody("direct:test-5", "test") must equal("test-1a")
    template.requestBody("direct:test-6", "test") must equal("test-1b")
    template.requestBody("direct:test-7", "test") must equal("test-extern-1")
  }

  // -------------------------------------------------
  //  Kleisli routes and multicast
  // -------------------------------------------------
  @Test def testKleisliRoutesWithMulticast {
    val aggregator = (m1: Message, m2: Message) => m1.appendBody(" --- %s" format m2.body)

    from("direct:test-10") route {
      appendString("-10") >=> multicast (
        appendString("-mc-1"),
        appendString("-mc-2") >=> "direct:extern-1"
      ) (aggregator) >=> appendString(" done")
    }

    from("direct:test-11") route {
      appendString("-11") >=> multicast (
        appendString("-mc-1") >=> failWith("failure"),
        appendString("-mc-2") >=> "direct:extern-1"
      ) (aggregator) >=> appendString(" done")
    }

    template.requestBody("direct:test-10", "test") must equal("test-10-mc-1 --- test-10-mc-2-extern-1 done")
    try {
      template.requestBody("direct:test-11", "test")
    } catch {
      case e => e.getCause.getMessage must equal("failure")
    }
  }

  // -------------------------------------------------
  //  Kleisli routes and content-based routing
  // -------------------------------------------------
  @Test def testKleisliRoutesWithContentBasedRouting {
    from("direct:test-20") route {
      appendString("-20") >=> choose {
        case Message("testX-20", _) => "direct:extern-1"
        case Message("testY-20", _) => "direct:extern-1" >=> "direct:extern-2"
        case Message("testZ-20", _) => "direct:extern-1" >=> failWith("failure")
        case _                      => "direct:extern-3"
      } >=> appendString(" done")
    }

    template.requestBody("direct:test-20", "testA") must equal("testA-20-extern-3 done")
    template.requestBody("direct:test-20", "testX") must equal("testX-20-extern-1 done")
    template.requestBody("direct:test-20", "testY") must equal("testY-20-extern-1-extern-2 done")
    try {
      template.requestBody("direct:test-20", "testZ")
    } catch {
      case e => e.getCause.getMessage must equal("failure")
    }
  }

  // -------------------------------------------------
  //  Kleisli routes and error handling
  // -------------------------------------------------
  @Test def testKleisliRoutesWithErrorHandling {
    from("direct:test-30") route {
      appendString("-30")
    } onError classOf[Exception] route {
      logMessage
    }

    from("direct:test-31") route {
      appendString("-31") >=> failWith("whatever")
    } onError classOf[Exception] route {
      // log the initial message and
      // mark the exception as handled (client won't see the exception)
      appendString("-31e") >=> markHandled
    }

    from("direct:test-32") route {
      appendString("-32") >=> failWith("failure")
    } onError classOf[Exception] route {
      // log initial message and
      // do not mark the exception as handled (client will see the exception)
      logMessage
    }

    from("direct:test-33") route {
      appendString("-33") >=> failWith("whatever")
    } onError classOf[Exception] route {
      // log initial message,
      // mark the exception as handled
      // but fail with another exception (client will see that exception)
      logMessage >=> markHandled >=> failWith("doh")
    }

    template.requestBody("direct:test-30", "test") must equal("test-30")
    template.requestBody("direct:test-31", "test") must equal("test-31e")

    try {
      template.requestBody("direct:test-32", "test")
    } catch {
      case e => e.getCause.getMessage must equal("failure")
    }
    try {
      template.requestBody("direct:test-33", "test")
    } catch {
      case e => e.getCause.getMessage must equal("doh")
    }
  }


  // -------------------------------------------------
  //  Simple routes with single MessageProcessor
  // -------------------------------------------------
  @Test def testMessageProcessor {
    from("direct:test-40") process { m => m.success }
    from("direct:test-41") process { m => m.appendBody("-41").success }
    from("direct:test-42") process { m => Failure(new Exception("left")) }

    template.requestBody("direct:test-40", "test") must equal("test")
    template.requestBody("direct:test-41", "test") must equal("test-41")
    try {
      template.requestBody("direct:test-42", "test")
    } catch {
      case e => e.getCause.getMessage must equal("left")
    }
  }

  // -------------------------------------------------
  //  MessageProcessor and for-comprehensions
  // -------------------------------------------------
  @Test def testMessageProcessorWithForComprehension {
    // alternative to multicast EIP using a for-comprehension
    // inside a MessageProcessor
    from("direct:test-50") process { m =>
      for {
        m1 <- appendString("-x") apply m
        m2 <- appendString("-y") >=> appendString("-z") apply m
      } yield m1.appendBody(" --- %s" format m2.body)
    }

    template.requestBody("direct:test-50", "test") must equal("test-x --- test-y-z")
  }

  // -------------------------------------------------
  //  Kleisli routes and for-comprehensions
  // -------------------------------------------------
  @Test def testKleisliRoutesWithForComprehension {
    val customMulticast = kleisliProcessor { m: Message => for {
        m1 <- appendString("-x") apply m
        m2 <- appendString("-y") >=> appendString("-z") apply m
      } yield m1.appendBody(" --- %s" format m2.body)
    }

    // alternative to multicast EIP using a for-comprehension
    // as part of a Kleisli composition
    from("direct:test-51") route {
       customMulticast >=> appendString("-end")
    }

    // custom multicast where one branch fails
    // (yield-expression won't be evaluated)
    from("direct:test-52") process { m =>
      for {
        m1 <- appendString("-x") >=> failWith("failure") apply m
        m2 <- appendString("-y") >=> appendString("-z") apply m
      } yield m1.appendBody(" --- %s" format m2.body)
    }

    template.requestBody("direct:test-51", "test") must equal("test-x --- test-y-z-end")
    try {
      template.requestBody("direct:test-52", "test")
    } catch {
      case e => e.getCause.getMessage must equal("failure")
    }
  }

  // -------------------------------------------------
  //  Kleisli routes and other control structures
  // -------------------------------------------------
  @Test def testKleisliRouteswithOtherControlStructures {
    val s = "foo"

    from("direct:test-60") route {
      "direct:extern-1" >=> {
        if (s == "foo") appendString("-1")
        else appendString("-2")
      } >=> "direct:extern-2"
    }

    template.requestBody("direct:test-60", "test") must equal("test-extern-1-1-extern-2")
  }

  // -------------------------------------------------
  //  Using routes with applicative functors
  // -------------------------------------------------
  @Test def testKleisliRoutesWithApplicateFunctors {
    import org.apache.camel.CamelContext
    import org.apache.camel.impl.DefaultExchange

    List("a", "b") ∘ (createMessage _) <*> List[MessageProcessor](
      appendString("-1") >=> "direct:extern-1",
      appendString("-2") >=> "direct:extern-2") must
      equal(List(
        Success(Message("a-1-extern-1")),
        Success(Message("b-1-extern-1")),
        Success(Message("a-2-extern-2")),
        Success(Message("b-2-extern-2"))
      ))

    // Alternative: single route using map (i.e ∘)
    List("a", "b") ∘ (createMessage _) ∘ (appendString("-0") >=> "direct:extern-1") must
      equal(List(
        Success(Message("a-0-extern-1")),
        Success(Message("b-0-extern-1"))
      ))

    def createMessage(body: Any)(implicit context: CamelContext) =
      Message(body).setExchange(new DefaultExchange(context))
  }
}
