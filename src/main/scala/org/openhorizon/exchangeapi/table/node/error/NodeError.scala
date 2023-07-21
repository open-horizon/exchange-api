package org.openhorizon.exchangeapi.table.node.error

//Node Errors
// We are using the type Any instead of this case class so anax and the UI can change the fields w/o our code having to change
final case class NodeError(errors: List[Any],
                           lastUpdated: String)
