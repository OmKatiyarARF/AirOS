FROM jenkins/jenkins:lts

# Skip setup wizard since we use JCasC
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc/jenkins.yaml

# Install Node.js, npm, and SSH client. J1: the Docker client
# (docker-ce-cli / docker-compose-plugin) is intentionally NOT installed —
# Jenkins no longer drives Docker directly; deploys run `docker compose` on
# the target host over SSH.
USER root
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        nodejs \
        npm \
        openssh-client \
        shellcheck \
        yamllint \
    && rm -rf /var/lib/apt/lists/*

USER jenkins

# Install plugins from plugins.txt
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Copy JCasC config into image (will be available at startup)
COPY casc/ /var/jenkins_home/casc/

# Copy init script that reads repos.json and creates pipeline jobs
# Jenkins copies ref/init.groovy.d/* to $JENKINS_HOME/init.groovy.d/ on startup
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/
