package org.openhorizon.exchangeapi.route.administration

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestAdminHashpwResponse extends AnyFunSuite {
  test("Constructor") {
    val testResponse  = AdminHashpwResponse(hashedPassword = "")
    
    assert(testResponse.isInstanceOf[AdminHashpwResponse])
    
    assert(testResponse.hashedPassword === "")
  }
  
  test("Equality") {
    class TestClass(variable1:String = "")
    
    val testResponse  = AdminHashpwResponse(hashedPassword = "")
    val testResponse2 = AdminHashpwResponse(hashedPassword = "")
    val testResponse3 = AdminHashpwResponse(hashedPassword = " ")
    val testResponse4 = testResponse
    
    assert(!testResponse.equals(AnyRef))
    assert(!testResponse.equals(TestClass))
    
    assert(testResponse.equals(testResponse2))
    assert(!testResponse.equals(testResponse3))
    assert(testResponse2.equals(testResponse))
    assert(!testResponse2.equals(testResponse3))
    assert(testResponse.equals(testResponse4))
    
    assert(testResponse.hashCode() === testResponse2.hashCode())
    assert(testResponse.hashCode() !== testResponse3.hashCode())
    assert(testResponse.hashCode() === testResponse4.hashCode())
    
    assert(testResponse === testResponse2)
    assert(testResponse !== testResponse3)
    assert(testResponse === testResponse4)
    
    assert(testResponse ne testResponse2)
    assert(testResponse ne testResponse3)
    assert(testResponse2 ne testResponse3)
    assert(testResponse eq testResponse4)
  }
  
  test("Copy") {
    val testResponse  = AdminHashpwResponse(hashedPassword = "")
    val testResponse2 = testResponse.copy()
    val testResponse3 = testResponse.copy(hashedPassword = "a")
    
    assert(testResponse2.hashedPassword === "")
    assert(testResponse3.hashedPassword === "a")
    
    assert(testResponse.equals(testResponse2))
    assert(!testResponse.equals(testResponse3))
    
    assert(testResponse.hashCode() === testResponse2.hashCode())
    assert(testResponse.hashCode() !== testResponse3.hashCode())
    
    assert(testResponse === testResponse2)
    assert(testResponse !== testResponse3)
    
    assert(testResponse ne testResponse2)
    assert(testResponse ne testResponse3)
  }
  
  test("toString()") {
    var testResponse = AdminHashpwResponse(hashedPassword = "abc")
    
    assert(testResponse.toString() === "AdminHashpwResponse(abc)")
  }
}