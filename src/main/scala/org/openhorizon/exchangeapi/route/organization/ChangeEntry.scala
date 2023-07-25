package org.openhorizon.exchangeapi.route.organization

import scala.collection.mutable.ListBuffer

final case class ChangeEntry(orgId: String,
                             var resource: String,
                             id: String,
                             var operation: String,
                             resourceChanges: ListBuffer[ResourceChangesInnerObject]){
  def addToResourceChanges(innerObject: ResourceChangesInnerObject): ListBuffer[ResourceChangesInnerObject] = { this.resourceChanges += innerObject}
  def setOperation(newOp: String): Unit = {this.operation = newOp}
  def setResource(newResource: String): Unit = {this.resource = newResource}
}
