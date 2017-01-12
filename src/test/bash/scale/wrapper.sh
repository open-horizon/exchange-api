#!/bin/bash

# Facilitates scale testing of Exchange. Runs the specified number of instances of test.sh in the background and waits for them to finish.
# For true scale testing use a higher level script (not supplied) to run wrapper.sh on multiple systems.

if [[ -z $1 ]]; then
	echo "Usage: $0 <num instances of test.sh>"
	exit 1
fi

dir=`dirname $0`

function checkrc {
	if [[ $1 != $2 ]]; then
		echo "curl failed with rc $1"
		exit $1
	fi
}

for (( i=1 ; i<=$1 ; i++ )) ; do
	$dir/test.sh $i &
done

wait
