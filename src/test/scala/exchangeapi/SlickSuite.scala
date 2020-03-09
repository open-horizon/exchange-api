package exchangeapi

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

//someday: use : http://blog.abhinav.ca/blog/2014/09/08/unit-testing-futures-with-scalatest/ to add slick unit tests

/**
 * Tests for Slick DB table
 * to run: `test-only exchangeapi.SlickSuite`
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class SlickSuite extends AnyFunSuite {

  ignore("listUsers") {
    // val cpds = new ComboPooledDataSource
    // val db = Database.forDataSource(cpds)
    // db.run(UsersTQ.getUser("bob").result) map { xs =>
    //   println(xs)
    // }
  }
  ignore("get user password") {
    // val cpds = new ComboPooledDataSource
    // val db = Database.forDataSource(cpds)
    // db.run(UsersTQ.getPassword("alice").result).map({ xs =>
    //   println(xs)
    // })
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

  ignore("node info") {
    // val cpds = new ComboPooledDataSource
    // val db = Database.forDataSource(cpds)
    // db.run(ExchangeApiTables.listUserNodes.result).map({ xs =>
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

  ignore("db-nodes") {
    // val cpds = new ComboPooledDataSource
    // val db = Database.forDataSource(cpds)
    // db.run(ExchangeApiTables.listUserNodes.result) map { xs =>
    //   println(xs)
    //   assert(xs != null)
    //for (user <- xs){
    //  println(user)
    //}
    // }
  }
}
