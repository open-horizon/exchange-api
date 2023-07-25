package org.openhorizon.exchangeapi.table.resourcechange

import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.resourcechange.ResChangeCategory.ResChangeCategory
import org.openhorizon.exchangeapi.table.resourcechange.ResChangeOperation.ResChangeOperation
import org.openhorizon.exchangeapi.table.resourcechange.ResChangeResource.ResChangeResource
import slick.dbio.DBIO

final case class ResourceChange(changeId: Long,
                                orgId: String,
                                id: String,
                                category: ResChangeCategory,
                                public: Boolean,
                                resource: ResChangeResource,
                                operation: ResChangeOperation) {

  def toResourceChangeRow = ResourceChangeRow(changeId, orgId, id, category.toString, public.toString, resource.toString, operation.toString, ApiTime.nowUTCTimestamp)

  // update returns a DB action to update this row
  //def update: DBIO[_] = toResourceChangeRow.update

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = toResourceChangeRow.insert

  // Returns a DB action to insert or update this row
  //def upsert: DBIO[_] = toResourceChangeRow.upsert
}
