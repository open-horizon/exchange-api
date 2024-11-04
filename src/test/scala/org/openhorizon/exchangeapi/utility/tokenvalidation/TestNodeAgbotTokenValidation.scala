package org.openhorizon.exchangeapi

import org.openhorizon.exchangeapi.utility.{NodeAgbotTokenValidation}
import org.scalatest.funsuite.AnyFunSuite


class TestNodeAgbotTokenValidation extends AnyFunSuite {
  test("NodeAgbotTokenValidation.isValid correctly identifies if password is too short, <15 chars"){
    info("NodeAgbotTokenValidation.isValid(\"1Abbb\")"+ " , " + NodeAgbotTokenValidation.isValid("1Abbb").toString())
    assert(NodeAgbotTokenValidation.isValid("1Abbb") == false)
    info("NodeAgbotTokenValidation.isValid(\"1Abcdefghijkl\")"+ " , " + NodeAgbotTokenValidation.isValid("1Abcdefghijkl").toString())
    assert(NodeAgbotTokenValidation.isValid("1Abcdefghijkl") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies if password does not contain digit"){
    info("NodeAgbotTokenValidation.isValid(\"aaaaaaaaaaaaaaaB\")"+ " , " + NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaaB").toString())
    assert(NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaaB") == false)
    info("NodeAgbotTokenValidation.isValid(\"abcdefghijklmno\")"+ " , " + NodeAgbotTokenValidation.isValid("abcdefghijklmno").toString())
    assert(NodeAgbotTokenValidation.isValid("abcdefghijklmno") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies if password does not contain uppercase letter"){
    info("NodeAgbotTokenValidation.isValid(\"aaaaaaaaaaaaaaa1\")"+ " , " + NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaa1").toString())
    assert(NodeAgbotTokenValidation.isValid("aaaaaaaaaaaaaaa1") == false)
    info("NodeAgbotTokenValidation.isValid(\"abcdefghijklmno1\")"+ " , " + NodeAgbotTokenValidation.isValid("abcdefghijklmno1").toString())
    assert(NodeAgbotTokenValidation.isValid("abcdefghijklmno1") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies if password does not contain lowercase letter"){
    info("NodeAgbotTokenValidation.isValid(\"AAAAAAAAAAAAAB1\")"+ " , " + NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAAB1").toString())
    assert(NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAAB1") == false)
    info("NodeAgbotTokenValidation.isValid(\"ABCDEFGHIJKLMNO1\")"+ " , " + NodeAgbotTokenValidation.isValid("ABCDEFGHIJKLMNO1").toString())
    assert(NodeAgbotTokenValidation.isValid("ABCDEFGHIJKLMNO1") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies valid password"){
    info("NodeAgbotTokenValidation.isValid(\"AAAAAAAAAAAAaB1\")"+ " , " + NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAaB1").toString())
    assert(NodeAgbotTokenValidation.isValid("AAAAAAAAAAAAaB1") == true)
    info("NodeAgbotTokenValidation.isValid(\"ValidPassword1!\")"+ " , " + NodeAgbotTokenValidation.isValid("ValidPassword1!").toString())
     assert(NodeAgbotTokenValidation.isValid("ValidPassword1!") == true)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies valid password with digits only") {
    info("NodeAgbotTokenValidation.isValid(\"123456789012345\")"+ " , " + NodeAgbotTokenValidation.isValid("123456789012345").toString())
    assert(NodeAgbotTokenValidation.isValid("123456789012345") == false)
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies password with exactly 15 characters") {
    info("NodeAgbotTokenValidation.isValid(\"Aa1aaaaaaaaaaaa\")"+ " , " + NodeAgbotTokenValidation.isValid("Aa1aaaaaaaaaaaa").toString())
    assert(NodeAgbotTokenValidation.isValid("Aa1" + "a" * 12) == true)  // valid
    info("NodeAgbotTokenValidation.isValid(\"Aaaaaaaabbbbbbb\")"+ " , " + NodeAgbotTokenValidation.isValid("Aaaaaaaabbbbbbb").toString())
    assert(NodeAgbotTokenValidation.isValid("Aaaaaaaabbbbbbb") == false) // invalid
    info("NodeAgbotTokenValidation.isValid(\"A1abbbbbbbbbbbb\")"+ " , " + NodeAgbotTokenValidation.isValid("A1abbbbbbbbbbbb").toString())
    assert(NodeAgbotTokenValidation.isValid("A1a" + "b" * 12) == true)  // valid
  }

  test("NodeAgbotTokenValidation.isValid correctly identifies password with special characters") {
    info("NodeAgbotTokenValidation.isValid(\"A1!abbbbbbbbbbb\")"+ " , " + NodeAgbotTokenValidation.isValid("A1!abbbbbbbbbbb").toString())
    assert(NodeAgbotTokenValidation.isValid("A1!a" + "b" * 11) == true)
    info("NodeAgbotTokenValidation.isValid(\"!A1abbbbbbbbbbb\")"+ " , " + NodeAgbotTokenValidation.isValid("!A1abbbbbbbbbbb").toString())
    assert(NodeAgbotTokenValidation.isValid("!A1a" + "b" * 11) == true)
  }
}
