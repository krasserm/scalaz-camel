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
    router.context.addRoutes(new org.apache.camel.builder.RouteBuilder() {
      def configure = {
        from("direct:predef").process(new RepeatBodyProcessor(strategy))
      }
    })
    router.start
  }

  override def afterAll = router.stop
  override def afterEach = mocks.values.foreach { m => m.reset }

  def support = afterWord("support")

  "scalaz.camel.Camel" should support {
    "basic routing" in {
      val promise = appendToBody("-1") >=> to("direct:predef") >=> appendToBody("-2") apply Message("x") get match {
        case Right(msg) => msg.body must equal ("x-1x-1-2")
        case _          => fail("unexpected result")
      }
    }
  }
}

class CamelTestSequential extends CamelTest
class CamelTestConcurrent extends CamelTest with ExecutorMgnt {
  import java.util.concurrent.Executors

  Camel.strategy = Strategy.Executor(register(Executors.newFixedThreadPool(3)))

  override def afterAll = {
    shutdown
    super.afterAll
  }
}