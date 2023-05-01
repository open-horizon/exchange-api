package org.openhorizon.exchangeapi.route.service

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestGetServiceAttributeResponse extends AnyFunSuite {
  test("Constructor") {
    val testResponse  = GetServiceAttributeResponse(attribute = "", value = "")
    
    assert(testResponse.isInstanceOf[GetServiceAttributeResponse])
    
    assert(testResponse.attribute === "")
    assert(testResponse.value === "")
  }
  
  test("Equality") {
    class TestClass(variable1:String = "", variable2:String = "", Variable3:String = "")
    
    val testResponse  = GetServiceAttributeResponse(attribute = "", value = "")
    val testResponse2 = GetServiceAttributeResponse(attribute = "", value = "")
    val testResponse3 = GetServiceAttributeResponse(attribute = " ", value = " ")
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
    val testResponse  = GetServiceAttributeResponse(attribute = "", value = "")
    val testResponse2 = testResponse.copy()
    val testResponse3 = testResponse.copy(attribute = "a", value = "b")
    
    assert(testResponse2.attribute === "")
    assert(testResponse2.value === "")
    assert(testResponse3.attribute === "a")
    assert(testResponse3.value === "b")
    
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
    var testResponse = GetServiceAttributeResponse(attribute = "abc", value = "def")
    
    assert(testResponse.toString() === "GetServiceAttributeResponse(abc,def)")
  }
}