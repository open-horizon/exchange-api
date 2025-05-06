package org.openhorizon.exchangeapi.table.apikey
import com.fasterxml.jackson.annotation.{JsonProperty,JsonInclude}
@JsonInclude(JsonInclude.Include.NON_NULL)
final case class ApiKeyMetadata(
  id: String,
  description: String,
  @JsonInclude(JsonInclude.Include.NON_NULL)
  user: String,
  @JsonProperty("created_at")createdAt: String,
  @JsonProperty("created_by")createdBy: String,
  @JsonProperty("modified_at")modifiedAt: String,
  @JsonProperty("modified_by")modifiedBy: String
) {
  def this(row: ApiKeyRow) = this(
    id = row.id,
    description = row.description,
    user = row.username,
    createdAt = row.createdAt,
    createdBy = row.createdBy,
    modifiedAt = row.modifiedAt,
    modifiedBy = row.modifiedBy
  )
}