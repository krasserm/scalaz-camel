package scalaz.camel.async

import org.scalatest.{WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.MustMatchers

import scalaz._

/**
 * @author Martin Krasser
 */
object ExampleContext {
  import org.apache.camel.impl.DefaultCamelContext
  import scalaz.camel.Router

  val context = new DefaultCamelContext
  val template = context.createProducerTemplate

  implicit val router = new Router(context)
}

/**
 * @author Martin Krasser
 */
class Example extends ExampleSupport with WordSpec with MustMatchers with BeforeAndAfterAll {
  import scalaz.concurrent.Strategy.Naive
  import Scalaz._
  import Camel._
  import ExampleContext._

  override def beforeAll = router.start
  override def afterAll = router.stop

  def support = afterWord("support")

  "scalaz-camel" should support {
    "non-blocking routing with asynchronous functions" in {
      from("direct:test-1") route {
        appendStringAsync("-1") >=> appendStringAsync("-2")
      }
      template.requestBody("direct:test-1", "test") must equal("test-1-2")
    }

    "non-blocking routing with asynchronous Camel processors" in {
      from("direct:test-2") route {
        repeatBodyAsync >=> repeatBodyAsync
      }
      template.requestBody("direct:test-2", "x") must equal("xxxx")
    }
  }
}
