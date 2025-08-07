package org.openhorizon.exchangeapi.table.resourcechange

import org.openhorizon.exchangeapi.table.resourcechange.ResChangeCategory.ResChangeCategory
import org.openhorizon.exchangeapi.table.resourcechange.ResChangeOperation.ResChangeOperation
import org.openhorizon.exchangeapi.table.resourcechange.ResChangeResource.ResChangeResource
import org.openhorizon.exchangeapi.utility.ApiTime
import slick.dbio.DBIO

import java.time.Instant

final case class ResourceChange(changeId: Long,
                                orgId: String,
                                id: String,
                                category: ResChangeCategory,
                                public: Boolean,
                                resource: ResChangeResource,
                                operation: ResChangeOperation,
                                lastUpdated: Instant = Instant.now()) {

  def toResourceChangeRow = ResourceChangeRow(changeId, orgId, id, category.toString, public.toString, resource.toString, operation.toString, lastUpdated)

  // update returns a DB action to update this row
  //def update: DBIO[_] = toResourceChangeRow.update

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = toResourceChangeRow.insert

  // Returns a DB action to insert or update this row
  //def upsert: DBIO[_] = toResourceChangeRow.upsert
}
