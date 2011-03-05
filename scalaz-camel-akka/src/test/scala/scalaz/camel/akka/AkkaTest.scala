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
package scalaz.camel.akka

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.scalatest.{WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor
import akka.camel.{Message => Msg}

import scalaz._
import scalaz.concurrent.Strategy

import scalaz.camel.core._

/**
 * @author Martin Krasser
 */
trait AkkaTest extends AkkaTestContext with WordSpec with MustMatchers with BeforeAndAfterAll {
  import Scalaz._

  override def beforeAll = router.start
  override def afterAll = router.stop

  def support = afterWord("support")

  "scalaz.camel.akka.Akka" should support {
    "1:1 in-out messaging with an actor that is accessed via the native API" in {
      val appender = Actor.actorOf(new AppendReplyActor("-2", 1))
      val route = appendToBody("-1") >=> to(appender.manage) >=> appendToBody("-3")
      route process Message("a") match {
        case Success(Message(body, _)) => body must equal("a-1-2-3")
        case _                         => fail("unexpected response")
      }
    }

    "1:n in-out messaging with an actor that is accessed via the native API" in {
      val appender = Actor.actorOf(new AppendReplyActor("-2", 3))
      val route = appendToBody("-1") >=> to(appender.manage) >=> appendToBody("-3")
      val queue = route submitN Message("a")
      List(queue.take, queue.take, queue.take) foreach { e =>
        e match {
          case Success(Message(body, _)) => body must equal("a-1-2-3")
          case _                         => fail("unexpected response")
        }
      }
    }

    "1:1 in-out messaging with an actor that is accessed via the Camel actor component" in {
      val greeter = Actor.actorOf(new GreetReplyActor)
      val route = appendToBody("-1") >=> to(greeter.manage.uri) >=> appendToBody("-3")
      route process Message("a") match {
        case Success(Message(body, _)) => body must equal("a-1-hello-3")
        case _                         => fail("unexpected response")
      }

    }

    "in-only messaging with an actor that is accessed via the native API" in {
      val latch = new CountDownLatch(1)
      val appender = Actor.actorOf(new CountDownActor("a-1", latch))
      val route = appendToBody("-1") >=> oneway >=> to(appender.manage) >=> appendToBody("-3")
      route process Message("a") match {
        case Success(Message(body, _)) => body must equal("a-1-3")
        case _                         => fail("unexpected response")
      }
      latch.await(5, TimeUnit.SECONDS) must be (true)
    }

    "in-only messaging with an actor that is accessed via the Camel actor component" in {
      val latch = new CountDownLatch(1)
      val appender = Actor.actorOf(new CountDownActor("a-1", latch))
      val route = appendToBody("-1") >=> oneway >=> to(appender.manage.uri) >=> appendToBody("-3")
      route process Message("a") match {
        case Success(Message(body, _)) => body must equal("a-1-3")
        case _                         => fail("unexpected response")
      }
      latch.await(5, TimeUnit.SECONDS) must be (true)
    }

    "aggregation of messages" in {
      val f: AggregationFunction = (m1, m2) => m1.appendToBody(m2.body)
      val p: CompletionPredicate = m => m.bodyAs[String].length == 5

      val ms = for (i <- 1 to 5) yield Message("%s" format i)
      
      (aggregate using f until p) >=> appendToBody("-done") process ms match {
        case Success(Message(body: String, _)) => dispatchConcurrencyStrategy match {
          case Strategy.Sequential => body must equal ("12345-done")
          case _                   => body.length must be (10)
        }
        case _ => fail("unexpected response")
      }
    }
  }

  class AppendReplyActor(s: String, c: Int) extends Actor {
    def receive = {
      case m: Message => for (_ <- 1 to c) self.reply(m.appendToBody(s))
    }
  }

  class GreetReplyActor extends Actor {
    def receive = {
      case Msg(body, _) => self.reply("%s-hello" format body)
    }
  }

  class CountDownActor(b: String, latch: CountDownLatch) extends Actor {
    def receive = {
      case Message(body, _) => if (body == b) latch.countDown
      case Msg(body, _)     => if (body == b) latch.countDown
    }
  }
}

class AkkaTestSequential extends AkkaTest
class AkkaTestConcurrent extends AkkaTest {
  dispatchConcurrencyStrategy = Strategy.Naive
  multicastConcurrencyStrategy = Strategy.Naive
}
