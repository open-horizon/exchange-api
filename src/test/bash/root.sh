# Run rest api methods as root
source `dirname $0`/functions.sh $1 ${@:3}

resource=${2#/}     # remove leading slash in case there, because we will add it below
if [[ ${resource:0:6} == "admin/" ]]; then
  org=""     # the admin methods are not under the org resource
else
  org="orgs/$EXCHANGE_ORG/"
fi
curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" $EXCHANGE_URL_ROOT/v1/${org}$resource | $parse

# Some examples of custom jq parsing:
# jq .users.bp
# jq '.nodes[] | {token,name}'
# jq '.nodes[] | .token'
# jq '.nodes | .["1"]'
# jq .nodes.d2.token