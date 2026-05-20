#!/usr/bin/env bash

# Kubernetes-optimized GTFS tripplanner entrypoint script
# Handles graceful shutdown, error handling, and configuration

set -o pipefail
set -o nounset
set -o errexit

# Global variables
readonly SCRIPT_NAME="$(basename "$0")"
readonly LOG_LEVEL="${LOG_LEVEL:-INFO}"
readonly AWS_RETRY_COUNT="${AWS_RETRY_COUNT:-3}"
readonly AWS_RETRY_DELAY="${AWS_RETRY_DELAY:-2}"

# Process tracking
CHILD_PID=""
SHUTDOWN_INITIATED=false

log() {
  local level="$1"
  shift
  local timestamp
  timestamp="$(date -u '+%Y-%m-%dT%H:%M:%S.%3NZ')"
  echo >&2 "${timestamp} [${level}] [gtfs-tripplanner] $*"
}

# Signal handling for graceful shutdown
cleanup() {
  if [[ "$SHUTDOWN_INITIATED" == "true" ]]; then
    return
  fi

  SHUTDOWN_INITIATED=true
  log "INFO" "Shutdown signal received, initiating graceful shutdown..."

  if [[ -n "$CHILD_PID" ]] && kill -0 "$CHILD_PID" 2>/dev/null; then
    log "INFO" "Sending TERM signal to OTP server (PID: $CHILD_PID)"
    kill -TERM "$CHILD_PID" 2>/dev/null || true

    # Wait for graceful shutdown with timeout
    local timeout=30
    while [[ $timeout -gt 0 ]] && kill -0 "$CHILD_PID" 2>/dev/null; do
      sleep 1
      ((timeout--))
    done

    # Force kill if still running
    if kill -0 "$CHILD_PID" 2>/dev/null; then
      log "WARN" "Graceful shutdown timeout, forcing termination"
      kill -KILL "$CHILD_PID" 2>/dev/null || true
    fi
  fi

  log "INFO" "Shutdown complete"
  exit 0
}

trap cleanup SIGTERM SIGINT SIGHUP

validate_required_env() {
  local var_name="$1"
  if [[ -z "${!var_name:-}" ]]; then
    log "ERROR" "Required environment variable '$var_name' is not set"
    return 1
  fi
}

aws_retry() {
  local cmd=("$@")
  local attempt=1

  while [[ $attempt -le $AWS_RETRY_COUNT ]]; do
    log "DEBUG" "AWS API call attempt $attempt/$AWS_RETRY_COUNT: ${cmd[*]}"

    if "${cmd[@]}"; then
      return 0
    fi

    local exit_code=$?
    if [[ $attempt -eq $AWS_RETRY_COUNT ]]; then
      log "ERROR" "AWS API call failed after $AWS_RETRY_COUNT attempts: ${cmd[*]}"
      return $exit_code
    fi

    log "WARN" "AWS API call failed (attempt $attempt/$AWS_RETRY_COUNT), retrying in ${AWS_RETRY_DELAY}s..."
    sleep "$AWS_RETRY_DELAY"
    ((attempt++))
  done
}

function print_help {
  echo "usage: $0 [options] <build | serve>"
  echo "Run or build a graph for OTP server"
  echo ""
  echo "-h,--help print this help"
  echo
  echo "--config-files-path Location where to copy OTP config files from. (default: /app/config)"
  echo "--osm-file-name Name of the .osm.pbf file to use when building OTP graph. (default: otp)"
  echo "--s3-bucket S3 bucket where OSM and GTFS data are stored. This is used to upload OTP graph as well. (default: shotl-maps)"
  echo "--osm-dir Name of the directory in the S3 bucket where OSM data is stored. (default: osm/\$ENVIRONMENT)"
  echo "--gtfs-dir Name of the directory in the S3 bucket where GTFS data is stored. (default: gtfs/\$ENVIRONMENT)"
  echo "--otp-dir Name of the directory in the S3 bucket where OTP data is stored. (default: otp/\$ENVIRONMENT)"
  echo "--max-memory JVM max memory in GB for the chosen process. (default: 2)"
  echo "--extra-args Extra java arguments for the chosen process"
  echo "--mount-path Path where the volume for builds is mounted. (default: /app/build)"
}

# validate required env variables
validate_required_env "ENVIRONMENT" || exit 1

POSITIONAL=()

