# Run rest api methods as root
source `dirname $0`/functions.sh $1 ${@:3}

resource=${2#/}     # remove leading slash in case there, because we will add it below
curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/$resource | $parse

# Some examples of custom jq parsing:
# jq .users.bp
# jq '.nodes[] | {token,name}'
# jq '.nodes[] | .token'
# jq '.nodes | .["1"]'
# jq .nodes.d2.token