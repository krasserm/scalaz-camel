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
