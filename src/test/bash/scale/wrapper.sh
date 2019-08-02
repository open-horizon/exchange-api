#!/bin/bash

# Facilitates scale testing of Exchange. Runs the specified number of instances of test.sh in the background and waits for them to finish.
# For true scale testing use a higher level script (not supplied) to run wrapper.sh on multiple systems, giving each one a different namebase.

if [[ -z $1 ]]; then
	echo "Usage: $0 <name-base> [<num-instances> <test-script>] ..."
	exit 1
fi

namebase=$1

# If the 1st pair is not specified, default it
if [[ -z $2 ]]; then
    set $namebase 1 test.sh
fi

script="${3:-test.sh}"

# default of where to write the summary or error msgs. Can be overridden
EX_PERF_REPORT_DIR="${EX_PERF_REPORT_DIR:-/tmp/exchangePerf}"

dir=`dirname $0`

function checkrc {
	if [[ $1 != $2 ]]; then
		echo "curl failed with rc $1"
		exit $1
	fi
}

# Clean up our children
trapHandler() {
    kill $pids
    exit
}

# Clear out all of the summaries (in case we are running with a lower number than previous)
rm -rf $EX_PERF_REPORT_DIR/$script/*

# Loop thru arg pairs (this 1st shift gets rid of namebase)
while shift; do
    if [[ -z "$1" ]]; then break; fi   # we are done

    # Get the next pair of args
    numInstances="$1"
    shift
    if [[ -z "$1" ]]; then
        echo "Error: no script specified after '$numInstances'"
        exit 1
    fi
    script="$1"

    # Fork the specified number of instances of this script
    for (( i=1 ; i<=$numInstances ; i++ )) ; do
        $dir/$script "${namebase}-$i" &
        pids="$pids $!"
    done
done

trap trapHandler SIGINT

# I think by specifying the list of pids, wait will return a non-zero exit code if any of the children do
wait $pids
exit $?
