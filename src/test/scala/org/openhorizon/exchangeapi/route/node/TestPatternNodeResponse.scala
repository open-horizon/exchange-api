package org.openhorizon.exchangeapi.route.node

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestPatternNodeResponse extends AnyFunSuite {
  test("Constructor") {
    val testResponse  = PatternNodeResponse(id = "", nodeType = "", publicKey = "")
    
    assert(testResponse.isInstanceOf[PatternNodeResponse])
    
    assert(testResponse.id === "")
    assert(testResponse.nodeType === "")
    assert(testResponse.publicKey === "")
  }
  
  test("Equality") {
    class TestClass(variable1:String = "", variable2:String = "", Variable3:String = "")
    
    val testResponse  = PatternNodeResponse(id = "", nodeType = "", publicKey = "")
    val testResponse2 = PatternNodeResponse(id = "", nodeType = "", publicKey = "")
    val testResponse3 = PatternNodeResponse(id = " ", nodeType = " ", publicKey = " ")
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
    val testResponse  = PatternNodeResponse(id = "", nodeType = "", publicKey = "")
    val testResponse2 = testResponse.copy()
    val testResponse3 = testResponse.copy(id = "a", nodeType = "b", publicKey = "c")
    
    assert(testResponse2.id === "")
    assert(testResponse2.nodeType === "")
    assert(testResponse2.publicKey === "")
    assert(testResponse3.id === "a")
    assert(testResponse3.nodeType === "b")
    assert(testResponse3.publicKey === "c")
    
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
    var testResponse = PatternNodeResponse(id = "abc", nodeType = "def", publicKey = "ghi")
    
    assert(testResponse.toString() === "PatternNodeResponse(abc,def,ghi)")
  }
}