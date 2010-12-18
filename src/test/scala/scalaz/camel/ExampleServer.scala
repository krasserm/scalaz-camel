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

import org.scalatest.{WordSpec, BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.MustMatchers

import scalaz._

/**
 * @author Martin Krasser
 */
object ExampleServerContext extends ExampleSupport {
  import org.apache.camel.impl.DefaultCamelContext
  import org.apache.camel.spring.spi.ApplicationContextRegistry
  import org.springframework.context.support.ClassPathXmlApplicationContext

  val appctx = new ClassPathXmlApplicationContext("/context-jms.xml")
  val registry = new ApplicationContextRegistry(appctx)

  implicit val context = new DefaultCamelContext(registry)

  val template = context.createProducerTemplate
}

/**
 * @author Martin Krasser
 */
class ExampleServer extends ExampleSupport with WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import Scalaz._
  import Camel._

  import ExampleServerContext._

  override def beforeAll = context.start
  override def afterEach = mock.reset

  def mock = context.getEndpoint("mock:mock", classOf[org.apache.camel.component.mock.MockEndpoint])

  def support = afterWord("support")

  "scalaz-camel" should support {

    "communication of http endpoints" in {
      from("jetty:http://0.0.0.0:8865/scalaz/camel/test") route {
        appendString("-1") >=> appendString("-2")
      }

      from("direct:server-test-http") route {
        "http://localhost:8865/scalaz/camel/test" >=> appendString(" done")
      }

      template.requestBody("direct:server-test-http", "hello") must equal("hello-1-2 done")
    }

    "communication of jms endpoints" in {
      from("jms:queue:scalaz-camel-test") route {
        appendString("-1") >=> appendString("-2") >=> logMessage >=> "mock:mock"
      }

      from("direct:server-test-jms") route {
        "jms:queue:scalaz-camel-test" >=> logMessage
      }

      mock.expectedBodiesReceived("a-1-2", "b-1-2", "c-1-2")
      template.sendBody("direct:server-test-jms", "a")
      template.sendBody("direct:server-test-jms", "b")
      template.sendBody("direct:server-test-jms", "c")
      mock.assertIsSatisfied
    }

    "fast failure of Kleisli routes" in {
      from("jms:queue:scalaz-camel-test-failure") route {
        appendString("-1") >=> choose {
          case Message("a-1", _) => failWith("failure")
          case Message("b-1", _) => logMessage
        } >=> "mock:mock"
      }

      from("direct:server-test-jms-failure") route {
        "jms:queue:scalaz-camel-test-failure" >=> logMessage
      }

      mock.expectedBodiesReceived("b-1")
      template.sendBody("direct:server-test-jms-failure", "a")
      template.sendBody("direct:server-test-jms-failure", "b")
      mock.assertIsSatisfied
    }
  }
}
