package scalaz.camel

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

  "scalaz.camel.async.Camel" should support {
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

    "non-blocking content-based routing" in {
      from("direct:test-10") route {
        asyncAppendString("-1") >=> choose {
          case Message("a-1", _) => asyncAppendString("-a") >=> asyncAppendString("-b")
          case Message("b-1", _) => asyncFailWith("failed") >=> asyncAppendString("-d")
        } >=> asyncAppendString("-2")
      }
      template.requestBody("direct:test-10", "a") must equal("a-1-a-b-2")
      try {
        template.requestBody("direct:test-10", "b")
      } catch {
        case e => e.getCause.getMessage must equal("failed")
      }
    }

    "sending messages to a list of recipients that all succeed" in {
      val aggregator = (m1: Message, m2: Message) => m1.appendBody(" - %s" format m2.body)

      from("direct:test-11") route {
        asyncAppendString("-1") >=> multicast(
          asyncAppendString("-a") >=> asyncAppendString("-b"),
          asyncAppendString("-c") >=> asyncAppendString("-d")
        ) { aggregator } >=> asyncAppendString(" done")
      }
      template.requestBody("direct:test-11", "a") must equal("a-1-a-b - a-1-c-d done")
    }

    "sending messages to a list of recipients that fail" in {
      val aggregator = (m1: Message, m2: Message) => m1.appendBody(" - %s" format m2.body)

      from("direct:test-12") route {
        asyncAppendString("-1") >=> multicast(
          asyncAppendString("-a") >=> asyncFailWith("oops1"),
          asyncFailWith("oops2")  >=> asyncAppendString("-d")
        ) { aggregator } >=> asyncAppendString(" done")
      }
      try {
        template.requestBody("direct:test-12", "a") must equal ("a-4")
      } catch {
        case e => e.getCause.getMessage must equal("oops1")
      }
    }

    "error handling with an error handler where exception is handled" in {
      from("direct:test-20") route {
        asyncAppendString("-1") >=> asyncFailWith("failure")
      } onError classOf[Exception] route {
        asyncAppendString("-2") >=> syncMarkHandled
      }
      template.requestBody("direct:test-20", "test") must equal("test-2")
    }

    "error handling with an error handler where exception is not handled" in {
      from("direct:test-21") route {
        asyncAppendString("-1") >=> asyncFailWith("failure")
      } onError classOf[Exception] route {
        asyncAppendString("-2")
      }
      try {
        template.requestBody("direct:test-21", "test")
      } catch {
        case e => e.getCause.getMessage must equal("failure")
      }
    }

    "error handling with an error handler that fails" in {
      from("direct:test-22") route {
        asyncAppendString("-1") >=> asyncFailWith("failure")
      } onError classOf[Exception] route {
        asyncAppendString("-2") >=> asyncFailWith("error")
      }
      try {
        template.requestBody("direct:test-22", "test")
      } catch {
        case e => e.getCause.getMessage must equal("error")
      }
    }

    "error handling with several error routes" in {
      from("direct:test-23") route {
        asyncAppendString("-1") >=> choose {
          case Message("a-1", _) => asyncFailWith(new IllegalArgumentException("x"))
          case Message("b-1", _) => asyncFailWith(new Exception("y"))
        }
      } onError classOf[IllegalArgumentException] route {
        asyncAppendString("-3") >=> syncMarkHandled
      } onError classOf[Exception] route {
        asyncAppendString("-4") >=> syncMarkHandled
      }
      template.requestBody("direct:test-23", "a") must equal("a-3")
      template.requestBody("direct:test-23", "b") must equal("b-4")
    }
  }
}
