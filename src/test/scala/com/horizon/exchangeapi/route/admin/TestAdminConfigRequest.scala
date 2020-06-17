package com.horizon.exchangeapi.route.admin

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import com.horizon.exchangeapi.AdminConfigRequest

@RunWith(classOf[JUnitRunner])
class TestAdminConfigRequest extends AnyFunSuite {
  test("Constructor") {
    val testRequest  = AdminConfigRequest(varPath = "", value = "")
    
    assert(testRequest.isInstanceOf[AdminConfigRequest])
    
    assert(testRequest.varPath === "")
    
    assert(intercept[IllegalArgumentException]{AdminConfigRequest(varPath = null, value = "")}.getMessage() === "requirement failed")
    assert(intercept[IllegalArgumentException]{AdminConfigRequest(varPath = "", value = null)}.getMessage() === "requirement failed")
    assert(intercept[IllegalArgumentException]{AdminConfigRequest(varPath = null, value = null)}.getMessage() === "requirement failed")
  }
  
  test("Equality") {
    class TestClass(variable1:String = "")
    
    val testRequest  = AdminConfigRequest(varPath = "", value = "")
    val testRequest2 = AdminConfigRequest(varPath = "", value = "")
    val testRequest3 = AdminConfigRequest(varPath = " ", value = " ")
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
    val testRequest  = AdminConfigRequest(varPath = "", value = "")
    val testRequest2 = testRequest.copy()
    val testRequest3 = testRequest.copy(varPath = "a", value = "b")
    
    assert(testRequest2.varPath === "")
    assert(testRequest2.value === "")
    assert(testRequest3.varPath === "a")
    assert(testRequest3.value === "b")
    
    assert(testRequest.equals(testRequest2))
    assert(!testRequest.equals(testRequest3))
    
    assert(testRequest.hashCode() === testRequest2.hashCode())
    assert(testRequest.hashCode() !== testRequest3.hashCode())
    
    assert(testRequest === testRequest2)
    assert(testRequest !== testRequest3)
    
    assert(testRequest ne testRequest2)
    assert(testRequest ne testRequest3)
    
    assert(intercept[IllegalArgumentException]{testRequest.copy(varPath = null)}.getMessage() === "requirement failed")
    assert(intercept[IllegalArgumentException]{testRequest.copy(value = null)}.getMessage() === "requirement failed")
    assert(intercept[IllegalArgumentException]{testRequest.copy(varPath = null, value = "")}.getMessage() === "requirement failed")
    assert(intercept[IllegalArgumentException]{testRequest.copy(varPath = "", value = null)}.getMessage() === "requirement failed")
    assert(intercept[IllegalArgumentException]{testRequest.copy(varPath = null, value = null)}.getMessage() === "requirement failed")
  }
  
  test("toString()") {
    var testRequest = AdminConfigRequest(varPath = "abc", value = "def")
    
    assert(testRequest.toString() === "AdminConfigRequest(abc,def)")
  }
}