#!/usr/bin/env bash

function retry() {
  local END
  local EXIT_CODE

  END=$(($(date +%s) + 600))

  while (( $(date +%s) < $END )); do
    set +e
    "$@"
    EXIT_CODE=$?
    set -e

    if [[ ${EXIT_CODE} == 0 ]]; then
      break
    fi
    sleep 5
  done

  return ${EXIT_CODE}
}

function hadoop_master_container(){
  docker-compose -f "${DOCKER_COMPOSE_LOCATION}" ps -q hadoop-master | grep .
}

function hadoop_master_ip() {
  HADOOP_MASTER_CONTAINER=$(hadoop_master_container)
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $HADOOP_MASTER_CONTAINER
}

function check_hadoop() {
  HADOOP_MASTER_CONTAINER=$(hadoop_master_container)
  docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl status hive-server2 | grep -i running &> /dev/null && \
    docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl status hive-metastore | grep -i running &> /dev/null && \
    docker exec ${HADOOP_MASTER_CONTAINER} netstat -lpn | grep -i 0.0.0.0:10000 &> /dev/null &&
    docker exec ${HADOOP_MASTER_CONTAINER} netstat -lpn | grep -i 0.0.0.0:9083 &> /dev/null
}

function exec_in_hadoop_master_container() {
  HADOOP_MASTER_CONTAINER=$(hadoop_master_container)
  docker exec ${HADOOP_MASTER_CONTAINER} "$@"
}

function stop_unnecessary_hadoop_services() {
  HADOOP_MASTER_CONTAINER=$(hadoop_master_container)
  docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl status
  docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl stop yarn-resourcemanager
  docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl stop yarn-nodemanager
}

# Expands docker compose file paths files into the format "-f $1 -f $2 ...."
# Arguments:
#   $1, $2, ...: A list of docker-compose files used to start/stop containers
function expand_compose_args() {
  local files=( "${@}" )
  local compose_args=""
  for file in ${files[@]}; do
    compose_args+=" -f ${file}"
  done
  echo "${compose_args}"
}

function cleanup_docker_containers() {
  local compose_args="$(expand_compose_args "$@")"
  # stop containers started with "up"
  docker-compose ${compose_args} down --remove-orphans

  # docker logs processes are being terminated as soon as docker container are stopped
  # wait for docker logs termination
  wait
}

function cleanup_hadoop_docker_containers() {
  cleanup_docker_containers "${DOCKER_COMPOSE_LOCATION}"
}

function termination_handler(){
  set +e
  cleanup_docker_containers "$@"
  exit 130
}

SCRIPT_DIR="${BASH_SOURCE%/*}"
INTEGRATION_TESTS_ROOT="${SCRIPT_DIR}/.."
PROJECT_ROOT="${INTEGRATION_TESTS_ROOT}/.."
DOCKER_COMPOSE_LOCATION="${INTEGRATION_TESTS_ROOT}/conf/docker-compose.yml"
source "${BASH_SOURCE%/*}/../../presto-product-tests/conf/product-tests-defaults.sh"

# check docker and docker compose installation
docker-compose version
docker version

# extract proxy IP
if [ -n "${DOCKER_MACHINE_NAME:-}" ]
then
  PROXY=`docker-machine ip`
else
  PROXY=127.0.0.1
fi

# Starts containers based on multiple docker compose locations
# Arguments:
#   $1, $2, ...: A list of docker-compose files used to start containers
function start_docker_containers() {
  local compose_args="$(expand_compose_args $@)"
  # Purposefully don't surround ${compose_args} with quotes so that docker-compose infers multiple arguments
  # stop already running containers
  docker-compose ${compose_args} down || true

  # catch terminate signals
  # trap arguments are not expanded until the trap is called, so they must be in a global variable
  TRAP_ARGS="$@"
  trap 'termination_handler $TRAP_ARGS' INT TERM

  # pull docker images
  if [[ "${CONTINUOUS_INTEGRATION:-false}" == 'true' ]]; then
    docker-compose ${compose_args} pull --quiet
  fi

  # start containers
  docker-compose ${compose_args} up -d
}

function start_hadoop_docker_containers() {
  start_docker_containers "${DOCKER_COMPOSE_LOCATION}"

  # start docker logs for hadoop container
  docker-compose -f "${DOCKER_COMPOSE_LOCATION}" logs --no-color hadoop-master &

  # wait until hadoop processes is started
  retry check_hadoop
}
