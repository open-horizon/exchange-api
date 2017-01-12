package exchangeapi

import org.scalatest.FunSuite

import com.mchange.v2.c3p0.ComboPooledDataSource
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
import scala.collection.immutable._
import java.time._
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.ExecutionContext.Implicits.global
import com.horizon.exchangeapi.ExchangeApiTables
import scala.util.{Success, Failure}
import com.horizon.exchangeapi.tables._

//TODO: use : http://blog.abhinav.ca/blog/2014/09/08/unit-testing-futures-with-scalatest/ to add slick unit tests

/**
 * Tests for Slick DB table
 * to run: `test-only exchangeapi.SlickSuite`
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class SlickSuite extends FunSuite {

  ignore("listUsers") {
    val cpds = new ComboPooledDataSource
    val db = Database.forDataSource(cpds)
    db.run(UsersTQ.getUser("bob").result) map { xs =>
      println(xs)
    }
  }
  ignore("get user password") {
    val cpds = new ComboPooledDataSource
    val db = Database.forDataSource(cpds)
    db.run(UsersTQ.getPassword("alice").result).map({ xs =>
      println(xs)
    })
  }
  test("user info") {
    // val cpds = new ComboPooledDataSource
    // val db = Database.forDataSource(cpds)
    //db.run(ExchangeApiTables.userInfo("alice").result) map { xs =>
    //  info("foo\n")
    //  xs
    //}
    //db.run(ExchangeApiTables.users.result).onSuccess {
    //  case Success(msg) => println(msg)
    //  case Failure(msg) => println(msg)
    //}
  }

  ignore("device info") {
    // val cpds = new ComboPooledDataSource
    // val db = Database.forDataSource(cpds)
    // db.run(ExchangeApiTables.listUserDevices.result).map({ xs =>
    //   println(xs)
    // })
  }


  ignore("get one user password") {
    // var cpds = new ComboPooledDataSource
    // var db = Database.forDataSource(cpds)
    //var xs = db.run(ExchangeApiTables.userPassword("alice", "password1").result)
    //println("starting")
    //xs.onComplete = println("foobar")
  }

  ignore("db-devices") {
    // val cpds = new ComboPooledDataSource
    // val db = Database.forDataSource(cpds)
    // db.run(ExchangeApiTables.listUserDevices.result) map { xs =>
    //   println(xs)
    //   assert(xs != null)
      //for (user <- xs){
      //  println(user)
      //}
    // }
  }
}
