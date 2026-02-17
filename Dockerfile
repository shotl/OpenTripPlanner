###############################################################################
# Stage 1: Build AWS CLI v2 from source (Alpine)
###############################################################################
FROM python:3.11-alpine3.19 AS aws-builder
ENV AWS_CLI_VERSION=2.15.0

RUN apk add --no-cache git unzip groff build-base libffi-dev cmake
RUN git clone --single-branch --depth 1 -b ${AWS_CLI_VERSION} https://github.com/aws/aws-cli.git

WORKDIR $PWD/aws-cli
RUN ./configure --with-install-type=portable-exe --with-download-deps
RUN make
RUN make install

# Reduce image size: remove autocomplete and examples
RUN rm -rf \
  /usr/local/lib/aws-cli/aws_completer \
  /usr/local/lib/aws-cli/awscli/data/ac.index \
  /usr/local/lib/aws-cli/awscli/examples
RUN find /usr/local/lib/aws-cli/awscli/data -name completions-1*.json -delete
RUN find /usr/local/lib/aws-cli/awscli/botocore/data -name examples-1.json -delete
RUN (cd /usr/local/lib/aws-cli; for a in *.so*; do test -f /lib/$a && rm $a; done)


###############################################################################
# Stage 2: Build OTP shaded JAR from source (JDK 21 required for compilation)
###############################################################################
FROM amazoncorretto:21-alpine AS otp-builder

RUN apk add --no-cache maven bash git

WORKDIR /build

# Copy Git metadata (required by git-commit-id-maven-plugin for version info)
COPY .git/ .git/

# Copy the full source tree needed for the Maven build
COPY pom.xml ./
COPY utils/ utils/
COPY raptor/ raptor/
COPY gtfs-realtime-protobuf/ gtfs-realtime-protobuf/
COPY application/ application/
COPY otp-shaded/ otp-shaded/

# Build the shaded JAR (skip tests & non-essential plugins for faster builds)
RUN mvn package -pl otp-shaded -am \
  -DskipTests \
  -Dplugin.prettier.skip=true \
  --batch-mode --no-transfer-progress && \
  # Copy the shaded JAR to a well-known location
  cp otp-shaded/target/otp-shaded-*.jar /build/otp-shaded.jar && \
  # Verify the JAR is valid
  test -s /build/otp-shaded.jar && \
  java -jar /build/otp-shaded.jar --version


###############################################################################
# Stage 3: Runtime image (JRE 21 — slim)
###############################################################################
FROM amazoncorretto:21-alpine

ENV AWS_DEFAULT_REGION=eu-west-1

# Install system dependencies
RUN apk update && \
  apk add --no-cache \
  bash \
  gcompat \
  groff \
  less \
  curl \
  unzip \
  openssl \
  wget \
  jq && \
  rm -rf /var/cache/apk/*

# Copy AWS CLI from builder stage
COPY --from=aws-builder /usr/local/lib/aws-cli/ /usr/local/lib/aws-cli/
RUN ln -s /usr/local/lib/aws-cli/aws /usr/local/bin/aws

WORKDIR /app

# Copy the shaded JAR built from source
COPY --from=otp-builder /build/otp-shaded.jar /app/otp-shaded.jar

# Copy application files
COPY script/entrypoint.sh /app/script/entrypoint.sh
RUN chmod +x /app/script/entrypoint.sh
COPY config/. /app/config/

# Create build directory for graph building
RUN mkdir -p /app/build

EXPOSE 80 10100

ENTRYPOINT [ "/app/script/entrypoint.sh" ]
