package org.openhorizon.exchangeapi.table.node

import org.apache.pekko.http.scaladsl.server.ValidationRejection
import org.openhorizon.exchangeapi.ExchangeApiApp.reject
import org.openhorizon.exchangeapi.auth.BadInputException
import org.openhorizon.exchangeapi.utility.{ExchMsg, Version, VersionRange}

//throw BadInputException(summary = )

/** 1 generic property that is used in the node search criteria */
final case class Prop(name: String,
                      value: String,
                      propType: String,
                      op: String) {
  //def toPropRow(nodeId: String, msUrl: String) = PropRow(nodeId+"|"+msUrl+"|"+name, nodeId+"|"+msUrl, name, value, propType, op)

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    if (!PropType.contains(propType))
      return Option[String](ExchMsg.translate("invalid.proptype.for.name", propType, name))
    
    if (!Op.contains(op))
      return Option[String](ExchMsg.translate("invalid.op.for.name", op, name))
    
    
    
    if (propType == PropType.BOOLEAN) {
      if (op != Op.EQUAL)
        return Option[String](ExchMsg.translate("invalid.op.for.name.opequal", op, name, Op.EQUAL, PropType.BOOLEAN))
      
      if (value.toLowerCase != "true" &&
          value.toLowerCase != "false" &&
          value != "*")
        return Option[String](ExchMsg.translate("invalid.boolean.value.for.name", value, name))
    }
    
    if ((propType==PropType.LIST ||
         propType==PropType.STRING) &&
        op!=Op.IN)
      return Option[String](ExchMsg.translate("invalid.op.for.name.proplist", op, name, Op.IN, PropType.STRING, PropType.LIST))
    
    if (propType==PropType.INT) {
      if (op==Op.IN)
        return Option[String](ExchMsg.translate("invalid.op.for.name", op, name))
      //      if (op==Op.IN) return Option[String]("invalid op '"+op+"' specified for "+name)
      
      if (value != "*") {
        // ensure its a valid integer number
        try { value.toInt }
        catch { case _: Exception => return Option[String](ExchMsg.translate("invalid.int.for.name", value, name)) }
      }
    }
    
    if (propType == PropType.VERSION) {
      if (!(op==Op.EQUAL || op==Op.IN))
        return Option[String](ExchMsg.translate("invalid.op.for.name.propversion", op, name, Op.EQUAL, Op.IN, PropType.VERSION))
        
      if (value != "*") {       // verify it is a valid version or range format
        if (!VersionRange(value).isValid)
          return Option[String](ExchMsg.translate("invalid.version.for.name", value, name))
      }
    }
    
    None
  }

  /** Returns true if this property (the search) matches that property (an entry in the db) */
  def matches(that: Prop): Boolean = {
    if (name != that.name) return false     // comparison can only be done on the same name
    if (op != that.op) return false         // comparison only makes sense if they both have the same operator
    if (propType==PropType.WILDCARD || that.propType==PropType.WILDCARD) return true
    if (value=="*" || that.value=="*") return true
    (propType, that.propType) match {
      case (PropType.BOOLEAN, PropType.BOOLEAN) => op match {
        case Op.EQUAL => (value == that.value)
        case _ => false
      }
      // this will automatically transform a string into a list of strings
      case (PropType.LIST, PropType.LIST) | (PropType.STRING, PropType.LIST) | (PropType.LIST, PropType.STRING) | (PropType.STRING, PropType.STRING) => op match {
        case Op.IN => ( value.split(",").intersect(that.value.split(",")).length > 0 )
        case _ => false
      }
      case (PropType.INT, PropType.INT) => op match {
        case Op.EQUAL => (value == that.value)
        case Op.GTEQUAL => try { (that.value.toInt >= value.toInt) } catch { case _: Exception => false }
        case Op.LTEQUAL => try { (that.value.toInt <= value.toInt) } catch { case _: Exception => false }
        case _ => false
      }
      case (PropType.VERSION, PropType.VERSION) => op match {
        case Op.EQUAL => (Version(value) == Version(that.value))
        case Op.IN => (Version(that.value) in VersionRange(value))
        case _ => false
      }
      case _ => false
    }
  }
}
