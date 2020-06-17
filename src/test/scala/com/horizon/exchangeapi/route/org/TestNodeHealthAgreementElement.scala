package com.horizon.exchangeapi.route.org

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import com.horizon.exchangeapi.NodeHealthAgreementElement

@RunWith(classOf[JUnitRunner])
class TestNodeHealthAgreementElement extends AnyFunSuite {
  test("Constructor") {
    val testElement  = NodeHealthAgreementElement(lastUpdated = "")
    
    assert(testElement.isInstanceOf[NodeHealthAgreementElement])
    
    assert(testElement.lastUpdated === "")
  }
  
  test("Equality") {
    class TestClass(variable1:String = "")
    
    val testElement  = NodeHealthAgreementElement(lastUpdated = "")
    val testElement2 = NodeHealthAgreementElement(lastUpdated = "")
    val testElement3 = NodeHealthAgreementElement(lastUpdated = " ")
    val testElement4 = testElement
    
    assert(!testElement.equals(AnyRef))
    assert(!testElement.equals(TestClass))
    
    assert(testElement.equals(testElement2))
    assert(!testElement.equals(testElement3))
    assert(testElement2.equals(testElement))
    assert(!testElement2.equals(testElement3))
    assert(testElement.equals(testElement4))
    
    assert(testElement.hashCode() === testElement2.hashCode())
    assert(testElement.hashCode() !== testElement3.hashCode())
    assert(testElement.hashCode() === testElement4.hashCode())
    
    assert(testElement === testElement2)
    assert(testElement !== testElement3)
    assert(testElement === testElement4)
    
    assert(testElement ne testElement2)
    assert(testElement ne testElement3)
    assert(testElement2 ne testElement3)
    assert(testElement eq testElement4)
  }
  
  test("Copy") {
    val testElement  = NodeHealthAgreementElement(lastUpdated = "")
    val testElement2 = testElement.copy()
    val testElement3 = testElement.copy(lastUpdated = "a")
    
    assert(testElement2.lastUpdated === "")
    assert(testElement3.lastUpdated === "a")
    
    assert(testElement.equals(testElement2))
    assert(!testElement.equals(testElement3))
    
    assert(testElement.hashCode() === testElement2.hashCode())
    assert(testElement.hashCode() !== testElement3.hashCode())
    
    assert(testElement === testElement2)
    assert(testElement !== testElement3)
    
    assert(testElement ne testElement2)
    assert(testElement ne testElement3)
  }
  
  test("toString()") {
    var testElement = NodeHealthAgreementElement(lastUpdated = "abc")
    
    assert(testElement.toString() === "NodeHealthAgreementElement(abc)")
  }
}