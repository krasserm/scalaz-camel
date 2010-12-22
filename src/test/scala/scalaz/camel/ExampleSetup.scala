package scalaz.camel

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext

import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, WordSpec}

import scalaz._

/**
 * @author Martin Krasser
 */
class ExampleSetup extends WordSpec with MustMatchers with BeforeAndAfterAll with ExampleSupport {
  import Scalaz._
  import Camel._

  val context = new DefaultCamelContext
  val template = context.createProducerTemplate

  context.addRoutes(new RouteBuilder() {
    def configure = from("direct:const").transform().constant("const")
  })

  implicit val router = new Router(context)

  override def afterAll = router.stop

  "scalaz-camel" when {
    "given an implicit router that has not been started" must {
      "allow setup of routes" in {
        from("direct:test-1") route {
          "direct:const" >=> appendString("-1")
        }
      }
    }
    "given an implicit router that has been started" must {
      "allow setup of routes" in {
        router.start
        from("direct:test-2") route {
          "direct:const" >=> appendString("-2")
        }

        template.requestBody("direct:test-1", "test") must equal("const-1")
        template.requestBody("direct:test-2", "test") must equal("const-2")
      }
    }
  }
}