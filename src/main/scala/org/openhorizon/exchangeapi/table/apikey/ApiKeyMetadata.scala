package org.openhorizon.exchangeapi.table.apikey
import com.fasterxml.jackson.annotation.{JsonProperty,JsonInclude}
@JsonInclude(JsonInclude.Include.NON_NULL)
final case class ApiKeyMetadata(
  id: String,
  description: String,
  @JsonInclude(JsonInclude.Include.NON_NULL)
  owner: String,
  lastUpdated: String
) {
  def this(row: ApiKeyRow) = this(
    id = row.id,
    description = row.description,
    owner = row.username,
    lastUpdated = row.modifiedAt
    
  )
}