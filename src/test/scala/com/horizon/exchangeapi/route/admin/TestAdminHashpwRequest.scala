package com.horizon.exchangeapi.route.admin

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import com.horizon.exchangeapi.AdminHashpwRequest

@RunWith(classOf[JUnitRunner])
class TestAdminHashpwRequest extends AnyFunSuite {
  test("Constructor") {
    val testRequest  = AdminHashpwRequest(password = "")
    
    assert(testRequest.isInstanceOf[AdminHashpwRequest])
    
    assert(testRequest.password === "")
    
    assert(intercept[IllegalArgumentException]{AdminHashpwRequest(password = null)}.getMessage() === "requirement failed")
  }
  
  test("Equality") {
    class TestClass(variable1:String = "")
    
    val testRequest  = AdminHashpwRequest(password = "")
    val testRequest2 = AdminHashpwRequest(password = "")
    val testRequest3 = AdminHashpwRequest(password = " ")
    val testRequest4 = testRequest
    
    assert(!testRequest.equals(AnyRef))
    assert(!testRequest.equals(TestClass))
    
    assert(testRequest.equals(testRequest2))
    assert(!testRequest.equals(testRequest3))
    assert(testRequest2.equals(testRequest))
    assert(!testRequest2.equals(testRequest3))
    assert(testRequest.equals(testRequest4))
    
    assert(testRequest.hashCode() === testRequest2.hashCode())
    assert(testRequest.hashCode() !== testRequest3.hashCode())
    assert(testRequest.hashCode() === testRequest4.hashCode())
    
    assert(testRequest === testRequest2)
    assert(testRequest !== testRequest3)
    assert(testRequest === testRequest4)
    
    assert(testRequest ne testRequest2)
    assert(testRequest ne testRequest3)
    assert(testRequest2 ne testRequest3)
    assert(testRequest eq testRequest4)
  }
  
  test("Copy") {
    val testRequest  = AdminHashpwRequest(password = "")
    val testRequest2 = testRequest.copy()
    val testRequest3 = testRequest.copy(password = "a")
    
    assert(testRequest2.password === "")
    assert(testRequest3.password === "a")
    
    assert(testRequest.equals(testRequest2))
    assert(!testRequest.equals(testRequest3))
    
    assert(testRequest.hashCode() === testRequest2.hashCode())
    assert(testRequest.hashCode() !== testRequest3.hashCode())
    
    assert(testRequest === testRequest2)
    assert(testRequest !== testRequest3)
    
    assert(testRequest ne testRequest2)
    assert(testRequest ne testRequest3)
    
    assert(intercept[IllegalArgumentException]{testRequest.copy(password = null)}.getMessage() === "requirement failed")
  }
  
  test("toString()") {
    var testRequest = AdminHashpwRequest(password = "abc")
    
    assert(testRequest.toString() === "AdminHashpwRequest(abc)")
  }
}