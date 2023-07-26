package org.openhorizon.exchangeapi.auth

import scala.util.matching.Regex

/** This and its subclasses are used to identify the target resource the rest api method goes after */
abstract class Target {
  def id: String    // this is the composite id, e.g. orgid/username
  def all: Boolean = getId == "*"
  def mine: Boolean = getId == "#"
  def isPublic: Boolean = false    // is overridden by some subclasses
  def isThere: Boolean = false    // is overridden by some subclasses
  def isOwner(user: IUser): Boolean = false    // is overridden by some subclasses
  //def isAdmin = false       // <- we can't reliably determine these 2
  //def isHubAdmin = false
  def isSuperUser = false       // TUser overrides this
  def label: String = ""    // overridden by subclasses. This should be the exchange resource type
  def toAccessMsg = s"$label=$id"  // the way the target should be described in access denied msgs

  // Returns just the orgid part of the resource
  def getOrg: String = {
    val reg: Regex = """^(\S+?)/.*""".r
    id match {
      case reg(org) => org
      case _ => ""
    }
  }

  // Returns just the id or username part of the resource
  def getId: String = {
    val reg: Regex = """^\S+?/(\S+)$""".r
    id match {
      case reg(id) => id
      case _ => ""
    }
  }
}
