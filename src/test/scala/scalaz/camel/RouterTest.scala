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

import org.apache.camel.{Consumer, Producer}
import org.apache.camel.impl.{ServiceSupport, DefaultCamelContext}

import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, WordSpec}

/**
 * @author Martin Krasser
 */
class RouterTest extends WordSpec with MustMatchers {

  val router = new Router(new DefaultCamelContext)

  implicit def toServiceSupport(consumer: Consumer): ServiceSupport = consumer.asInstanceOf[ServiceSupport]
  implicit def toServiceSupport(producer: Producer): ServiceSupport = producer.asInstanceOf[ServiceSupport]

  var c1, c2: Consumer = _
  var p1, p2: Producer = _

  "A router" when {
    "not started" must {
      "create not-started consumers and producers" in {
        c1 = router.createConsumer("direct:test-1", null)
        p1 = router.createProducer("direct:test-1")
        c1 must not be ('started)
        p1 must not be ('started)
      }
    }
    "started" must {
      "start previously created consumers and producers" in {
        router.start
        c1 must be ('started)
        p1 must be ('started)
      }
      "create started consumers and producers" in {
        c2 = router.createConsumer("direct:test-2", null)
        p2 = router.createProducer("direct:test-2")
        c2 must be ('started)
        p2 must be ('started)
      }
    }
    "stopped" must {
      "stop all previously created consumers and producers" in {
        router.stop
        c1 must not be ('started)
        p1 must not be ('started)
        c2 must not be ('started)
        p2 must not be ('started)
      }
    }
  }
}
