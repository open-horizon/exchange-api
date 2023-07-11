package org.openhorizon.exchangeapi.table.node

object NodeType extends Enumeration {
  type NodeType = Value
  val DEVICE: NodeType.Value = Value("device")
  val CLUSTER: NodeType.Value = Value("cluster")
  def isDevice(str: String): Boolean = str == DEVICE.toString
  def isCluster(str: String): Boolean = str == CLUSTER.toString
  def containsString(str: String): Boolean = values.find(_.toString == str).orNull != null
  def valuesAsString: String = values.map(_.toString).mkString(", ")
}
