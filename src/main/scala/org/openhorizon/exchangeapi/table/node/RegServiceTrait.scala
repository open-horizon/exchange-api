package org.openhorizon.exchangeapi.table.node

/** We define this trait because services in the DB and in the search criteria need the same methods, but have slightly different constructor args */
trait RegServiceTrait {
  def url: String   // this is the composite org/svcurl
  def properties: List[Prop]

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    for (p <- properties) {
      p.validate match {
        case Some(msg) => return Option[String](url+": "+msg)     // prepend the url so they know which service was bad
        case None => ;      // continue checking
      }
    }
    None     // this means it is valid
  }

  /** Returns true if this service (the search) matches that service (an entry in the db)
    * Rules for comparison:
    * - if both parties do not have the same property names, it is as if wildcard was specified
    */
  def matches(that: RegService): Boolean = {
    if (url != that.url) return false
    // go thru each of our props, finding and comparing the corresponding prop in that
    for (thatP <- that.properties) {
      properties.find(p => thatP.name == p.name) match {
        case None => ;        // if the node does not specify this property, that is equivalent to it specifying wildcard
        case Some(p) => if (!p.matches(thatP)) return false
      }
    }
    true
  }
}
