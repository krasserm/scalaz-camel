package scalaz.camel

import org.scalatest.{WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.MustMatchers

import scalaz._

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

  "scalaz.camel.async.Camel" should support {
    "non-blocking routing with asynchronous endpoints" in {

      // non-blocking server route using
      // - Jetty-continuation enabled endpoint and
      // - asynchronous message processors
      from("jetty:http://localhost:8766/test") route {
        asyncConvertToString >=> asyncRepeatBody
      }

      // non-blocking client route using
      // - asynchronous Jetty HTTP client and
      // - asynchronous message processor
      from("direct:test-1") route {
        "jetty:http://localhost:8766/test" >=> asyncAppendString("-done")
      }

      template.requestBody("direct:test-1", "test") must equal("testtest-done")
    }

    "caching of input streams for using original message in error handlers" in {
      from("jetty:http://localhost:8761/test") route {
        asyncConvertToString >=> asyncFailWith("failure")
      } onError classOf[Exception] route {
        asyncConvertToString >=> asyncAppendString("-handled") >=> syncMarkHandled
      }
      
      template.requestBody("http://localhost:8761/test", "test", classOf[String]) must equal ("test-handled")
    }
  }
}