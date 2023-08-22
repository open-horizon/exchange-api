package org.openhorizon.exchangeapi.auth

import scala.util.matching.Regex

final case class CompositeId(compositeId: String) {
  def getOrg: String = {
    val reg: Regex = """^(\S+?)/.*""".r
    compositeId match {
      case reg(org) => org
      case _ => ""
    }
  }

  def getId: String = {
    val reg: Regex = """^\S+?/(\S+)$""".r
    compositeId match {
      case reg(id) => id
      case _ => ""
    }
  }

  def split: (String, String) = {
    val reg: Regex = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org, id) => (org, id)
      // These 2 lines never get run, and aren't needed. If we really want to handle a special, put something like this as the 1st case above: case reg(org, "") => return (org, "")
      //case reg(org, _) => return (org, "")
      //case reg(_, id) => return ("", id)
      case _ => ("", "")
    }
  }
}
