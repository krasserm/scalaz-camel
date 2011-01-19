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
  override def afterEach = mocks.values.foreach { m => m.reset }

  def support = afterWord("support")

  "scalaz.camel.Camel" should support {

    "Kleisli composition of CPS message processors" in {
      appendToBody("-1") >=> appendToBody("-2") responseFor Message("a") must equal(Success(Message("a-1-2")))
    }

    "Kleisli composition of direct-style message processors" in {
      ds_appendToBody("-1") >=> ds_appendToBody("-2") responseFor Message("a") must equal(Success(Message("a-1-2")))
    }

    "Kleisli composition of asynchonous Camel message processors" in {
      repeatBody >=> repeatBody responseFor Message("a") must equal(Success(Message("aaaa")))
    }

    "Kleisli composition of synchonous Camel message processors" in {
      repeatBody.sp >=> repeatBody.sp responseFor Message("a") must equal(Success(Message("aaaa")))
    }

    "Kleisli composition of Camel endpoint producers" in {
      to("direct:predef-1") >=> to("direct:predef-2") responseFor Message("a") must equal(Success(Message("a-p1-p2")))
    }

    "Kleisli composition of different types of message processors" in {
      repeatBody >=> repeatBody.sp >=> appendToBody("-1") >=> ds_appendToBody("-2") >=> to("direct:predef-1") responseFor
         Message("a") must equal(Success(Message("aaaa-1-2-p1")))
    }

    "Kleisli composition of CPS processors defined inline" in {
      val route = appendToBody("-1") >=> { (m: Message, k: MessageValidation => Unit) => k(m.appendToBody("-x").success) }
      route responseFor Message("a") must equal(Success(Message("a-1-x")))
    }

    "Kleisli composition of direct-style processors defined inline" in {
      val route = appendToBody("-1") >=> { m: Message => m.appendToBody("-y") }
      route responseFor Message("a") must equal(Success(Message("a-1-y")))
    }

    "failure reporting with CPS processors" in {
      failWith("1") >=> failWith("2") responseFor Message("a") match {
        case Success(_)          => fail("Failure result expected")
        case Failure(m: Message) => m.exception match {
          case Some(e: Exception) => e.getMessage must equal("1")
          case None               => fail("no exception set for message")
        }
      }
    }

    "failure reporting with direct-style processors (that throw exceptions)" in {
      ds_failWith("1") >=> ds_failWith("2") responseFor Message("a") match {
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

    "application of Kleisli routes using response queues" in {
      val queue = appendToBody("-1") >=> appendToBody("-2") responseQueueFor Message("a")

      queue.take match {
        case Success(m) => m.body must equal("a-1-2")
        case Failure(m) => fail("unexpected failure")
      }
    }

    "application of Kleisli routes using continuation-passing style (CPS)" in {
      val queue = new java.util.concurrent.LinkedBlockingQueue[MessageValidation](10)
      appendToBody("-1") >=> appendToBody("-2") apply Message("a").success respond { mv => queue.put(mv) }
      queue.take match {
        case Success(m) => m.body must equal("a-1-2")
        case Failure(m) => fail("unexpected failure")
      }
    }

    "message comsumption from endpoints" in {
      from("direct:test-1") route { appendToBody("-1") >=> appendToBody("-2") }
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
      } handle {
        case e: TestException1 => appendToBody("-1")
        case e: TestException2 => appendToBody("-2")
        case e: Exception      => appendToBody("-3")
      }

      template.requestBody("direct:test-2", "a") must equal ("a-0-1")
      template.requestBody("direct:test-2", "b") must equal ("b-0-2")
      template.requestBody("direct:test-2", "c") must equal ("c-0-3")
    }

    "error handling including reporting of causing exception" in {
      from("direct:test-3") route {
        failWith(new TestException1("failed"))
      } handle {
        case e: Exception => to("mock:mock") >=> markFailed(e) /* causes producer template to throw exception */
      }

      mock("mock").expectedBodiesReceived("a")

      try {
        template.requestBody("direct:test-3", "a")
        fail("exception expected")
      } catch {
        case e: Exception => {
          val cause = e.getCause
          cause.isInstanceOf[TestException1] must be (true)
          cause.getMessage must equal ("failed")
        }
      }

      mock("mock").assertIsSatisfied
    }

    "error handling with failures in error handlers" in {
      from("direct:test-4a") route {
        failWith("failed")
      } handle {
        case e: Exception => failWith("x")
      }

      from("direct:test-4b") route {
        failWith("failed")
      } handle {
        case e: Exception => failWith("x")
      }

      try {
        template.requestBody("direct:test-4a", "a")
        fail("exception expected")
      } catch {
        case e: Exception => e.getCause.getMessage must equal ("x")
      }

      try {
        template.requestBody("direct:test-4b", "a")
        fail("exception expected")
      } catch {
        case e: Exception => e.getCause.getMessage must equal ("x")
      }
    }

    "content-based routing" in {
      from("direct:test-10") route {
        appendToBody("-1") >=> choose {
          case Message("a-1", _) => appendToBody("-2") >=> appendToBody("-3")
          case Message("b-1", _) => appendToBody("-4") >=> appendToBody("-5")
        } >=> appendToBody("-done")
      }
      template.requestBody("direct:test-10", "a") must equal ("a-1-2-3-done")
      template.requestBody("direct:test-10", "b") must equal ("b-1-4-5-done")
      template.requestBody("direct:test-10", "c") must equal ("c-1-done")
    }

    "scatter-gather" in {
      val combine = (m1: Message, m2: Message) => m1.appendToBody(" + %s" format m2.body)

      from("direct:test-11") route {
        appendToBody("-1") >=> scatter(
          appendToBody("-2") >=> appendToBody("-3"),
          appendToBody("-4") >=> appendToBody("-5"),
          appendToBody("-6") >=> appendToBody("-7")
        ).gather(combine) >=> appendToBody(" done")
      }

      template.requestBody("direct:test-11", "a") must equal ("a-1-2-3 + a-1-4-5 + a-1-6-7 done")
    }

    "scatter-gather that fails if one of the recipients fail" in {
      val combine = (m1: Message, m2: Message) => m1.appendToBody(" + %s" format m2.body)

      from("direct:test-12") route {
        appendToBody("-1") >=> scatter(
          appendToBody("-2") >=> failWith("x"),
          appendToBody("-4") >=> failWith("y")
        ).gather(combine) >=> appendToBody(" done")
      }

      try {
        template.requestBody("direct:test-12", "a")
        fail("exception expected")
      } catch {
        case e: Exception => {
          // test passed but reported exception message can be either 'x'
          // or 'y' if message is distributed to destination concurrently.
          // For sequential multicast (or a when using a single-threaded
          // executor for multicast) then exception message 'x' will always
          // be reported first.
          if (Camel.multicastConcurrencyStrategy == Strategy.Sequential)
            e.getCause.getMessage must equal ("x")
        }
      }
    }

    "usage of Kleisli routes inside message processors" in {
      // CPS message processor doing CPS application of Kleisli route
      val composite1: MessageProcessor = (m: Message, k: MessageValidation => Unit) =>
        appendToBody("-n1") >=> appendToBody("-n2") apply m.success respond k

      // direct-style message processor (blocks contained until route generated response)
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

    "custom scatter-gather using for-comprehensions and promises" in {
      // needed for creation of response promise (can be any
      // other strategy as well such as Sequential or ...)
      implicit val strategy = Strategy.Naive

      // input message to destination routes
      val input = Message("test")

      // custom scatter-gather
      val promise = for {
        a <- appendToBody("-1") >=> appendToBody("-2") responsePromiseFor input
        b <- appendToBody("-3") >=> appendToBody("-4") responsePromiseFor input
      } yield a |@| b apply { (m1: Message, m2: Message) => m1.appendToBody(" + %s" format m2.body) }

      promise.get must equal(Success(Message("test-1-2 + test-3-4")))
    }

    "multicast" in {
      from("direct:test-30") route {
        appendToBody("-1") >=> multicast(
          appendToBody("-2") >=> to("mock:mock1"),
          appendToBody("-3") >=> to("mock:mock1")
        ) >=> appendToBody("-done") >=> to("mock:mock2")
      }

      mock("mock1").expectedBodiesReceivedInAnyOrder("a-1-2"     , "a-1-3")
      mock("mock2").expectedBodiesReceivedInAnyOrder("a-1-2-done", "a-1-3-done")

      template.sendBody("direct:test-30", "a")

      mock("mock1").assertIsSatisfied
      mock("mock2").assertIsSatisfied
    }

    "multicast with a failing destination" in {
      from("direct:test-31") route {
        appendToBody("-1") >=> multicast(
          appendToBody("-2"),
          appendToBody("-3") >=> failWith("-fail")
        ) >=> appendToBody("-done") >=> to("mock:mock")
      } handle {
        case e: Exception => appendToBody(e.getMessage) >=> to("mock:error")
      }

      mock("mock").expectedBodiesReceived("a-1-2-done")
      mock("error").expectedBodiesReceived("a-1-3-fail")

      template.sendBody("direct:test-31", "a")

      mock("mock").assertIsSatisfied
      mock("error").assertIsSatisfied
    }

    "splitting of messages" in {
      val splitLogic = (m: Message) => for (i <- 1 to 3) yield { m.appendToBody("-%s" format i) }

      from("direct:test-35") route { split(splitLogic) >=> appendToBody("-done") >=> to("mock:mock") }

      mock("mock").expectedBodiesReceivedInAnyOrder("a-1-done", "a-2-done", "a-3-done")

      template.sendBody("direct:test-35", "a")

      mock("mock").assertIsSatisfied
    }

    "aggregation of messages" in {

      // Waits for three messages with a 'keep' header.
      // At arrival of the third message, a new Message
      // with body 'aggregated' is returned.
      def waitFor(count: Int) = {
        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        (m: Message) => {
          m.header("keep") match {
            case None    => Some(m)
            case Some(_) => if (counter.incrementAndGet == count) Some(Message("aggregated")) else None
          }
        }
      }

      from("direct:test-40") route {
        aggregate(waitFor(3)) >=> to("mock:mock")
      }

      mock("mock").expectedBodiesReceivedInAnyOrder("aggregated", "not aggregated")

      // only third message will make the aggregator to send a response
      for (i <- 1 to 5) template.sendBodyAndHeader("direct:test-40", "a", "keep", true)

      // ignored by aggregator and forwarded as-is
      template.sendBody("direct:test-40", "not aggregated")
  
      mock("mock").assertIsSatisfied
    }

    "filtering of messages" in {
      from("direct:test-45") route {
        filter(_.body == "ok") >=> to("mock:mock")
      }

      mock("mock").expectedBodiesReceived("ok")

      template.sendBody("direct:test-45", "filtered")
      template.sendBody("direct:test-45", "ok")

      mock("mock").assertIsSatisfied
    }

    "sharing of routes" in {

      // nothing specific to scalaz-camel
      // just demonstrates function reuse

      val r = appendToBody("-1") >=> appendToBody("-2")

      from ("direct:test-50a") route r
      from ("direct:test-50b") route r

      template.requestBody("direct:test-50a", "a") must equal ("a-1-2")
      template.requestBody("direct:test-50b", "b") must equal ("b-1-2")
    }

    "preserving the message exchange even if a processor drops it" in {
      // Function that returns *new* message that doesn't contain the exchange of
      // the input message (it contains a new default exchange). The exchange of
      // the input message will be set on the result message by the MessageValidationResponder
      val badguy1 = (m: Message) => new Message("bad")

      
      // Function that returns a *new* message on which setException is called as well.
      // Returning a new message *and* calling either setException or setOneway required
      // explicit setting on the exchange from the input message as well.
      val badguy2 = (m: Message) => new Message("bad").setExchangeFrom(m).setException(new TestException1("x"))

      val route1 = appendToBody("-1") >=> badguy1 >=> appendToBody("-2")
      val route2 = appendToBody("-1") >=> badguy2 >=> appendToBody("-2")

      route1 responseFor Message("a").setOneway(true) match {
        case Failure(m) => fail("unexpected failure")
        case Success(m) => {
          m.exchange.oneway must be (true)
          m.body must equal ("bad-2")
        }
      }

      route2 responseFor Message("a").setOneway(true) match {
        case Failure(m) => fail("unexpected failure")
        case Success(m) => {
          m.exchange.oneway must be (true)
          m.body must equal ("bad-2")
        }
      }
    }

    "proper correlation of (concurrent) request and response messages" in {
      import CamelTestProcessors.{processorConcurrencyStrategy => s}

      // Note that there is no explicit correlation of request and response messages using correlation
      // identifiers, for example. Every application of a Kleisli route to a message maintains that
      // message (and derived responses) in context of a responder monad (the a computational context
      // for a single message (flow) on the message-processing route). This ensures proper isolation of
      // individual message exchanges.

      def conditionalDelay(delay: Long, body: String): MessageProcessor = (m: Message, k: MessageValidation => Unit) => {
        if (m.body == body)
          s.apply { Thread.sleep(delay); k(m.success) }
        else
          s.apply { k(m.success) }
      }

      val r = conditionalDelay(1000, "a") >=> conditionalDelay(1000, "x") >=> appendToBody("-done")

      from("direct:test-55") route r

      val a = Strategy.Naive.apply { template.requestBody("direct:test-55", "a") }
      val b = Strategy.Naive.apply { template.requestBody("direct:test-55", "b") }
      val x = Strategy.Naive.apply { r responseFor Message("x") }
      val y = Strategy.Naive.apply { r responseFor Message("y") }

      y() must equal (Success(Message("y-done")))
      x() must equal (Success(Message("x-done")))

      b() must equal ("b-done")
      a() must equal ("a-done")
    }
  }
  
  class TestException1(msg: String) extends Exception(msg)
  class TestException2(msg: String) extends Exception(msg)
}

class CamelTestSequential extends CamelTest
class CamelTestConcurrent extends CamelTest with ExecutorMgnt {
  import java.util.concurrent.Executors

  Camel.dispatchConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))
  Camel.multicastConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))
  CamelTestProcessors.processorConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))

  override def afterAll = {
    shutdown
    super.afterAll
  }
}
