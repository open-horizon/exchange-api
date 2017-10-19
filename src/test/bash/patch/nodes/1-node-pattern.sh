# Update node 1 as node
source `dirname $0`/../../functions.sh PATCH $*

curl $copts -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_NODEAUTH" -d '{
  "pattern": "'$EXCHANGE_ORG'/p1x"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/nodes/n1 | $parse
