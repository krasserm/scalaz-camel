package scalaz.camel.async

import org.apache.camel.AsyncProcessor

import org.scalatest.{WordSpec, BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.matchers.MustMatchers

import scalaz._
import scalaz.camel.Message

/**
 * @author Martin Krasser
 */
object ExampleServerContext {
  import org.apache.camel.spring.SpringCamelContext._
  import scalaz.camel.Router

  val context = springCamelContext("/context.xml")
  val template = context.createProducerTemplate

  implicit val router = new Router(context)
}

/**
 * @author Martin Krasser
 */
class ExampleServer extends ExampleSupport with WordSpec with MustMatchers with BeforeAndAfterAll {
  import scalaz.concurrent.Strategy.Naive
  import Scalaz._
  import Camel._

  import ExampleServerContext._

  override def beforeAll = router.start
  override def afterAll = router.stop

  def support = afterWord("support")

  "scalaz-camel" should support {
    "non-blocking routing with asynchronous endpoints" in {

      // non-blocking server route using
      // - Jetty-continuation enabled endpoint and
      // - asynchronous message processors
      from("jetty:http://localhost:8766/test") route {
        convertToStringAsync >=> repeatBodyAsync
      }

      // non-blocking client route using
      // - asynchronous Jetty HTTP client and
      // - asynchronous message processor
      from("direct:test-1") route {
        "jetty:http://localhost:8766/test" >=> appendStringAsync("-done")
      }

      template.requestBody("direct:test-1", "test") must equal("testtest-done")
    }
  }
}