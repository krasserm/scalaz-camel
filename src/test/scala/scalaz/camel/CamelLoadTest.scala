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

import java.util.concurrent.{CountDownLatch, Executors}

import org.scalatest.{WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.MustMatchers

import scalaz._
import scalaz.concurrent.Strategy._

/**
 * @author Martin Krasser
 */
abstract class CamelLoadTest extends CamelTestContext with ExecutorMgnt with WordSpec with MustMatchers with BeforeAndAfterAll {
  import Scalaz._
  import Camel._
  import CamelTestProcessors._

  override def beforeAll = router.start
  override def afterAll = {
    shutdown
    router.stop
  }

  "scalaz.camel.Camel" should {
    "be able to pass a simple load test" in {
      val combine = (m1: Message, m2: Message) => m1.appendBody(" + %s" format m2.body)
      val route = appendToBody("-1") >=> scatter(
        appendToBody("-2") >=> appendToBody("-3"),
        appendToBody("-4") >=> appendToBody("-5"),
        appendToBody("-6") >=> appendToBody("-7")
      ).gather(combine) >=> appendToBody(" done")

      val count = 1000
      val latch = new CountDownLatch(count)

      1 to count foreach { i =>
        route apply Message("a-%s" format i).success respond { mv =>
          mv must equal (Success(Message("a-%s-1-2-3 + a-%s-1-4-5 + a-%s-1-6-7 done" format (i, i, i))))
          if (i % 50 == 0) print(".")
          latch.countDown
        }
      }
      latch.await
      println
    }
  }
}

class CamelLoadTestConcurrent1 extends CamelLoadTest {
  Camel.dispatchConcurrencyStrategy = Executor(register(Executors.newFixedThreadPool(1)))
  Camel.multicastConcurrencyStrategy = Executor(register(Executors.newFixedThreadPool(1)))
  CamelTestProcessors.processorConcurrencyStrategy = Executor(register(Executors.newFixedThreadPool(1)))
}

class CamelLoadTestConcurrentN extends CamelLoadTest {
  Camel.dispatchConcurrencyStrategy = Executor(register(Executors.newFixedThreadPool(3)))
  Camel.multicastConcurrencyStrategy = Executor(register(Executors.newFixedThreadPool(3)))
  CamelTestProcessors.processorConcurrencyStrategy = Executor(register(Executors.newFixedThreadPool(3)))
}

class CamelLoadTestSequential extends CamelLoadTest