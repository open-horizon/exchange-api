# Run rest api methods as user 2. Most useful for methods that do not need an input body, like GET and DELETE.
source `dirname $0`/functions.sh $1 ${@:3}

resource=${2#/}     # remove leading slash in case there, because we will add it below
curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic 2:$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/$resource | $parse