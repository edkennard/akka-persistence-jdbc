/*
 * Copyright 2015 Dennis Vriend
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

package akka.persistence.jdbc.query

import akka.actor.{ ActorRef, Props }
import akka.event.LoggingReceive
import akka.persistence.PersistentActor
import akka.persistence.jdbc.TestSpec
import akka.persistence.jdbc.extension.SlickDatabase
import akka.persistence.jdbc.query.journal.JdbcReadJournal
import akka.persistence.journal.Tagged
import akka.persistence.query.{ EventEnvelope, PersistenceQuery }
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import slick.driver.PostgresDriver.api._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

abstract class QueryTestSpec(config: String) extends TestSpec(config) {

  val readJournal: JdbcReadJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

  def withCurrentPersistenceIds(within: FiniteDuration = 1.second)(f: TestSubscriber.Probe[String] ⇒ Unit): Unit = {
    val tp = readJournal.currentPersistenceIds().runWith(TestSink.probe[String])
    tp.within(within)(f(tp))
  }

  def withAllPersistenceIds(within: FiniteDuration = 1.second)(f: TestSubscriber.Probe[String] ⇒ Unit): Unit = {
    val tp = readJournal.allPersistenceIds().runWith(TestSink.probe[String])
    tp.within(within)(f(tp))
  }

  def withCurrentEventsByPersistenceid(within: FiniteDuration = 1.second)(persistenceId: String, fromSequenceNr: Long = 0, toSequenceNr: Long = Long.MaxValue)(f: TestSubscriber.Probe[EventEnvelope] ⇒ Unit): Unit = {
    val tp = readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).runWith(TestSink.probe[EventEnvelope])
    tp.within(within)(f(tp))
  }

  def withEventsByPersistenceId(within: FiniteDuration = 1.second)(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long)(f: TestSubscriber.Probe[EventEnvelope] ⇒ Unit): Unit = {
    val tp = readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).runWith(TestSink.probe[EventEnvelope])
    tp.within(within)(f(tp))
  }

  //  def withCurrentEventsByTag(within: FiniteDuration = 1.second)(tag: String, offset: Long)(f: TestSubscriber.Probe[EventEnvelope] ⇒ Unit): Unit = {
  //    val tp = readJournal.currentEventsByTag(tag, offset).runWith(TestSink.probe[EventEnvelope])
  //    tp.within(within)(f(tp))
  //  }

  case class DeleteCmd(toSequenceNr: Long = Long.MaxValue) extends Serializable

  class TestActor(id: Int) extends PersistentActor {
    override val persistenceId: String = "my-" + id

    var state: Int = 0

    override def receiveCommand: Receive = LoggingReceive {
      case "state" ⇒
        sender() ! state

      case DeleteCmd(toSequenceNr) ⇒
        deleteMessages(toSequenceNr)
        sender() ! s"deleted-$toSequenceNr"

      case event: Int ⇒
        persist(event) { (event: Int) ⇒
          updateState(event)
        }
    }

    def updateState(event: Int): Unit = {
      state = state + event
    }

    override def receiveRecover: Receive = LoggingReceive {
      case event: Int ⇒ updateState(event)
    }
  }

  def setupEmpty(persistenceId: Int): ActorRef = {
    system.actorOf(Props(new TestActor(persistenceId)))
  }

  def withTestActors(f: (ActorRef, ActorRef, ActorRef) ⇒ Unit): Unit = {
    f(setupEmpty(1), setupEmpty(2), setupEmpty(3))
  }

  def withTags(payload: Any, tags: String*) = Tagged(payload, Set(tags: _*))

  protected override def beforeEach(): Unit = {
    val db = SlickDatabase(system).db
    val queries = (for {
      _ ← sqlu"""TRUNCATE journal"""
      _ ← sqlu"""TRUNCATE deleted_to"""
      _ ← sqlu"""TRUNCATE snapshot"""
    } yield ()).transactionally
    db.run(queries).futureValue
    super.beforeAll()
  }
}
