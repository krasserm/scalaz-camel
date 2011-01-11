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

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.{Exchange, Predicate}

import org.scalatest.{WordSpec, BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.MustMatchers

import scalaz._
import scalaz.concurrent.Strategy

/**
 * @author Martin Krasser
 */
trait CamelTest extends CamelTestContext with WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import Scalaz._
  import Camel._
  import CamelTestProcessors._

  override def beforeAll = {
    from("direct:predef-1") route { appendToBody("-p1") }
    from("direct:predef-2") route { appendToBody("-p2") }
    router.start
  }

  override def afterAll = router.stop
  override def afterEach = mock.reset

  def support = afterWord("support")

  "scalaz.camel.Camel" should support {

    "Kleisli composition of asynchonous message processor functions" in {
      appendToBody("-1") >=> appendToBody("-2") responseFor Message("a") must equal(Success(Message("a-1-2")))
    }

    "Kleisli composition of synchonous message processor functions" in {
      appendToBodySync("-1") >=> appendToBodySync("-2") responseFor Message("a") must equal(Success(Message("a-1-2")))
    }

    "Kleisli composition of asynchonous Camel message processors" in {
      repeatBody >=> repeatBody responseFor Message("a") must equal(Success(Message("aaaa")))
    }

    "Kleisli composition of synchonous Camel message processors" in {
      repeatBody.sp >=> repeatBody.sp responseFor Message("a") must equal(Success(Message("aaaa")))
    }

    "Kleisli composition of (a)synchronous Camel endpoint processors" in {
      to("direct:predef-1") >=> to("direct:predef-2") responseFor Message("a") must equal(Success(Message("a-p1-p2")))
    }

    "Kleisli composition of different types of message processors" in {
       repeatBody >=> repeatBody.sp >=> appendToBody("-1") >=> appendToBodySync("-2") >=> to("direct:predef-1") responseFor
         Message("a") must equal(Success(Message("aaaa-1-2-p1")))
    }

    //
    // TODO: inline processors (sync and async)
    //

    "failure reporting for asynchronous processors" in {
      failWith("1") >=> failWith("2") responseFor Message("a") match {
        case Success(_)          => fail("Failure result expected")
        case Failure(m: Message) => m.exception match {
          case Some(e: Exception) => e.getMessage must equal("1")
          case None               => fail("no exception set for message")
        }
      }
    }

    "failure reporting for synchronous processors (that throw exceptions)" in {
      failWithSync("1") >=> failWithSync("2") responseFor Message("a") match {
        case Success(_)          => fail("Failure result expected")
        case Failure(m: Message) => m.exception match {
          case Some(e: Exception) => e.getMessage must equal("1")
          case None               => fail("no exception set for message")
        }
      }
    }

    "application of Kleisli routes using promises" in {
      // With the 'Sequential' strategy, routing will be started in the current
      // thread but processing may continue in another thread depending on the
      // concurrency strategy used for dispatcher and processors.
      implicit val strategy = Strategy.Sequential

      val promise = appendToBody("-1") >=> appendToBody("-2") responsePromiseFor Message("a")

      promise.get match {
        case Success(m) => m.body must equal("a-1-2")
        case Failure(m) => fail("unexpected failure")
      }
    }

    "application of Kleisli routes using continuation-passing style (CPS)" in {
      val queue = new java.util.concurrent.ArrayBlockingQueue[MessageValidation](10)
      appendToBody("-1") >=> appendToBody("-2") apply Message("a").success respond { mv => queue.put(mv) }
      queue.take match {
        case Success(m) => m.body must equal("a-1-2")
        case Failure(m) => fail("unexpected failure")
      }
    }

    "message comsumption from endpoints" in {
      from("direct:test-1") route {
        appendToBody("-1") >=> appendToBody("-2")
      }
      template.requestBody("direct:test-1", "test") must equal ("test-1-2")
    }

    "error handling with multiple error handling routes" in {
      val p: Message => Message = (m: Message) => {
        m.body match {
          case "a-0" => throw new TestException1("failure1")
          case "b-0" => throw new TestException2("failure2")
          case _   => throw new Exception("failure")
        }
      }

      from("direct:test-2") route {
        appendToBody("-0") >=> p
      } onError classOf[TestException1] route {
        appendToBody("-1") >=> markHandled
      } onError classOf[TestException2] route {
        appendToBody("-2") >=> markHandled
      } onError classOf[Exception] route {
        appendToBody("-3") >=> markHandled
      }

      template.requestBody("direct:test-2", "a") must equal ("a-0-1")
      template.requestBody("direct:test-2", "b") must equal ("b-0-2")
      template.requestBody("direct:test-2", "c") must equal ("c-0-3")
    }

    "error handling including reporting of causing exception" in {
      from("direct:test-3") route {
        failWith("failed")
      } onError classOf[Exception] route {
        to("mock:mock")
      }

      mock.expectedBodiesReceived("a")

      try {
        template.requestBody("direct:test-3", "a"); fail("exception expected")
      } catch {
        case e: Exception => e.getCause.getMessage must equal ("failed")
      }

      mock.assertIsSatisfied
    }

    "error handling with failures in error handlers" in {
      from("direct:test-4a") route {
        failWith("failed")
      } onError classOf[Exception] route {
        failWith("x")
      }

      from("direct:test-4b") route {
        failWith("failed")
      } onError classOf[Exception] route {
        markHandled >=> failWith("x")
      }

      try {
        template.requestBody("direct:test-4a", "a"); fail("exception expected")
      } catch {
        case e: Exception => e.getCause.getMessage must equal ("x")
      }

      try {
        template.requestBody("direct:test-4b", "a"); fail("exception expected")
      } catch {
        case e: Exception => e.getCause.getMessage must equal ("x")
      }
    }

    "content-based routing using the 'choose' combinator" in {
      from("direct:test-10") route {
        appendToBody("-1") >=> choose {
          case Message("a-1", _) => appendToBody("-2") >=> appendToBody("-3")
          case Message("b-1", _) => appendToBody("-4") >=> appendToBody("-5")
        } >=> appendToBody("-done")
      }
      template.requestBody("direct:test-10", "a") must equal ("a-1-2-3-done")
      template.requestBody("direct:test-10", "b") must equal ("b-1-4-5-done")
    }

    "(concurrent) recipient-lists using the 'multicast' combinator" in {
      val combine = (m1: Message, m2: Message) => m1.appendBody(" + %s" format m2.body)

      from("direct:test-11") route {
        appendToBody("-1") >=> multicast(
          appendToBody("-2") >=> appendToBody("-3"),
          appendToBody("-4") >=> appendToBody("-5"),
          appendToBody("-6") >=> appendToBody("-7")
        )(combine) >=> appendToBody(" done")
      }

      template.requestBody("direct:test-11", "a") must equal ("a-1-2-3 + a-1-4-5 + a-1-6-7 done")
    }

    "(concurrent) recipient-lists that fail if one of the recipients fail" in {
      val combine = (m1: Message, m2: Message) => m1.appendBody(" + %s" format m2.body)

      from("direct:test-12") route {
        appendToBody("-1") >=> multicast(
          appendToBody("-2") >=> failWith("x"),
          appendToBody("-4") >=> failWith("y")
        )(combine) >=> appendToBody(" done")
      }
      try {
        template.requestBody("direct:test-12", "a"); fail("exception expected")
      } catch {
        case e: Exception => {
          // test passed but reported exception message can be either 'x'
          // or 'y' if message is distributed to destination concurrently.
          // For sequential multicast (or a when using a single-threaded
          // executor for multicast) then exception message 'x' will always
          // be reported first.
          if (Camel.scatterConcurrencyStrategy == Strategy.Sequential)
            e.getCause.getMessage must equal ("x")
        }
      }
    }

    "usage of Kleisli routes inside message processors" in {
      // asynchronous message processor (implementation uses continuation-passing style)
      val composite1: MessageProcessor = (m: Message, k: MessageValidation => Unit) =>
        appendToBody("-n1") >=> appendToBody("-n2") apply m.success respond k

      // synchronous message processor (synchronously executes contained route)
      val composite2: Message => Message = (m: Message) =>
        appendToBody("-n3") >=> appendToBody("-n4") responseFor m match {
          case Success(m) => m
          case Failure(m) => throw m.exception.get
        }

      from("direct:test-20") route {
         composite1 >=> composite2
      }

      template.requestBody("direct:test-20", "test") must equal("test-n1-n2-n3-n4")
    }

    "custom multicast using for-comprehensions and promises" in {
      // needed for creation of response promise (can be any
      // other strategy as well such as Sequential or ...)
      implicit val strategy = Strategy.Naive

      // input message to destination routes
      val input = Message("test")

      // custom multicast
      val promise = for {
        a <- appendToBody("-1") >=> appendToBody("-2") responsePromiseFor input
        b <- appendToBody("-3") >=> appendToBody("-4") responsePromiseFor input
      } yield a |@| b apply { (m1: Message, m2: Message) => m1.appendBody(" + %s" format m2.body) }

      promise.get must equal(Success(Message("test-1-2 + test-3-4")))
    }

    "preserving the message exchange even if a processor drops it" in {
      val badguy = (m: Message) => new Message("bad") // new message that doesn't contain exchange of m
      val route = appendToBody("-1") >=> badguy >=> appendToBody("-2")

      val response = route responseFor Message("a").setOneway(true) match {
        case Failure(m) => fail("unexpected failure")
        case Success(m) => {
          m.exchange.oneway must be (true)
          m.body must equal ("bad-2")
        }
      }
    }
  }

  class TestException1(msg: String) extends Exception(msg)
  class TestException2(msg: String) extends Exception(msg)
}

class CamelTestSequential extends CamelTest
class CamelTestConcurrent extends CamelTest with ExecutorMgnt {
  import java.util.concurrent.Executors

  Camel.dispatchConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))
  Camel.scatterConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))
  CamelTestProcessors.processorConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))

  override def afterAll = {
    shutdown
    super.afterAll
  }
}
