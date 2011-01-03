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
    "non-blocking routing with asynchronous functions that report success" in {
      from("direct:test-1") route {
        asyncAppendString("-1") >=> asyncAppendString("-2")
      }
      template.requestBody("direct:test-1", "test") must equal("test-1-2")
    }

    "non-blocking routing with asynchronous Camel processors that report success" in {
      from("direct:test-2") route {
        asyncRepeatBody >=> asyncRepeatBody
      }
      template.requestBody("direct:test-2", "x") must equal("xxxx")
    }

    "non-blocking routing with asynchronous functions that report failure" in {
      from("direct:test-3") route {
        asyncFailWith("blah") >=> asyncRepeatBody
      }
      try {
        template.requestBody("direct:test-3", "testZ")
      } catch {
        case e => e.getCause.getMessage must equal("blah")
      }
    }

    "routing with both asynchronous and synchronous functions" in {
      from("direct:test-4") route {
        asyncAppendString("-1") >=> syncAppendString("-2")
      }
      template.requestBody("direct:test-4", "test") must equal("test-1-2")
    }

    "routing with both asynchronous Camel processors that report success" in {
      from("direct:test-5") route {
        asyncRepeatBody >=> syncRepeatBody
      }
      template.requestBody("direct:test-5", "x") must equal("xxxx")
    }
  }
}