while [[ $# -gt 0 ]]; do
  key="$1"
  case $key in
  -h | --help)
    print_help
    exit 1
    ;;
  --config-files-path)
    CONFIG_FILES_PATH="$2"
    log "INFO" "--config-files-path='${CONFIG_FILES_PATH}'"
    shift
    shift
    ;;
  --osm-file-name)
    OSM_FILE_NAME+=("$2")
    log "INFO" "osm-file-name='${OSM_FILE_NAME}'"
    shift
    shift
    ;;
  --s3-bucket)
    S3_BUCKET="$2"
    log "INFO" "--s3-bucket='${S3_BUCKET}'"
    shift
    shift
    ;;
  --osm-dir)
    OSM_DIR="$2"
    log "INFO" "--osm-dir='${OSM_DIR}'"
    shift
    shift
    ;;
  --gtfs-dir)
    GTFS_DIR="$2"
    log "INFO" "--gtfs-dir='${GTFS_DIR}'"
    shift
    shift
    ;;
  --otp-dir)
    OTP_DIR="$2"
    log "INFO" "--otp-dir='${OTP_DIR}'"
    shift
    shift
    ;;
  --max-memory)
    MAX_MEMORY="$2"
    log "INFO" "--server-max-memory='${MAX_MEMORY}'"
    shift
    shift
    ;;
  --extra-args)
    EXTRA_ARGS="$2"
    log "INFO" "--extra-args='${EXTRA_ARGS}'"
    shift
    shift
    ;;
  --mount-path)
    MOUNT_PATH="$2"
    log "INFO" "--mount-path='${MOUNT_PATH}'"
    shift
    shift
    ;;
  *)                   # unknown option
    POSITIONAL+=("$1") # save it in an array for later
    shift              # past argument
    ;;
  esac
done

set +u
set -- "${POSITIONAL[@]}" # restore positional parameters

if [[ ! -n "$1" ]]; then
  log "ERROR" "Either build or serve must be specified"
  print_help
  exit 1
fi

ACTION="$1"

set -u

CONFIG_FILES_PATH="${CONFIG_FILES_PATH:-"/app/config/${ENVIRONMENT}"}"
OSM_FILE_NAME="${OSM_FILE_NAME:-"otp"}"
S3_BUCKET="${S3_BUCKET:-"shotl-maps"}"
OSM_DIR="${OSM_DIR:-"osm"}"
GTFS_DIR="${GTFS_DIR:-"gtfs"}"
OTP_DIR="${OTP_DIR:-"otp"}"
MAX_MEMORY="${MAX_MEMORY:-2}"
EXTRA_ARGS="${EXTRA_ARGS:-}"
MOUNT_PATH="${MOUNT_PATH:-"/app/build"}"

if [[ $ACTION == "build" ]]; then
  log "INFO" "downloading OSM data from s3://${S3_BUCKET}/${OSM_DIR}/${ENVIRONMENT}/${OSM_FILE_NAME}.osm.pbf"
  aws_retry aws s3 cp s3://${S3_BUCKET}/${OSM_DIR}/${ENVIRONMENT}/${OSM_FILE_NAME}.osm.pbf ${OSM_FILE_NAME}.osm.pbf

  log "INFO" "Downloading GTFS data from S3"
  aws_retry aws s3 cp s3://${S3_BUCKET}/${GTFS_DIR}/${ENVIRONMENT}/ . --recursive --exclude "*" --include "*.gtfs.zip"

  log "INFO" "Copying config files from ${CONFIG_FILES_PATH} to current working directory"
  cp ${CONFIG_FILES_PATH}/* . || log "WARNING" "no config files were found in ${CONFIG_FILES_PATH}"

  log "INFO" "Building graph"
  java -Xmx"${MAX_MEMORY}"G ${EXTRA_ARGS} -jar otp-shaded.jar --build --save .

  log "INFO" "Uploading build to S3"
  aws_retry aws s3 cp graph.obj s3://${S3_BUCKET}/${OTP_DIR}/${ENVIRONMENT}/graph.obj

  log "INFO" "Build uploaded to s3://${S3_BUCKET}/${OTP_DIR}/${ENVIRONMENT}"
  exit 0
elif [[ $ACTION == "serve" ]]; then
  log "INFO" "downloading build from s3://${S3_BUCKET}/${OTP_DIR}/$ENVIRONMENT/graph.obj"
  aws_retry aws s3 cp s3://${S3_BUCKET}/${OTP_DIR}/${ENVIRONMENT}/graph.obj /${MOUNT_PATH}/graph.obj

  log "INFO" "Copying config files from ${CONFIG_FILES_PATH} to build working directory"
  cp ${CONFIG_FILES_PATH}/* /${MOUNT_PATH}/. || log "WARNING" "no config files were found in ${CONFIG_FILES_PATH}"

  log "INFO" "Starting OTP Router"

  java -Xmx"${MAX_MEMORY}"G ${EXTRA_ARGS} -jar otp-shaded.jar --load /${MOUNT_PATH}/. --port 80 &

  CHILD_PID=$!
  log "INFO" "OTP Router started with PID: $CHILD_PID"

  # Brief check to ensure the process started successfully
  sleep 1
  if ! kill -0 "$CHILD_PID" 2>/dev/null; then
    log "ERROR" "OTP Router failed to start"
    exit 1
  fi

  log "INFO" "OTP Router process confirmed running (Kubernetes will handle readiness checks)"
  log "INFO" "Waiting for OTP Router to complete..."

  wait "$CHILD_PID"
  local exit_code=$?
  log "INFO" "OTP Router exited with code: $exit_code"
  exit $exit_code
else
  log "ERROR" "Unrecognized CMD: '$ACTION'"
  exit 1
fi
