# Running Cluster

The configs include a Docker Compose file that helps to spin up a local Mesos cluster with Marathon. The Mesos cluster
will include a [cutom mock executor](https://github.com/mesosphere/marathon-performance-custom-executor) which are
precompiled [here](https://github.com/mesosphere/marathon-perf-testing/tree/master/files).

Spin up the cluster with

```
docker network create testing 

MARATHON_VERSION=v1.8.119 \
MESOS_VERSION=1.5.1-rc1 \
CLUSTER_WORKDIR=./workdir \
MARATHON_PERF_TESTING_DIR="$(pwd)/marathon-perf-testing" \
NETWORK_ID=testing \
docker-compose -f config/docker-compose.yml up
```
