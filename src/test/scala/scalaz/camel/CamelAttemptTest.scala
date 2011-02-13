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
trait CamelAttemptTest extends CamelTestContext with WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import Scalaz._
  import Camel._
  import CamelTestProcessors.{failWith => failWithErrorMessage, _}

  override def beforeAll = router.start
  override def afterAll = router.stop
  override def afterEach = mocks.values.foreach { m => m.reset }

  def support = afterWord("support")

  "scalaz.camel.Camel" should support {
    "single routing attempts with multiple error handling routes" in {
      val p: Message => Message = (m: Message) => {
        m.body match {
          case "a-0" => throw new TestException1("failure1")
          case "b-0" => throw new TestException2("failure2")
          case _   => throw new Exception("failure")
        }
      }

      from("direct:test-2") {
        attempt {
          appendToBody("-0") >=> p
        } fallback {
          case e: TestException1 => appendToBody("-1")
          case e: TestException2 => appendToBody("-2")
          case e: Exception      => appendToBody("-3")
        }
      }

      template.requestBody("direct:test-2", "a") must equal ("a-0-1")
      template.requestBody("direct:test-2", "b") must equal ("b-0-2")
      template.requestBody("direct:test-2", "c") must equal ("c-0-3")
    }

    "single routing attempts including reporting of causing exception" in {
      from("direct:test-3") {
        attempt {
          failWith(new TestException1("failed"))
        } fallback {
          case e: Exception => to("mock:mock") >=> failWith(e) /* causes producer template to throw exception */
        }
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

    "single routing attempts with failures in error handlers" in {
      from("direct:test-4a") {
        attempt {
          failWithErrorMessage("failed")
        } fallback {
          case e: Exception => failWithErrorMessage("x")
        }
      }

      from("direct:test-4b") {
        attempt {
          failWithErrorMessage("failed")
        } fallback {
          case e: Exception => failWithErrorMessage("x")
        }
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

    "multiple routing attempts with the original message" in {
      val route = appendToBody("-1") >=> attempt(3) {
          appendToBody("-2") >=> to("mock:mock") >=> failWith(new TestException1("error"))
        }.fallback {
          case (e, s) => orig(s) >=> retry(s)
        } >=> appendToBody("-3")

      mock("mock").expectedBodiesReceived("a-1-2", "a-1-2", "a-1-2")
      route responseFor Message("a") match {
        case Failure(m) => m.body must equal("a-1-2")
        case _          => fail("failure response expected")
      }
      mock("mock").assertIsSatisfied
    }

    "multiple routing attempts with a modified message" in {
      val route = appendToBody("-1") >=> attempt(2) {
          appendToBody("-2") >=> to("mock:mock") >=> failWith(new TestException1("error"))
        }.fallback {
          case (e, s) => orig(s) >=> appendToBody("-m") >=> retry(s)
        } >=> appendToBody("-3")

      mock("mock").expectedBodiesReceivedInAnyOrder("a-1-2", "a-1-m-2")
      route responseFor Message("a") match {
        case Failure(m) => m.body must equal("a-1-m-2")
        case _          => fail("failure response expected")
      }
      mock("mock").assertIsSatisfied
    }

    "multiple routing attempts with the latest message" in {
      val route = appendToBody("-1") >=> attempt(3) {
          appendToBody("-2") >=> to("mock:mock") >=> failWith(new TestException1("error"))
        }.fallback {
          case (e, s) => appendToBody("-m") >=> retry(s)
        } >=> appendToBody("-3")

      mock("mock").expectedBodiesReceivedInAnyOrder("a-1-2", "a-1-2-m-2", "a-1-2-m-2-m-2")
      route responseFor Message("a") match {
        case Failure(m) => m.body must equal("a-1-2-m-2-m-2")
        case _          => fail("failure response expected")
      }
      mock("mock").assertIsSatisfied
    }

    "multiple routing attempts that succeed after retry" in {
      val conditionalFailure: Message => Message = (m: Message) => {
        if (m.body == "a-1-2") throw new TestException1("error") else m
      }

      val route = appendToBody("-1") >=> attempt(3) {
          appendToBody("-2") >=> to("mock:mock") >=> printMessage >=> conditionalFailure
        }.fallback {
          case (e, s) => retry(s)
        } >=> appendToBody("-3")

      mock("mock").expectedBodiesReceivedInAnyOrder("a-1-2", "a-1-2-2")
      route responseFor Message("a") match {
        case Success(m) => m.body must equal("a-1-2-2-3")
        case _          => fail("success response expected")
      }
      mock("mock").assertIsSatisfied
    }
  }

  class TestException1(msg: String) extends Exception(msg)
  class TestException2(msg: String) extends Exception(msg)
}

class CamelAttemptTestSequential extends CamelAttemptTest
class CamelAttemptTestConcurrent extends CamelAttemptTest with ExecutorMgnt {
  import java.util.concurrent.Executors

  Camel.dispatchConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))
  Camel.multicastConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))
  CamelTestProcessors.processorConcurrencyStrategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))

  override def afterAll = {
    shutdown
    super.afterAll
  }
}
