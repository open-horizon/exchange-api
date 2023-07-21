package org.openhorizon.exchangeapi

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ApiUtilsSuite extends AnyFunSuite{

  test("ApiTime fixFormatting no milliseconds"){
    val timeNoMilliseconds = "2019-06-17T21:24:55Z[UTC]"
    info(ApiTime.fixFormatting(timeNoMilliseconds) + " , " + "2019-06-17T21:24:55.000Z[UTC]")
    assert(ApiTime.fixFormatting(timeNoMilliseconds) == "2019-06-17T21:24:55.000Z[UTC]")
  }

  test("ApiTime fixFormatting no seconds and nomilliseconds"){
    val timeNoSeconds = "2019-06-17T21:24Z[UTC]"
    info(ApiTime.fixFormatting(timeNoSeconds) + " , " + "2019-06-17T21:24:00.000Z[UTC]")
    assert(ApiTime.fixFormatting(timeNoSeconds) == "2019-06-17T21:24:00.000Z[UTC]")
  }

  test("ApiTime.nowUTC is always right length"){
    info(ApiTime.nowUTC)
    assert(ApiTime.nowUTC.length >= 29)
  }

  test("ApiTime.thenUTC is always right time and length"){
    info(ApiTime.thenUTC(1615406509)+ " , 2021-03-10T20:01:49.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406509) == "2021-03-10T20:01:49.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406509).length >= 29)
  }

  test("ApiTime.thenUTC is always right time and length test 2"){
    info(ApiTime.thenUTC(1615406355)+ " , 2021-03-10T19:59:15.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406355) == "2021-03-10T19:59:15.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406355).length >= 29)
  }

  test("ApiTime.pastUTC is always right length"){
    info(ApiTime.pastUTC(10))
    assert(ApiTime.pastUTC(10).length >= 29)
  }

  test("ApiTime.futureUTC is always right length"){
    info(ApiTime.futureUTC(10))
    assert(ApiTime.futureUTC(10).length >= 29)
  }

  test("ApiTime.beginningUTC is always correct and right length"){
    info(ApiTime.beginningUTC + " , " +"1970-01-01T00:00:00.000Z[UTC]")
    assert(ApiTime.beginningUTC == "1970-01-01T00:00:00.000Z[UTC]")
    assert(ApiTime.beginningUTC.length >= 29)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies if password is too short, <15 chars"){
    info("NodeAgbotTokenValidation.isValid(\"1Abbb\")"+ " , " + NodeAgbotTokenValidation.isValid("1Abbb").toString())
    assert(NodeAgbotTokenValidation.isValid("1Abbb") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies if password does not contain digit"){
    info("NodeAgbotTokenValidation.isValid(\"aaaaaaaaaaaaaaaB\")"+ " , " + NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaaB").toString())
    assert(NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaaB") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies if password does not contain uppercase letter"){
    info("NodeAgbotTokenValidation.isValid(\"aaaaaaaaaaaaaaa1\")"+ " , " + NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaa1").toString())
    assert(NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaa1") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies if password does not contain lowercase letter"){
    info("NodeAgbotTokenValidation.isValid(\"AAAAAAAAAAAAAB1\")"+ " , " + NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAAB1").toString())
    assert(NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAAB1") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies valid password"){
    info("NodeAgbotTokenValidation.isValid(\"AAAAAAAAAAAAaB1\")"+ " , " + NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAaB1").toString())
    assert(NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAaB1") == true)
  }
}
