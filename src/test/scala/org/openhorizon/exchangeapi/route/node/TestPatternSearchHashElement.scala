package org.openhorizon.exchangeapi.route.node

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestPatternSearchHashElement extends AnyFunSuite {
  test("Constructor") {
    val testResponse  = PatternSearchHashElement(nodeType = "", publicKey = "", noAgreementYet = false)
    
    assert(testResponse.isInstanceOf[PatternSearchHashElement])
    
    assert(testResponse.nodeType === "")
    assert(testResponse.publicKey === "")
    assert(testResponse.noAgreementYet === false)
  }
  
  test("Equality") {
    class TestClass(variable1:String = "", variable2:String = "", Variable3:String = "")
    
    val testResponse  = PatternSearchHashElement(nodeType = "", publicKey = "", noAgreementYet = false)
    val testResponse2 = PatternSearchHashElement(nodeType = "", publicKey = "", noAgreementYet = false)
    val testResponse3 = PatternSearchHashElement(nodeType = " ", publicKey = " ", noAgreementYet = true)
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
    val testResponse  = PatternSearchHashElement(nodeType = "", publicKey = "", noAgreementYet = false)
    val testResponse2 = testResponse.copy()
    val testResponse3 = testResponse.copy(nodeType = "a", publicKey = "b", noAgreementYet = true)
    
    assert(testResponse2.nodeType === "")
    assert(testResponse2.publicKey === "")
    assert(testResponse2.noAgreementYet === false)
    assert(testResponse3.nodeType === "a")
    assert(testResponse3.publicKey === "b")
    assert(testResponse3.noAgreementYet === true)
    
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
    var testResponse = PatternSearchHashElement(nodeType = "abc", publicKey = "def", noAgreementYet = true)
    
    assert(testResponse.toString() === "PatternSearchHashElement(abc,def,true)")
  }
}