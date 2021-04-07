package test.com.dounine.tractor.store

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import com.dounine.tractor.store.UserTable
import com.dounine.tractor.tools.akka.db.DataSource
import com.dounine.tractor.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

class DataSourceTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25521
                      |akka.extensions = ["com.dounine.tractor.tools.akka.db.DBSource"]
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          DataSourceTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          DataSourceTest
        ].getSimpleName}"
                      |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with MockitoSugar
    with JsonParse {

  "datasource test" ignore {
    "create entity" in {
      val db = DataSource(system).source().db
      val dict = TableQuery[UserTable]
      dict.schema.create.statements.foreach(println)
    }
  }
}
